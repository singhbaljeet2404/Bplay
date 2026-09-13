package com.bplay.mirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.bplay.protocol.BplayProtocol;
import com.bplay.protocol.HandshakeCodec;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns everything that has to outlive an activity: the two servers, the advertising, and the one
 * session that may be running at a time.
 *
 * <p>A foreground service because mirroring must survive the user pressing Home on the remote, and
 * because Fire OS is aggressive about reclaiming background processes that hold sockets open.
 */
public final class MirrorService extends Service implements SessionHost, MirrorSession.Callback {

    private static final String TAG = "BPlayService";
    private static final String CHANNEL_ID = "bplay-mirror";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START = "com.bplay.mirror.START";
    public static final String ACTION_STOP = "com.bplay.mirror.STOP";

    public enum State { STOPPED, STARTING, RUNNING, ERROR }

    /** A snapshot of everything the UI draws. Immutable so it can cross threads safely. */
    public static final class Status {
        public final State state;
        public final String address;
        public final String pin;
        public final String deviceName;
        public final String error;
        public final MirrorSession session;

        Status(State state, String address, String pin, String deviceName, String error,
               MirrorSession session) {
            this.state = state;
            this.address = address;
            this.pin = pin;
            this.deviceName = deviceName;
            this.error = error;
            this.session = session;
        }

        public boolean isMirroring() {
            return session != null && !session.isClosed();
        }

        /** The URL a browser should open, or null before the servers are up. */
        public String senderUrl() {
            return address == null ? null
                    : "https://" + address + ":" + BplayProtocol.DEFAULT_WEB_PORT + "/";
        }
    }

    public interface StatusListener {
        void onStatusChanged(Status status);
    }

    private static volatile MirrorService instance;

    /**
     * Static because an activity always subscribes before this service exists. Starting a service
     * only queues its creation, and the activity's onResume runs first on the same main-looper
     * pass -- so a listener list owned by the instance could never be reached in time, and the UI
     * would wait forever for a callback nobody was there to send.
     */
    private static final List<StatusListener> LISTENERS = new CopyOnWriteArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object sessionLock = new Object();

    private CertificateStore certificates;
    private TcpStreamServer tcpServer;
    private WebServer webServer;
    private LocalMediaServer mediaServer;
    private Discovery discovery;
    private BleBeacon beacon;

    private volatile State state = State.STOPPED;
    private volatile String address;
    private volatile String error;
    private volatile MirrorSession activeSession;

    public static MirrorService current() {
        return instance;
    }

    public static void startServers(Context context) {
        Intent intent = new Intent(context, MirrorService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        certificates = new CertificateStore(this);
        discovery = new Discovery(this);
        beacon = new BleBeacon(this);
        // Whoever subscribed while this did not yet exist is still waiting.
        main.post(this::notifyListeners);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }
        // Must be inside 5 seconds of the start request on modern Android, and before any slow
        // work, or the system kills the process with a ForegroundServiceDidNotStartInTime crash.
        goForeground("Starting...");
        if (state == State.STOPPED || state == State.ERROR) {
            state = State.STARTING;
            notifyListeners();
            new Thread(this::bringUpServers, "bplay-startup").start();
        }
        return START_STICKY;
    }

