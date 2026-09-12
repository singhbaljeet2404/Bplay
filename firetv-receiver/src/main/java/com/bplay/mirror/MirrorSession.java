package com.bplay.mirror;

import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import com.bplay.protocol.AnnexB;
import com.bplay.protocol.BplayProtocol;
import com.bplay.protocol.HandshakeCodec;
import com.bplay.protocol.Packet;
import com.bplay.protocol.Params;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One device mirroring to this TV: the decoders, the sender's details, and the routing between
 * them. Created once a handshake is accepted and discarded when the sender goes away.
 */
public final class MirrorSession implements VideoDecoder.Listener {

    private static final String TAG = "BPlaySession";

    public interface Callback {
        void onSessionVideoSize(MirrorSession session, int width, int height);

        void onSessionFirstFrame(MirrorSession session);

        void onSessionEnded(MirrorSession session, String reason);
    }

    /** How the sender reached us -- shown on screen so the user can tell the paths apart. */
    public enum Transport { TCP, WEBSOCKET }

    private final Callback callback;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final VideoDecoder video = new VideoDecoder(this);
    private final AudioPlayer audio = new AudioPlayer();

    public final String deviceName;
    public final String platform;
    public final Transport transport;
    public final long startedAtMs = System.currentTimeMillis();

    private volatile int declaredWidth;
    private volatile int declaredHeight;
    private volatile Runnable onClose;
    private final String audioMimeFromHandshake;
    private final int declaredAudioSampleRate;
    private final int declaredAudioChannels;

    public MirrorSession(Callback callback, HandshakeCodec.Request request, Transport transport) {
        this.callback = callback;
        this.transport = transport;
        this.deviceName = request.deviceName();
        this.platform = request.platform();
        this.declaredWidth = request.params.getInt(HandshakeCodec.KEY_WIDTH, 1280);
        this.declaredHeight = request.params.getInt(HandshakeCodec.KEY_HEIGHT, 720);

        this.declaredAudioSampleRate = request.params.getInt("asamplerate", 48000);
        this.declaredAudioChannels = request.params.getInt("achannels", 2);

        String audioCodec = request.audioCodec();
        this.audioMimeFromHandshake = normaliseAudioMime(
                audioCodec.isEmpty() ? MediaFormat.MIMETYPE_AUDIO_AAC : audioCodec);
        if (!audioCodec.isEmpty()) {
            // Describe it now, start it lazily on the first config packet: an audio decoder spun
            // up for a sender that turns out to have no audio track would sit there holding an
            // AudioTrack open for nothing.
            audio.configure(audioMimeFromHandshake, null,
                    declaredAudioSampleRate, declaredAudioChannels);
        }
        video.start();
        Log.i(TAG, "Session opened: " + deviceName + " (" + platform + ") over " + transport);
    }

    /** Called when the connection closes, so the transport can clean its socket up. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    public void attachSurface(Surface surface) {
        video.setSurface(surface);
    }

    public void detachSurface() {
        video.setSurface(null);
    }

    public void handlePacket(Packet packet) {
        if (closed.get()) {
            return;
        }
        switch (packet.type) {
            case BplayProtocol.TYPE_VIDEO_CONFIG:
                video.setConfig(packet.payload, declaredWidth, declaredHeight);
                break;
            case BplayProtocol.TYPE_VIDEO:
                // Browsers encoding in Annex-B mode never send a separate config packet; their
                // SPS/PPS ride inline on each keyframe, so recover it from the first one.
                if (!video.hasConfig() && packet.isKeyframe()) {
                    byte[] inlineCsd = AnnexB.extractCsd(packet.payload);
                    if (inlineCsd != null) {
                        video.setConfig(inlineCsd, declaredWidth, declaredHeight);
                    }
                }
                video.submit(packet);
                break;
            case BplayProtocol.TYPE_AUDIO_CONFIG:
                handleAudioConfig(packet);
                break;
            case BplayProtocol.TYPE_AUDIO:
                audio.submit(packet);
                break;
            case BplayProtocol.TYPE_META:
                handleMeta(packet);
                break;
            case BplayProtocol.TYPE_PING:
                break; // presence is the whole message
            case BplayProtocol.TYPE_BYE:
                close("Sender disconnected");
                break;
            default:
                Log.w(TAG, "Ignoring unknown packet type " + packet.type);
                break;
        }
    }

    private void handleAudioConfig(Packet packet) {
        // Two shapes arrive here. Android and iOS senders have real codec-specific data and send
        // the raw bytes. Browsers producing Opus have none, so they send a Params description
        // instead; the "codec" key is what tells the two apart.
        Params params = Params.decode(packet.payload);
        if (params.has("codec")) {
            byte[] csd = params.has("csd") ? hexToBytes(params.get("csd", "")) : null;
            audio.configure(normaliseAudioMime(params.get("codec", "")),
                    csd != null && csd.length > 0 ? csd : null,
                    params.getInt("sampleRate", 48000), params.getInt("channels", 2));
        } else {
            audio.configure(audioMimeFromHandshake, packet.payload,
                    declaredAudioSampleRate, declaredAudioChannels);
        }
        audio.start();
    }

    private void handleMeta(Packet packet) {
        Params params = Params.decode(packet.payload);
        int width = params.getInt(HandshakeCodec.KEY_WIDTH, 0);
        int height = params.getInt(HandshakeCodec.KEY_HEIGHT, 0);
        if (width > 0 && height > 0) {
            declaredWidth = width;
            declaredHeight = height;
            Log.i(TAG, "Sender resized to " + width + "x" + height);
        }
    }

    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Log.i(TAG, "Session with " + deviceName + " ended: " + reason);
        video.stop();
        audio.stop();
        Runnable r = onClose;
        if (r != null) {
            try {
                r.run();
            } catch (Exception e) {
                Log.w(TAG, "Transport cleanup failed", e);
            }
        }
        callback.onSessionEnded(this, reason);
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void onVideoSize(int width, int height) {
        callback.onSessionVideoSize(this, width, height);
    }

    @Override
    public void onFirstFrame() {
        callback.onSessionFirstFrame(this);
    }

    @Override
    public void onDecoderError(String message) {
        Log.w(TAG, "Decoder error: " + message);
        // Recoverable on its own -- VideoDecoder rebuilds the codec and resyncs on the next
        // keyframe -- so this is not a reason to tear the session down.
    }

    private static String normaliseAudioMime(String codec) {
        if (codec == null) {
            return MediaFormat.MIMETYPE_AUDIO_OPUS;
        }
        String lower = codec.toLowerCase(java.util.Locale.US);
        if (lower.contains("opus")) {
            return MediaFormat.MIMETYPE_AUDIO_OPUS;
        }
        if (lower.contains("aac") || lower.contains("mp4a")) {
            return MediaFormat.MIMETYPE_AUDIO_AAC;
        }
        return codec;
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return new byte[0];
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }
}
