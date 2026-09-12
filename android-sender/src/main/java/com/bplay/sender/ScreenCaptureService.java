package com.bplay.sender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.bplay.protocol.BplayProtocol;

/**
 * Runs the mirror: holds the screen-capture permission, drives the encoder, owns the socket.
 *
 * <p>A foreground service because screen capture legally requires one, and because mirroring
 * obviously has to keep running once the user leaves this app -- which is the entire point.
 */
public final class ScreenCaptureService extends Service implements ScreenEncoder.Listener {

    private static final String TAG = "BPlayCapture";
    private static final String CHANNEL_ID = "bplay-sender";
    private static final int NOTIFICATION_ID = 42;

    public static final String ACTION_START = "com.bplay.sender.START";
    public static final String ACTION_STOP = "com.bplay.sender.STOP";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_PIN = "pin";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_DPI = "dpi";
    public static final String EXTRA_MAX_HEIGHT = "maxHeight";
    public static final String EXTRA_AUDIO = "audio";
    public static final String EXTRA_DEVICE_NAME = "deviceName";

    public interface StateListener {
        void onMirroringStarted(String tvName);

        void onMirroringStopped(String reason);
    }

    private static volatile StateListener listener;
    private static volatile boolean running;

    public static void setListener(StateListener value) {
        listener = value;
    }

    public static boolean isRunning() {
        return running;
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, ScreenCaptureService.class)
                .setAction(ACTION_STOP));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private ScreenEncoder encoder;
    private AudioCapture audioCapture;
    private SenderConnection connection;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown("Stopped");
            return START_NOT_STICKY;
        }

        // Android 14 requires the foreground service to already be running before the media
        // projection token is redeemed, so this has to come first.
        goForeground("Connecting…");

        new Thread(() -> startMirroring(intent), "bplay-start").start();
        return START_NOT_STICKY;
    }

    private void startMirroring(Intent intent) {
        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, BplayProtocol.DEFAULT_STREAM_PORT);
        String pin = intent.getStringExtra(EXTRA_PIN);
        int width = intent.getIntExtra(EXTRA_WIDTH, 1080);
        int height = intent.getIntExtra(EXTRA_HEIGHT, 1920);
        int dpi = intent.getIntExtra(EXTRA_DPI, 320);
        int requestedMaxHeight = intent.getIntExtra(EXTRA_MAX_HEIGHT, 1080);
        boolean wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, true);
        String deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);

        boolean audioPossible = wantAudio && AudioCapture.isSupported();

        try {
            connection = new SenderConnection();
            connection.connect(host, port, pin, deviceName, width, height, audioPossible);

            int ceiling = Math.min(requestedMaxHeight, connection.maxHeight());
            int[] size = fitWithin(width, height, ceiling);
            int bitrate = Math.min(bitrateFor(size[1]), connection.maxBitrate());

            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                throw new IllegalStateException("Screen capture permission was not granted");
            }
            // Registering a callback is mandatory from Android 14 and, everywhere, the only way
            // to notice the user revoking capture from the system UI.
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    shutdown("Screen sharing was stopped");
                }
            }, main);

            encoder = new ScreenEncoder(connection, this);
            encoder.start(projection, size[0], size[1], dpi, bitrate);

            if (audioPossible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioCapture = new AudioCapture(connection);
                if (!audioCapture.start(projection)) {
                    audioCapture = null;
                }
            }

            running = true;
            String tvName = connection.tvName();
            goForeground("Mirroring to " + tvName);
            main.post(() -> {
                StateListener l = listener;
                if (l != null) {
                    l.onMirroringStarted(tvName);
                }
            });
            Log.i(TAG, "Mirroring " + size[0] + "x" + size[1] + " to " + tvName);
        } catch (SenderConnection.RejectedException e) {
            shutdown(e.getMessage());
        } catch (java.net.SocketTimeoutException e) {
            shutdown("The TV did not answer. Check it is on the same Wi-Fi network.");
        } catch (java.net.ConnectException e) {
            shutdown("Could not reach the TV. Is BPlay open on it?");
        } catch (Exception e) {
            Log.e(TAG, "Could not start mirroring", e);
            shutdown(e.getMessage() == null ? "Could not start mirroring" : e.getMessage());
        }
    }

    /** Scales to the TV's ceiling, preserving aspect and keeping both sides even for H.264. */
    static int[] fitWithin(int width, int height, int maxHeight) {
        int longest = Math.max(width, height);
        int shortest = Math.min(width, height);
        if (longest <= maxHeight * 16 / 9 && shortest <= maxHeight) {
            return new int[]{even(width), even(height)};
        }
        float scale = Math.min((float) maxHeight / shortest,
                (float) (maxHeight * 16 / 9) / longest);
        return new int[]{even(Math.round(width * scale)), even(Math.round(height * scale))};
    }

    private static int even(int value) {
        int clamped = Math.max(2, value);
        return clamped % 2 == 0 ? clamped : clamped - 1;
    }

    static int bitrateFor(int height) {
        if (height <= 480) return 2_000_000;
        if (height <= 720) return 4_000_000;
        return 8_000_000;
    }

    @Override
    public void onEncoderError(String message) {
        shutdown(message);
    }

    private void shutdown(String reason) {
        if (!running && encoder == null && connection == null) {
            stopSelf();
            return;
        }
        running = false;
        if (audioCapture != null) {
            audioCapture.stop();
            audioCapture = null;
        }
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
                // Already stopped by the system.
            }
            projection = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
        main.post(() -> {
            StateListener l = listener;
            if (l != null) {
                l.onMirroringStopped(reason);
            }
        });
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown("Stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void goForeground(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Screen mirroring", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP), flags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_cast)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .addAction(0, getString(R.string.stop), stop)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}