    private void bringUpServers() {
        long startedAt = android.os.SystemClock.elapsedRealtime();
        try {
            address = NetworkUtils.primaryAddress();
            if (address == null) {
                throw new IllegalStateException(
                        "No network connection. Connect this Fire TV to Wi-Fi or Ethernet.");
            }

            // RSA keygen on Fire TV hardware is slow enough to be visible; this is why startup
            // runs off the main thread and the UI shows a "Starting" state.
            certificates.prepare();

            tcpServer = new TcpStreamServer(this, BplayProtocol.DEFAULT_STREAM_PORT);
            tcpServer.start();

            webServer = new WebServer(this, this, BplayProtocol.DEFAULT_WEB_PORT);
            webServer.makeSecure(certificates.serverSocketFactory(),
                    CertificateStore.enabledProtocols());
            webServer.startServer();

            discovery.register(deviceName(), BplayProtocol.DEFAULT_STREAM_PORT,
                    BplayProtocol.DEFAULT_WEB_PORT, !requiredPin().isEmpty());
            beacon.start(address, requiredPin());

            state = State.RUNNING;
            error = null;
            Log.i(TAG, "Ready at " + address + " after "
                    + (android.os.SystemClock.elapsedRealtime() - startedAt) + " ms"
                    + " (cert " + certificates.fingerprint() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Startup failed", e);
            error = e.getMessage() != null ? e.getMessage() : e.toString();
            state = State.ERROR;
            stopServersQuietly();
        }
        main.post(() -> {
            goForeground(statusText());
            notifyListeners();
        });
    }

    private String statusText() {
        switch (state) {
            case RUNNING:
                MirrorSession session = activeSession;
                return session != null && !session.isClosed()
                        ? "Mirroring " + session.deviceName
                        : "Ready at " + address;
            case STARTING:
                return "Starting...";
            case ERROR:
                return "Error: " + error;
            default:
                return "Stopped";
        }
    }

    private void stopEverything() {
        MirrorSession session = activeSession;
        if (session != null) {
            session.close("TV stopped mirroring");
        }
        stopServersQuietly();
        state = State.STOPPED;
        notifyListeners();
    }

    private void stopServersQuietly() {
        try {
            if (discovery != null) {
                discovery.unregister();
            }
        } catch (Exception ignored) {
            // Teardown is best effort.
        }
        try {
            if (beacon != null) {
                beacon.stop();
            }
        } catch (Exception ignored) {
            // Teardown is best effort.
        }
        try {
            if (tcpServer != null) {
                tcpServer.stop();
                tcpServer = null;
            }
        } catch (Exception ignored) {
            // Teardown is best effort.
        }
        try {
            if (webServer != null) {
                webServer.stop();
                webServer = null;
            }
        } catch (Exception ignored) {
            // Teardown is best effort.
        }
        try {
            if (mediaServer != null) {
                mediaServer.stop();
                mediaServer = null;
            }
        } catch (Exception ignored) {
            // Teardown is best effort.
        }
    }

    @Override
    public void onDestroy() {
        stopEverything();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // activities reach the service through current()
    }

    // ---- SessionHost -----------------------------------------------------

    @Override
    public String requiredPin() {
        return Prefs.requiredPin(this);
    }

    @Override
    public String deviceName() {
        return Prefs.deviceName(this);
    }

    @Override
    public int maxHeight() {
        return Prefs.maxHeight(this);
    }

    @Override
    public int maxBitrate() {
        return Prefs.maxBitrate(this);
    }

    @Override
    public MirrorSession tryBeginSession(HandshakeCodec.Request request,
                                         MirrorSession.Transport transport) {
        MirrorSession session;
        synchronized (sessionLock) {
            MirrorSession existing = activeSession;
            if (existing != null && !existing.isClosed()) {
                Log.w(TAG, "Refused " + request.deviceName() + ": " + existing.deviceName
                        + " is already mirroring");
                return null;
            }
            session = new MirrorSession(this, request, transport);
            activeSession = session;
        }
        main.post(() -> {
            startActivity(new Intent(this, MirrorActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            goForeground(statusText());
            notifyListeners();
        });
        return session;
    }

    // ---- native file playback -------------------------------------------

    @Override
    public void onSessionMediaOffered(MirrorSession session) {
        main.post(() -> {
            MirrorSession active = activeSession;
            if (active != session) {
                return;
            }
            try {
                // One server per session: it reads through that session's relay, and stops with it.
                if (mediaServer != null) {
                    mediaServer.stop();
                }
                mediaServer = new LocalMediaServer(session.relay());
            } catch (Exception e) {
                Log.w(TAG, "Could not start the local media server", e);
                mediaServer = null;
                return;
            }
            startActivity(new Intent(this, MediaPlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            goForeground(statusText());
            notifyListeners();
        });
    }

    @Override
    public void onSessionMediaControl(MirrorSession session, String action, int positionMs) {
        main.post(() -> {
            MediaPlaybackActivity player = MediaPlaybackActivity.current();
            if (player != null) {
                player.applyControl(action, positionMs);
            }
        });
    }

    /** The loopback URL the television's own player reads from, or null when nothing is offered. */
    public String mediaUrl() {
        LocalMediaServer server = mediaServer;
        MirrorSession session = activeSession;
        if (server == null || session == null) {
            return null;
        }
        MediaRelay.Offer offer = session.relay().offer();
        return offer == null ? null : server.urlFor(offer.id);
    }

    /** Tells the sender where playback has got to, so its scrub bar means something. */
    public void reportMediaState(int positionMs, int durationMs, boolean playing) {
        MirrorSession session = activeSession;
        if (session == null) {
            return;
        }
        session.sendBack(BplayProtocol.TYPE_MEDIA_STATE, 0, 0, new com.bplay.protocol.Params()
                .put("positionMs", positionMs)
                .put("durationMs", durationMs)
                .put("state", playing ? "playing" : "paused")
                .encode());
    }

    public MediaRelay.Offer mediaOffer() {
        MirrorSession session = activeSession;
        return session == null ? null : session.relay().offer();
    }

    // ---- MirrorSession.Callback -----------------------------------------

    @Override
    public void onSessionVideoSize(MirrorSession session, int width, int height) {
        main.post(() -> {
            MirrorActivity activity = MirrorActivity.current();
            if (activity != null) {
                activity.onVideoSize(width, height);
            }
        });
    }

    @Override
    public void onSessionFirstFrame(MirrorSession session) {
        main.post(() -> {
            MirrorActivity activity = MirrorActivity.current();
            if (activity != null) {
                activity.onFirstFrame();
            }
        });
    }

    @Override
    public void onSessionEnded(MirrorSession session, String reason) {
        synchronized (sessionLock) {
            if (activeSession == session) {
                activeSession = null;
            }
        }
        main.post(() -> {
            MirrorActivity activity = MirrorActivity.current();
            if (activity != null) {
                activity.onSessionEnded(reason);
            }
            MediaPlaybackActivity player = MediaPlaybackActivity.current();
            if (player != null) {
                player.onSessionEnded();
            }
            if (mediaServer != null) {
                mediaServer.stop();
                mediaServer = null;
            }
            goForeground(statusText());
            notifyListeners();
        });
    }

    // ---- UI plumbing -----------------------------------------------------

    public void attachSurface(Surface surface) {
        MirrorSession session = activeSession;
        if (session != null) {
            session.attachSurface(surface);
        }
    }

    public void detachSurface() {
        MirrorSession session = activeSession;
        if (session != null) {
            session.detachSurface();
        }
    }

    public void endCurrentSession(String reason) {
        MirrorSession session = activeSession;
        if (session != null) {
            session.close(reason);
        }
    }

    public Status status() {
        return new Status(state, address, Prefs.requiredPin(this), deviceName(), error,
                activeSession);
    }

    public String certificateFingerprint() {
        return certificates != null ? certificates.fingerprint() : "";
    }

    /** Safe to call before the service is running; the listener is told again once it is. */
    public static void addListener(Context context, StatusListener listener) {
        LISTENERS.add(listener);
        listener.onStatusChanged(snapshot(context));
    }

    public static void removeListener(StatusListener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * The running service's status, or a placeholder for the seconds before it exists. The name
     * and PIN come from storage either way, so the screen has something true to show immediately
     * rather than sitting blank.
     */
    public static Status snapshot(Context context) {
        MirrorService service = instance;
        if (service != null) {
            return service.status();
        }
        return new Status(State.STARTING, null, Prefs.requiredPin(context),
                Prefs.deviceName(context), null, null);
    }

    /** Re-advertises after the user renames the TV or changes the PIN. */
    public void refreshAdvertising() {
        if (state != State.RUNNING) {
            return;
        }
        new Thread(() -> {
            discovery.register(deviceName(), BplayProtocol.DEFAULT_STREAM_PORT,
                    BplayProtocol.DEFAULT_WEB_PORT, !requiredPin().isEmpty());
            if (address != null) {
                beacon.start(address, requiredPin());
            }
            main.post(this::notifyListeners);
        }, "bplay-readvertise").start();
    }

    private void notifyListeners() {
        Status snapshot = status();
        for (StatusListener listener : LISTENERS) {
            try {
                listener.onStatusChanged(snapshot);
            } catch (Exception e) {
                Log.w(TAG, "Listener failed", e);
            }
        }
    }

    private void goForeground(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Screen mirroring",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), flags);

        Notification notification = new androidx.core.app.NotificationCompat.Builder(this,
                CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_cast)
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}
