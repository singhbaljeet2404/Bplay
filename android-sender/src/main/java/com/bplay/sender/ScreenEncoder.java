package com.bplay.sender;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.bplay.protocol.BplayProtocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns the phone's screen into an H.264 stream.
 *
 * <p>{@code MediaProjection} paints into the encoder's input surface directly, so frames never
 * pass through application memory -- the GPU hands them to the encoder and the encoder hands back
 * compressed bytes. That is what makes mirroring at 1080p viable on a mid-range phone.
 */
public final class ScreenEncoder {

    private static final String TAG = "BPlayEncoder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int FRAME_RATE = 30;

    /**
     * A keyframe every second. Costs a little bandwidth and buys fast recovery: the TV can start
     * displaying, or resync after a dropped packet, within a second rather than whenever the
     * encoder felt like it.
     */
    private static final int I_FRAME_INTERVAL_SECONDS = 1;

    public interface Listener {
        void onEncoderError(String message);
    }

    private final SenderConnection connection;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean();

    private MediaCodec codec;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private Thread drainThread;

    public ScreenEncoder(SenderConnection connection, Listener listener) {
        this.connection = connection;
        this.listener = listener;
    }

    public void start(MediaProjection projection, int width, int height, int dpi, int bitrate)
            throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // A screen is mostly static, so let the encoder idle rather than burn bitrate and
            // battery re-sending an unchanged desktop 30 times a second.
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FRAME_RATE);
        }

        codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();

        virtualDisplay = projection.createVirtualDisplay("BPlay",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null);

        drainThread = new Thread(this::drain, "bplay-encoder");
        drainThread.start();
        Log.i(TAG, "Encoding " + width + "x" + height + " at " + bitrate / 1000 + " kbps");
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running.get()) {
                int index = codec.dequeueOutputBuffer(info, 100_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The encoder reports SPS/PPS here as well as in the first buffer; sending it
                    // now means the TV can configure before the first keyframe even arrives.
                    MediaFormat outputFormat = codec.getOutputFormat();
                    byte[] csd = extractCsd(outputFormat);
                    if (csd != null) {
                        connection.send(BplayProtocol.TYPE_VIDEO_CONFIG, csd);
                    }
                    continue;
                }
                if (index < 0) {
                    continue;
                }

                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    byte[] payload = new byte[info.size];
                    buffer.get(payload);

                    boolean isConfig =
                            (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    connection.send(
                            isConfig ? BplayProtocol.TYPE_VIDEO_CONFIG : BplayProtocol.TYPE_VIDEO,
                            isKey ? BplayProtocol.FLAG_KEYFRAME : 0,
                            info.presentationTimeUs, payload, 0, payload.length);
                }
                codec.releaseOutputBuffer(index, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                listener.onEncoderError("Lost the connection to the TV");
            }
        } catch (IllegalStateException e) {
            if (running.get()) {
                listener.onEncoderError("The encoder stopped unexpectedly");
            }
        } catch (Exception e) {
            Log.e(TAG, "Encoder failed", e);
            if (running.get()) {
                listener.onEncoderError(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }

    /** Pulls SPS and PPS out of the encoder's output format and concatenates them. */
    static byte[] extractCsd(MediaFormat format) {
        ByteBuffer sps = format.containsKey("csd-0") ? format.getByteBuffer("csd-0") : null;
        ByteBuffer pps = format.containsKey("csd-1") ? format.getByteBuffer("csd-1") : null;
        if (sps == null) {
            return null;
        }
        int total = sps.remaining() + (pps != null ? pps.remaining() : 0);
        byte[] csd = new byte[total];
        int position = sps.remaining();
        sps.duplicate().get(csd, 0, position);
        if (pps != null) {
            pps.duplicate().get(csd, position, pps.remaining());
        }
        return csd;
    }

    public void stop() {
        running.set(false);
        if (drainThread != null) {
            drainThread.interrupt();
            try {
                drainThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drainThread = null;
        }
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception ignored) {
                // Already released with the projection.
            }
            virtualDisplay = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
                // Already in an error state.
            }
            try {
                codec.release();
            } catch (Exception ignored) {
                // Nothing further to do.
            }
            codec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
    }
}
