package com.bplay.mirror;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Full-screen playback. Exists only while a device is actually mirroring.
 *
 * <p>The surface is created here and handed down to the decoder, which is the one ordering
 * constraint in the whole app: video can arrive before the TV has anywhere to put it, so
 * {@link VideoDecoder} waits for this activity rather than the other way round.
 */
public final class MirrorActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static volatile MirrorActivity instance;

    private SurfaceView surfaceView;
    private TextView overlay;
    private int videoWidth;
    private int videoHeight;

    public static MirrorActivity current() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_mirror);

        surfaceView = findViewById(R.id.surface);
        overlay = findViewById(R.id.overlay);
        surfaceView.getHolder().addCallback(this);
        instance = this;
    }

    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        MirrorService service = MirrorService.current();
        if (service != null) {
            service.attachSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        applyAspectRatio();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        MirrorService service = MirrorService.current();
        if (service != null) {
            service.detachSurface();
        }
    }

    public void onVideoSize(int width, int height) {
        this.videoWidth = width;
        this.videoHeight = height;
        applyAspectRatio();
    }

    public void onFirstFrame() {
        overlay.setVisibility(View.GONE);
    }

    public void onSessionEnded(String reason) {
        // Straight back to the home screen; a "disconnected" screen on a TV is just something
        // else the user has to dismiss with the remote.
        android.util.Log.i("BPlayMirror", "Playback finished: " + reason);
        finish();
    }

    /**
     * Letterboxes rather than stretching. A phone mirroring in portrait onto a 16:9 TV is the
     * common case, and a distorted picture looks like a bug.
     */
    private void applyAspectRatio() {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        View root = findViewById(R.id.mirror_root);
        int availableWidth = root.getWidth();
        int availableHeight = root.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) {
            return;
        }

        float videoAspect = (float) videoWidth / videoHeight;
        float screenAspect = (float) availableWidth / availableHeight;

        int targetWidth;
        int targetHeight;
        if (videoAspect > screenAspect) {
            targetWidth = availableWidth;
            targetHeight = Math.round(availableWidth / videoAspect);
        } else {
            targetHeight = availableHeight;
            targetWidth = Math.round(availableHeight * videoAspect);
        }

        ViewGroup.LayoutParams params = surfaceView.getLayoutParams();
        if (params.width == targetWidth && params.height == targetHeight) {
            return;
        }
        if (params instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) params).gravity = android.view.Gravity.CENTER;
        }
        params.width = targetWidth;
        params.height = targetHeight;
        surfaceView.setLayoutParams(params);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) {
            MirrorService service = MirrorService.current();
            if (service != null) {
                service.endCurrentSession("Stopped from the TV remote");
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
