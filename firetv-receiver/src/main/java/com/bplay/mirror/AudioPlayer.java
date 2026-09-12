package com.bplay.mirror;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.bplay.protocol.BplayProtocol;
import com.bplay.protocol.Packet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays the sender's audio track, when there is one.
 *
 * <p>Entirely optional by design: Android only allows an app to capture another app's audio from
 * API 29, browsers only offer tab or system audio on some platforms, and iOS gives app audio but
 * not system audio. So audio failing must never take video down with it -- every failure path
 * here degrades to a silent mirror rather than ending the session.
 */
public final class AudioPlayer {

    private static final String TAG = "BPlayAudio";
    private static final int QUEUE_CAPACITY = 120;

    private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean();

    private Thread thread;
    private volatile String mime;
    private volatile byte[] csd;
    private volatile int sampleRate = 48000;
    private volatile int channelCount = 2;
    private volatile boolean configured;

    /**
     * @param mime {@code audio/mp4a-latm} (Android senders) or {@code audio/opus} (browsers)
     * @param csd  codec-specific data, or null to synthesise a default for Opus
     */
    public void configure(String mime, byte[] csd, int sampleRate, int channelCount) {
        this.mime = mime;
        this.csd = csd;
        if (sampleRate > 0) {
            this.sampleRate = sampleRate;
        }
        if (channelCount > 0) {
            this.channelCount = channelCount;
        }
        this.configured = true;
    }

    public boolean isConfigured() {
        return configured;
    }

    public void start() {
        if (!configured || !running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::run, "bplay-audio");
        thread.start();
    }

    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        queue.clear();
    }

    public void submit(Packet packet) {
        if (!running.get()) {
            return;
        }
        if (!queue.offer(packet)) {
            // Stale audio is worse than no audio: drop it and stay near live.
            queue.clear();
            queue.offer(packet);
        }
    }

    private void run() {
        MediaCodec codec = null;
        AudioTrack track = null;
        try {
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(buildFormat(), null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running.get()) {
                Packet packet = queue.poll(20, TimeUnit.MILLISECONDS);
                if (packet != null && packet.type == BplayProtocol.TYPE_AUDIO) {
                    int index = codec.dequeueInputBuffer(5000);
                    if (index >= 0) {
                        ByteBuffer input = codec.getInputBuffer(index);
                        if (input != null && input.capacity() >= packet.payload.length) {
                            input.clear();
                            input.put(packet.payload);
                            codec.queueInputBuffer(index, 0, packet.payload.length,
                                    packet.ptsUs, 0);
                        } else {
                            codec.queueInputBuffer(index, 0, 0, packet.ptsUs, 0);
                        }
                    }
                }

                int outIndex;
                while ((outIndex = codec.dequeueOutputBuffer(info, 0)) >= 0
                        || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat format = codec.getOutputFormat();
                        track = replaceTrack(track, format);
                        continue;
                    }
                    ByteBuffer output = codec.getOutputBuffer(outIndex);
                    if (output != null && info.size > 0) {
                        if (track == null) {
                            track = replaceTrack(null, codec.getOutputFormat());
                        }
                        byte[] pcm = new byte[info.size];
                        output.position(info.offset);
                        output.get(pcm, 0, info.size);
                        track.write(pcm, 0, pcm.length);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Silent mirroring beats a dead session.
            Log.w(TAG, "Audio playback stopped; continuing without sound", e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                    // Already in an error state; release() still matters.
                }
                try {
                    codec.release();
                } catch (Exception ignored) {
                    // Nothing further to do.
                }
            }
            releaseTrack(track);
        }
    }

    private MediaFormat buildFormat() {
        MediaFormat format = MediaFormat.createAudioFormat(mime, sampleRate, channelCount);
        byte[] data = csd;
        if (MediaFormat.MIMETYPE_AUDIO_OPUS.equals(mime)) {
            if (data == null || data.length < 19) {
                data = opusIdentificationHeader(sampleRate, channelCount);
            }
            format.setByteBuffer("csd-0", ByteBuffer.wrap(data));
            // Android's Opus decoder insists on all three: the pre-skip and seek pre-roll are
            // 64-bit little-endian nanosecond values, not the sample counts the Ogg header uses.
            format.setByteBuffer("csd-1", ByteBuffer.wrap(nanosLe(80_000_000L)));  // 3840 samples
            format.setByteBuffer("csd-2", ByteBuffer.wrap(nanosLe(80_000_000L)));
        } else if (data != null) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(data));
        }
        return format;
    }

    /** The 19-byte OpusHead a raw WebCodecs Opus stream does not carry. */
    static byte[] opusIdentificationHeader(int sampleRate, int channels) {
        ByteBuffer buffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.put((byte) 1);                 // version
        buffer.put((byte) channels);
        buffer.putShort((short) 3840);        // pre-skip, the usual 80 ms at 48 kHz
        buffer.putInt(sampleRate);
        buffer.putShort((short) 0);           // output gain
        buffer.put((byte) 0);                 // channel mapping family
        return buffer.array();
    }

    static byte[] nanosLe(long nanos) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(nanos).array();
    }

    private AudioTrack replaceTrack(AudioTrack existing, MediaFormat format) {
        releaseTrack(existing);
        int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : sampleRate;
        int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channelCount;
        int channelMask = channels >= 2
                ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;

        int minBuffer = AudioTrack.getMinBufferSize(rate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            minBuffer = rate * channels * 2 / 5; // ~200 ms fallback
        }
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2, AudioTrack.MODE_STREAM);
        track.play();
        Log.i(TAG, "Audio output at " + rate + " Hz, " + channels + " channel(s)");
        return track;
    }

    private static void releaseTrack(AudioTrack track) {
        if (track == null) {
            return;
        }
        try {
            track.stop();
        } catch (Exception ignored) {
            // Uninitialised tracks throw here; release() below is what matters.
        }
        try {
            track.release();
        } catch (Exception ignored) {
            // Nothing further to do.
        }
    }
}
