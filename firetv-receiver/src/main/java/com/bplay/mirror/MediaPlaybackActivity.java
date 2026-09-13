package com.bplay.mirror;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Plays a file the sender offered, decoded by the television rather than re-encoded by the phone.
 *
 * <p>The player reads from {@link LocalMediaServer} over loopback, which pulls the bytes from the
 * sender on demand. From the player's point of view this is an ordinary HTTP video, so seeking,
 * buffering and audio all behave the way they normally would.
 */
public final class MediaPlaybackActivity extends AppCompatActivity {

    private static final String TAG = "BPlayMedia";

    private static volatile MediaPlaybackActivity instance;

    private VideoView videoView;
    private ImageView imageView;
    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean isImage;

    public static MediaPlaybackActivity current() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_media);
        videoView = findViewById(R.id.video);
        imageView = findViewById(R.id.image);
        status = findViewById(R.id.media_status);
        instance = this;
        begin();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Stepping to the next photo re-launches this activity, and singleTask means onCreate
        // does not run again -- so the new file is picked up here instead.
        setIntent(intent);
        status.setVisibility(View.VISIBLE);
        try {
            videoView.stopPlayback();
        } catch (Exception ignored) {
            // Nothing was playing.
        }
        begin();
    }

    /** One update a second: enough for a scrub bar, far below anything the sender would notice. */
    private final Runnable reportState = new Runnable() {
        @Override
        public void run() {
            MirrorService service = MirrorService.current();
            if (service != null && !isImage) {
                service.reportMediaState(positionMs(), durationMs(), isPlaying());
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        main.post(reportState);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(reportState);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(reportState);
        if (instance == this) {
            instance = null;
        }
        try {
            videoView.stopPlayback();
        } catch (Exception ignored) {
            // Never started.
        }
        super.onDestroy();
    }

    private void begin() {
        MirrorService service = MirrorService.current();
        if (service == null) {
            finish();
            return;
        }
        String url = service.mediaUrl();
        MediaRelay.Offer offer = service.mediaOffer();
        if (url == null || offer == null) {
            finish();
            return;
        }
        isImage = offer.isImage;
        status.setText(getString(R.string.media_loading, offer.name));

        if (isImage) {
            videoView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            loadImage(url);
            return;
        }

        imageView.setVisibility(View.GONE);
        videoView.setVisibility(View.VISIBLE);
        videoView.setOnPreparedListener(player -> {
            status.setVisibility(View.GONE);
            player.setLooping(false);
            videoView.start();
        });
        videoView.setOnCompletionListener(player -> finish());
        videoView.setOnErrorListener((player, what, extra) -> {
            Log.w(TAG, "Playback failed (" + what + "/" + extra + ")");
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.media_failed);
            main.postDelayed(this::finish, 4000);
            return true;
        });
        videoView.setVideoPath(url);
    }

    /** Images are small enough to pull in one go rather than stream. */
    private void loadImage(String url) {
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                try (InputStream in = connection.getInputStream()) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    // A modern phone photo is far larger than any television panel; decoding it at
                    // full size would risk running the app out of memory for no visible gain.
                    options.inSampleSize = 1;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    bitmap = BitmapFactory.decodeStream(in, null, options);
                }
            } catch (Throwable e) {
                Log.w(TAG, "Could not load the image", e);
            }
            final Bitmap loaded = bitmap;
            main.post(() -> {
                if (loaded != null) {
                    imageView.setImageBitmap(loaded);
                    status.setVisibility(View.GONE);
                } else {
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.media_failed);
                }
            });
        }, "bplay-image").start();
    }

    // ---- driven from the sender ------------------------------------------

    public void applyControl(String action, int positionMs) {
        main.post(() -> {
            if (isImage) {
                if ("stop".equals(action)) {
                    finish();
                }
                return;
            }
            switch (action) {
                case "play":
                    videoView.start();
                    break;
                case "pause":
                    videoView.pause();
                    break;
                case "seek":
                    videoView.seekTo(positionMs);
                    break;
                case "stop":
                    finish();
                    break;
                default:
                    break;
            }
        });
    }

    public int positionMs() {
        try {
            return videoView.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    public int durationMs() {
        try {
            return videoView.getDuration();
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isPlaying() {
        try {
            return videoView.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public void onSessionEnded() {
        main.post(this::finish);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (!isImage) {
                    if (videoView.isPlaying()) {
                        videoView.pause();
                    } else {
                        videoView.start();
                    }
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
