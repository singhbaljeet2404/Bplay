package com.bplay.sender;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bplay.protocol.BplayProtocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Pick a TV, enter the PIN, start mirroring.
 *
 * <p>Discovery is offered but never required: a manually typed address always works, which matters
 * because the networks where mDNS fails are exactly the ones where a user most needs a way through.
 */
public final class MainActivity extends AppCompatActivity
        implements TvDiscovery.Listener, ScreenCaptureService.StateListener {

    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_BLUETOOTH = 1002;
    private static final int REQUEST_NOTIFICATIONS = 1003;

    private TvDiscovery discovery;
    private final List<TvDiscovery.Tv> devices = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private ListView deviceList;
    private TextView status;
    private EditText manualHost;
    private EditText pin;
    private Spinner quality;
    private CheckBox audio;
    private Button connect;
    private Button stop;
    private Button bluetoothButton;

    private TvDiscovery.Tv selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceList = findViewById(R.id.devices);
        status = findViewById(R.id.status);
        manualHost = findViewById(R.id.manual_host);
        pin = findViewById(R.id.pin);
        quality = findViewById(R.id.quality);
        audio = findViewById(R.id.audio);
        connect = findViewById(R.id.connect);
        stop = findViewById(R.id.stop);
        bluetoothButton = findViewById(R.id.bluetooth);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice,
                new ArrayList<>());
        deviceList.setAdapter(adapter);
        deviceList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        deviceList.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            if (position < devices.size()) {
                selected = devices.get(position);
                manualHost.setText(selected.host);
            }
        });

        quality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.quality_labels)));
        quality.setSelection(1); // 1080p

        if (!AudioCapture.isSupported()) {
            audio.setChecked(false);
            audio.setEnabled(false);
            audio.setText(R.string.audio_unsupported);
        }

        connect.setOnClickListener(v -> requestCapturePermission());
        stop.setOnClickListener(v -> ScreenCaptureService.stop(this));
        bluetoothButton.setOnClickListener(v -> enableBluetoothDiscovery());
        findViewById(R.id.refresh).setOnClickListener(v -> restartDiscovery());

        discovery = new TvDiscovery(this, this);
        ScreenCaptureService.setListener(this);
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        restartDiscovery();
        updateButtons();
    }

    @Override
    protected void onStop() {
        discovery.stopAll();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ScreenCaptureService.setListener(null);
        super.onDestroy();
    }

    private void restartDiscovery() {
        discovery.clear();
        discovery.startNetworkDiscovery();
        if (TvDiscovery.hasScanPermission(this)) {
            discovery.startBluetoothDiscovery();
            bluetoothButton.setVisibility(View.GONE);
        } else {
            bluetoothButton.setVisibility(View.VISIBLE);
        }
        status.setText(R.string.status_searching);
    }

    private void enableBluetoothDiscovery() {
        requestPermissions(TvDiscovery.scanPermissions(), REQUEST_BLUETOOTH);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted && discovery.startBluetoothDiscovery()) {
                bluetoothButton.setVisibility(View.GONE);
            } else {
                Toast.makeText(this, R.string.bluetooth_unavailable, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onDevicesChanged(List<TvDiscovery.Tv> found) {
        runOnUiThread(() -> {
            devices.clear();
            devices.addAll(found);
            adapter.clear();
            for (TvDiscovery.Tv tv : devices) {
                adapter.add(tv.toString());
            }
            adapter.notifyDataSetChanged();
            status.setText(devices.isEmpty()
                    ? getString(R.string.status_searching)
                    : getResources().getQuantityString(R.plurals.status_found,
                            devices.size(), devices.size()));
        });
    }

    private void requestCapturePermission() {
        String host = manualHost.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, R.string.pick_a_tv, Toast.LENGTH_SHORT).show();
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        // This dialog is the only way to obtain screen capture; there is no silent path, by design.
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            status.setText(R.string.status_permission_denied);
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);

        String host = manualHost.getText().toString().trim();
        int port = selected != null && host.equals(selected.host)
                ? selected.port : BplayProtocol.DEFAULT_STREAM_PORT;

        Intent intent = new Intent(this, ScreenCaptureService.class)
                .setAction(ScreenCaptureService.ACTION_START)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(ScreenCaptureService.EXTRA_HOST, host)
                .putExtra(ScreenCaptureService.EXTRA_PORT, port)
                .putExtra(ScreenCaptureService.EXTRA_PIN, pin.getText().toString().trim())
                .putExtra(ScreenCaptureService.EXTRA_WIDTH, metrics.widthPixels)
                .putExtra(ScreenCaptureService.EXTRA_HEIGHT, metrics.heightPixels)
                .putExtra(ScreenCaptureService.EXTRA_DPI, metrics.densityDpi)
                .putExtra(ScreenCaptureService.EXTRA_MAX_HEIGHT, selectedMaxHeight())
                .putExtra(ScreenCaptureService.EXTRA_AUDIO, audio.isChecked())
                .putExtra(ScreenCaptureService.EXTRA_DEVICE_NAME, deviceName());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        status.setText(R.string.status_connecting);
        connect.setEnabled(false);
    }

    private int selectedMaxHeight() {
        int[] values = getResources().getIntArray(R.array.quality_values);
        int index = quality.getSelectedItemPosition();
        return index >= 0 && index < values.length ? values[index] : 1080;
    }

    private String deviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        if (!manufacturer.isEmpty()
                && !model.toLowerCase(java.util.Locale.US)
                .startsWith(manufacturer.toLowerCase(java.util.Locale.US))) {
            return capitalise(manufacturer) + " " + model;
        }
        return capitalise(model);
    }

    private static String capitalise(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    @Override
    public void onMirroringStarted(String tvName) {
        runOnUiThread(() -> {
            status.setText(getString(R.string.status_mirroring, tvName));
            updateButtons();
        });
    }

    @Override
    public void onMirroringStopped(String reason) {
        runOnUiThread(() -> {
            status.setText(reason == null ? getString(R.string.status_stopped) : reason);
            updateButtons();
        });
    }

    private void updateButtons() {
        boolean running = ScreenCaptureService.isRunning();
        connect.setEnabled(!running);
        stop.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }
}
