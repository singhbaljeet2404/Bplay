package com.bplay.mirror;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Broadcasts the TV's address and PIN over Bluetooth LE so the phone app can connect without the
 * user reading anything off the screen.
 *
 * <p><strong>This carries pairing data only, never video.</strong> Bluetooth Classic tops out
 * around 1-2 Mbit/s of real throughput and BLE is far below that, while even a conservative 720p30
 * H.264 mirror needs 2-4 Mbit/s sustained. Video goes over Wi-Fi; Bluetooth's useful job here is
 * removing the "type 192.168.1.x into your phone" step. See docs/ARCHITECTURE.md.
 *
 * <p>Entirely optional: several Fire TV models expose no BLE advertiser at all, so every failure
 * path here logs and moves on.
 */
public final class BleBeacon {

    private static final String TAG = "BPlayBle";

    /** 16-bit UUID under the Bluetooth base; the sender app scans for exactly this. */
    public static final ParcelUuid SERVICE_UUID =
            ParcelUuid.fromString("0000b91a-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback callback;

    public BleBeacon(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * @param ipv4 the address to publish, e.g. {@code 192.168.1.42}
     * @param pin  the four-digit PIN, or "" when PINs are disabled
     */
    // Lint cannot see through hasAdvertisePermission(), which is checked below before the
    // advertiser is touched; the SecurityException catch is the second line of defence.
    @android.annotation.SuppressLint("MissingPermission")
    public void start(String ipv4, String pin) {
        stop();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        byte[] payload = encode(ipv4, pin);
        if (payload == null) {
            return;
        }
        try {
            BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            if (adapter == null || !adapter.isEnabled()) {
                Log.i(TAG, "Bluetooth is off; skipping the pairing beacon");
                return;
            }
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                Log.i(TAG, "This device cannot advertise over BLE; Wi-Fi discovery only");
                return;
            }
            if (!hasAdvertisePermission()) {
                Log.i(TAG, "No Bluetooth advertise permission; Wi-Fi discovery only");
                return;
            }

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(false)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false) // a long name overflows the 31-byte packet
                    .addServiceUuid(SERVICE_UUID)
                    .addServiceData(SERVICE_UUID, payload)
                    .build();

            callback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.i(TAG, "Pairing beacon running");
                }

                @Override
                public void onStartFailure(int errorCode) {
                    Log.i(TAG, "Pairing beacon unavailable (" + errorCode + ")");
                }
            };
            advertiser.startAdvertising(settings, data, callback);
        } catch (SecurityException e) {
            Log.i(TAG, "No Bluetooth advertise permission; Wi-Fi discovery only");
        } catch (Exception e) {
            Log.i(TAG, "Could not start the pairing beacon", e);
        }
    }

    /** BLUETOOTH_ADVERTISE became a runtime permission in Android 12. */
    private boolean hasAdvertisePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.BLUETOOTH_ADVERTISE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    // Same as above: the hasAdvertisePermission() gate is there, lint just cannot follow it.
    @android.annotation.SuppressLint("MissingPermission")
    public void stop() {
        try {
            if (advertiser != null && callback != null && hasAdvertisePermission()) {
                advertiser.stopAdvertising(callback);
            }
        } catch (Exception ignored) {
            // Bluetooth may have been switched off underneath us.
        } finally {
            advertiser = null;
            callback = null;
        }
    }

    /**
     * Packs into the 31-byte advertising budget: 4 address bytes, 2 PIN bytes, 2 port bytes.
     *
     * <pre>[0..3] IPv4  [4..5] PIN as a big-endian u16 (0xFFFF = no PIN)  [6..7] stream port</pre>
     */
    static byte[] encode(String ipv4, String pin) {
        byte[] address = com.bplay.protocol.tls.X509SelfSigner.parseIp(ipv4);
        if (address == null || address.length != 4) {
            return null; // IPv6-only networks fall back to mDNS and manual entry
        }
        int pinValue = 0xFFFF;
        if (pin != null && pin.length() == 4) {
            try {
                pinValue = Integer.parseInt(pin);
            } catch (NumberFormatException ignored) {
                pinValue = 0xFFFF;
            }
        }
        int port = com.bplay.protocol.BplayProtocol.DEFAULT_STREAM_PORT;
        return new byte[]{
                address[0], address[1], address[2], address[3],
                (byte) (pinValue >>> 8), (byte) pinValue,
                (byte) (port >>> 8), (byte) port
        };
    }
}
