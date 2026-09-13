package com.bplay.sender;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.bplay.protocol.BplayProtocol;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures what the phone is playing and sends it alongside the video.
 *
 * <p>Requires Android 10, where {@code AudioPlaybackCapture} was introduced -- before that, an app
 * simply could not record another app's output, so on older phones the mirror is silent and that
 * is the end of it. Apps are also free to opt out of being captured, and many streaming video
 * apps do; when that happens this records silence rather than failing, which is the behaviour
 * Android intends.
 */
@RequiresApi(Build.VERSION_CODES.Q)
public final class AudioCapture implements AudioSource {

    private static final String TAG = "BPlayAudioCapture";
    private static final String MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BIT_RATE = 128_000;

    private final SenderConnection connection;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;
    private AudioRecord record;
    private MediaCodec codec;

    public AudioCapture(SenderConnection connection) {
        this.connection = connection;
    }

    @Override
    @SuppressLint("MissingPermission")
    public boolean start(MediaProjection projection) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                            .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();

            int reported = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            final int minBuffer = reported > 0 ? reported : SAMPLE_RATE * CHANNELS * 2 / 10;

            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuffer * 2)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            MediaFormat encoderFormat =
                    MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS);
            encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer * 2);

            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            record.startRecording();

            thread = new Thread(() -> pump(minBuffer), "bplay-audio-capture");
            thread.start();
            return true;
        } catch (Exception e) {
            // Never fatal: a silent mirror is a working mirror.
            Log.w(TAG, "Audio capture unavailable; continuing without sound", e);
            running.set(false);
            releaseResources();
            return false;
        }
    }

    private void pump(int bufferSize) {
        byte[] pcm = new byte[bufferSize];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long startNs = System.nanoTime();
        try {
            while (running.get()) {
                int read = record.read(pcm, 0, pcm.length);
                if (read > 0) {
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer input = codec.getInputBuffer(index);
                        if (input != null) {
                            input.clear();
                            input.put(pcm, 0, Math.min(read, input.capacity()));
                            long ptsUs = (System.nanoTime() - startNs) / 1000;
                            codec.queueInputBuffer(index, 0,
                                    Math.min(read, input.capacity()), ptsUs, 0);
                        }
                    }
                }

                int outIndex;
                while ((outIndex = codec.dequeueOutputBuffer(info, 0)) >= 0
                        || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat output = codec.getOutputFormat();
                        if (output.containsKey("csd-0")) {
                            ByteBuffer csd = output.getByteBuffer("csd-0");
                            byte[] bytes = new byte[csd.remaining()];
                            csd.duplicate().get(bytes);
                            connection.send(BplayProtocol.TYPE_AUDIO_CONFIG, bytes);
                        }
                        continue;
                    }
                    ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        byte[] payload = new byte[info.size];
                        buffer.get(payload);
                        connection.send(isConfig
                                        ? BplayProtocol.TYPE_AUDIO_CONFIG : BplayProtocol.TYPE_AUDIO,
                                0, info.presentationTimeUs, payload, 0, payload.length);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Audio capture stopped", e);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        releaseResources();
    }

    private void releaseResources() {
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
                // Never started.
            }
            try {
                record.release();
            } catch (Exception ignored) {
                // Nothing further to do.
            }
            record = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
                // Already stopped.
            }
            try {
                codec.release();
            } catch (Exception ignored) {
                // Nothing further to do.
            }
            codec = null;
        }
    }
}
