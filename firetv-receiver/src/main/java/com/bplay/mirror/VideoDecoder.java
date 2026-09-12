package com.bplay.mirror;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.bplay.protocol.BplayProtocol;
import com.bplay.protocol.Packet;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decodes the incoming H.264 stream straight onto the TV's surface.
 *
 * <p>One decoder serves every sender -- Android's {@code MediaCodec} encoder, iOS
 * {@code VideoToolbox}, and a browser's {@code WebCodecs} all hand us Annex-B access units, so
 * nothing above this class needs to know where a frame came from.
 *
 * <p>Tuned for latency rather than smoothness: frames are rendered the moment they decode instead
 * of being scheduled against their timestamps. Mirroring a laptop is interactive -- a cursor that
 * trails the mouse by half a second is useless, and nobody notices a millisecond of jitter.
 */
public final class VideoDecoder {

    private static final String TAG = "BPlayVideo";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    /** Deep enough to ride out a Wi-Fi hiccup, shallow enough not to build up lag. */
    private static final int QUEUE_CAPACITY = 90;

    public interface Listener {
        /** Reports the decoder's real output size so the UI can letterbox correctly. */
        void onVideoSize(int width, int height);

        void onFirstFrame();

        void onDecoderError(String message);
    }

    private final Listener listener;
    private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object configLock = new Object();

    private Thread thread;
    private volatile Surface surface;
    private byte[] csd;
    private int declaredWidth = 1280;
    private int declaredHeight = 720;
    private volatile boolean reconfigureRequested;
    private volatile boolean firstFrameReported;

    public VideoDecoder(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        firstFrameReported = false;
        thread = new Thread(this::run, "bplay-video");
        thread.start();
    }

    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        queue.clear();
    }

    /** Surface arrives from the activity, usually after the stream has already started. */
    public void setSurface(Surface surface) {
        this.surface = surface;
        reconfigureRequested = true;
    }

    /** Whether the decoder has the SPS/PPS it needs in order to configure. */
    public boolean hasConfig() {
        synchronized (configLock) {
            return csd != null;
        }
    }

    /** Codec-specific data (SPS/PPS). A new one mid-stream means the sender rotated or resized. */
    public void setConfig(byte[] newCsd, int width, int height) {
        synchronized (configLock) {
            boolean changed = csd == null || !java.util.Arrays.equals(csd, newCsd)
                    || width != declaredWidth || height != declaredHeight;
            csd = newCsd;
            if (width > 0) {
                declaredWidth = width;
            }
            if (height > 0) {
                declaredHeight = height;
            }
            if (changed) {
                reconfigureRequested = true;
            }
        }
    }

    /**
     * Hands a packet to the decode thread. Never blocks the network thread: if the decoder has
     * fallen behind, the backlog is dropped and playback resumes at the next keyframe. Blocking
     * here instead would push back on TCP and turn a brief stall into permanent lag.
     */
    public void submit(Packet packet) {
        if (!running.get()) {
            return;
        }
        if (!queue.offer(packet)) {
            queue.clear();
            Log.w(TAG, "Decoder fell behind; dropped the backlog and resyncing on next keyframe");
            queue.offer(packet);
        }
    }

    private void run() {
        while (running.get()) {
            MediaCodec codec = null;
            try {
                Surface target = surface;
                byte[] config;
                int width;
                int height;
                synchronized (configLock) {
                    config = csd;
                    width = declaredWidth;
                    height = declaredHeight;
                }
                if (target == null || config == null) {
                    Thread.sleep(50); // waiting for the activity's surface or the sender's SPS/PPS
                    continue;
                }

                reconfigureRequested = false;
                codec = createCodec(target, config, width, height);
                decodeLoop(codec);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Log.e(TAG, "Decoder failed", e);
                if (running.get()) {
                    listener.onDecoderError(e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                    // Drop everything buffered: after a codec error the old packets reference a
                    // decoder state that no longer exists.
                    queue.clear();
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                releaseQuietly(codec);
            }
        }
    }

    private MediaCodec createCodec(Surface target, byte[] config, int width, int height)
            throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(config));

        // Declaring a generous maximum lets an adaptive decoder absorb a phone rotating from
        // portrait to landscape without a full reconfigure (and the black flash that comes with it).
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, Math.max(1920, Math.max(width, height)));
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, Math.max(1920, Math.max(width, height)));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }

        MediaCodec codec = MediaCodec.createDecoderByType(MIME);
        codec.configure(format, target, null, 0);
        codec.start();
        Log.i(TAG, "Decoder started at " + width + "x" + height + " (" + codec.getName() + ")");
        return codec;
    }

    private void decodeLoop(MediaCodec codec) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawKeyframe = false;

        while (running.get() && !reconfigureRequested) {
            drainOutput(codec, info);

            Packet packet = queue.poll(10, TimeUnit.MILLISECONDS);
            if (packet == null) {
                continue;
            }
            if (packet.type != BplayProtocol.TYPE_VIDEO) {
                continue;
            }
            // A decoder handed a P-frame it has no reference for produces either garbage or an
            // error, so wait for the sender's next IDR before feeding anything.
            if (!sawKeyframe) {
                if (!packet.isKeyframe()) {
                    continue;
                }
                sawKeyframe = true;
            }

            int inputIndex;
            while ((inputIndex = codec.dequeueInputBuffer(5000)) < 0) {
                if (!running.get() || reconfigureRequested) {
                    return;
                }
                drainOutput(codec, info);
            }
            ByteBuffer input = codec.getInputBuffer(inputIndex);
            if (input == null) {
                codec.queueInputBuffer(inputIndex, 0, 0, packet.ptsUs, 0);
                continue;
            }
            input.clear();
            if (input.capacity() < packet.payload.length) {
                Log.w(TAG, "Frame larger than the codec's input buffer; skipping");
                codec.queueInputBuffer(inputIndex, 0, 0, packet.ptsUs, 0);
                sawKeyframe = false;
                continue;
            }
            input.put(packet.payload);
            codec.queueInputBuffer(inputIndex, 0, packet.payload.length, packet.ptsUs,
                    packet.isKeyframe() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        }
    }

    private void drainOutput(MediaCodec codec, MediaCodec.BufferInfo info) {
        while (true) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat out = codec.getOutputFormat();
                int w = out.getInteger(MediaFormat.KEY_WIDTH);
                int h = out.getInteger(MediaFormat.KEY_HEIGHT);
                // Crop values, when present, are the only accurate size: a 1080p stream is coded
                // as 1088 lines and would otherwise letterbox with 8 rows of noise.
                if (out.containsKey("crop-left") && out.containsKey("crop-right")) {
                    w = out.getInteger("crop-right") - out.getInteger("crop-left") + 1;
                }
                if (out.containsKey("crop-top") && out.containsKey("crop-bottom")) {
                    h = out.getInteger("crop-bottom") - out.getInteger("crop-top") + 1;
                }
                Log.i(TAG, "Output format is now " + w + "x" + h);
                listener.onVideoSize(w, h);
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            }
            if (index < 0) {
                return;
            }
            boolean render = info.size > 0;
            codec.releaseOutputBuffer(index, render);
            if (render && !firstFrameReported) {
                firstFrameReported = true;
                listener.onFirstFrame();
            }
        }
    }

    private static void releaseQuietly(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (Exception ignored) {
            // stop() throws if the codec already errored out; release() below still applies.
        }
        try {
            codec.release();
        } catch (Exception ignored) {
            // Nothing left to do if the codec cannot be released.
        }
    }
}
