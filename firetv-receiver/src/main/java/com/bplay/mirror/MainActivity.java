package com.bplay.mirror;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * The TV's home screen: everything a user needs in order to connect something to it.
 *
 * <p>Written for a remote control and a sofa. Nothing here needs more than the D-pad, the text is
 * sized to be read from across a room, and the address and PIN are the two largest things on it
 * because they are what somebody is squinting at while holding a phone.
 */
public final class MainActivity extends AppCompatActivity implements MirrorService.StatusListener {

    private TextView statusView;
    private TextView addressView;
    private TextView pinView;
    private TextView nameView;
    private TextView hintView;
    private ImageView qrView;
    private Button pinToggleButton;
    private Button qualityButton;

    private String qrEncodedFor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        addressView = findViewById(R.id.address);
        pinView = findViewById(R.id.pin);
        nameView = findViewById(R.id.device_name);
        hintView = findViewById(R.id.hint);
        qrView = findViewById(R.id.qr);
        pinToggleButton = findViewById(R.id.button_pin_toggle);
        qualityButton = findViewById(R.id.button_quality);

        findViewById(R.id.button_rename).setOnClickListener(v -> promptForName());
        findViewById(R.id.button_new_pin).setOnClickListener(v -> {
            Prefs.regeneratePin(this);
            refreshFromService();
        });
        pinToggleButton.setOnClickListener(v -> {
            Prefs.setPinEnabled(this, !Prefs.pinEnabled(this));
            MirrorService service = MirrorService.current();
            if (service != null) {
                service.refreshAdvertising();
            }
            refreshFromService();
        });
        qualityButton.setOnClickListener(v -> cycleQuality());
        findViewById(R.id.button_help).setOnClickListener(v -> showHelp());

        requestNotificationPermissionIfNeeded();
        MirrorService.startServers(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFromService();
        MirrorService service = MirrorService.current();
        if (service != null) {
            service.addListener(this);
        }
    }

    @Override
    protected void onPause() {
        MirrorService service = MirrorService.current();
        if (service != null) {
            service.removeListener(this);
        }
        super.onPause();
    }

    private void refreshFromService() {
        MirrorService service = MirrorService.current();
        if (service == null) {
            // The service is still starting; its first callback will fill the screen in.
            statusView.setText(R.string.status_starting);
            return;
        }
        onStatusChanged(service.status());
    }

    @Override
    public void onStatusChanged(MirrorService.Status status) {
        runOnUiThread(() -> render(status));
    }

    private void render(MirrorService.Status status) {
        nameView.setText(status.deviceName);

        switch (status.state) {
            case RUNNING:
                if (status.isMirroring()) {
                    statusView.setText(getString(R.string.status_mirroring,
                            status.session.deviceName));
                } else {
                    statusView.setText(R.string.status_ready);
                }
                break;
            case STARTING:
                statusView.setText(R.string.status_starting);
                break;
            case ERROR:
                statusView.setText(getString(R.string.status_error, status.error));
                break;
            default:
                statusView.setText(R.string.status_stopped);
                break;
        }

        String url = status.senderUrl();
        addressView.setText(url != null ? url : getString(R.string.address_unknown));

        boolean pinOn = Prefs.pinEnabled(this);
        pinView.setText(pinOn ? status.pin : getString(R.string.pin_off));
        pinToggleButton.setText(pinOn ? R.string.button_pin_disable : R.string.button_pin_enable);
        qualityButton.setText(getString(R.string.button_quality,
                Prefs.maxHeight(this), Prefs.maxBitrate(this) / 1_000_000));
        hintView.setText(pinOn ? R.string.hint_with_pin : R.string.hint_without_pin);

        renderQr(url, pinOn ? status.pin : null);
    }

    private void renderQr(String url, String pin) {
        if (url == null) {
            qrView.setVisibility(View.INVISIBLE);
            qrEncodedFor = null;
            return;
        }
        // Carry the PIN in the URL so scanning the code is genuinely one step -- the page reads
        // it out of the query string and pre-fills the field.
        String payload = pin != null ? url + "?pin=" + pin : url;
        if (payload.equals(qrEncodedFor)) {
            return; // avoid re-encoding on every status tick
        }
        Bitmap bitmap = QrCodes.encode(payload, 480);
        if (bitmap != null) {
            qrView.setImageBitmap(bitmap);
            qrView.setVisibility(View.VISIBLE);
            qrEncodedFor = payload;
        } else {
            qrView.setVisibility(View.INVISIBLE);
        }
    }

    private void promptForName() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(Prefs.deviceName(this));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_rename_title)
                .setMessage(R.string.dialog_rename_message)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        Prefs.setDeviceName(this, name);
                        MirrorService service = MirrorService.current();
                        if (service != null) {
                            service.refreshAdvertising();
                        }
                        refreshFromService();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Quality is one button rather than a settings screen. The presets pair a resolution with a
     * bitrate that actually sustains it over Wi-Fi, so the user picks one axis instead of two.
     */
    private void cycleQuality() {
        int height = Prefs.maxHeight(this);
        if (height >= 1080) {
            Prefs.setMaxHeight(this, 720);
            Prefs.setMaxBitrate(this, 4_000_000);
        } else if (height >= 720) {
            Prefs.setMaxHeight(this, 480);
            Prefs.setMaxBitrate(this, 2_000_000);
        } else {
            Prefs.setMaxHeight(this, 1080);
            Prefs.setMaxBitrate(this, 8_000_000);
        }
        refreshFromService();
    }

    private void showHelp() {
        MirrorService service = MirrorService.current();
        String fingerprint = service != null ? service.certificateFingerprint() : "";
        String shortFingerprint = fingerprint.length() > 23
                ? fingerprint.substring(0, 23) + "..." : fingerprint;

        new AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(getString(R.string.help_body, shortFingerprint))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void requestNotificationPermissionIfNeeded() {
        // A foreground service on Android 13+ needs this or its notification is silently dropped,
        // which on some builds takes the service down with it.
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }
}
