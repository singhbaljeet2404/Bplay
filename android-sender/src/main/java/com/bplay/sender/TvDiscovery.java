package com.bplay.sender;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.bplay.protocol.BplayProtocol;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds Fire TVs running BPlay, by two independent routes.
 *
 * <p>mDNS is the main one and works on any normal home network. Bluetooth LE is the backstop for
 * the case mDNS cannot handle: networks that block multicast between clients, which includes most
 * guest and hotel Wi-Fi. The beacon carries the TV's address and PIN, so the user still ends up
 * connecting over Wi-Fi -- Bluetooth only delivers the details that would otherwise be typed in
 * by hand.
 */
public final class TvDiscovery {

    private static final String TAG = "BPlayDiscovery";

    /** A TV we can offer the user. */
    public static final class Tv {
        public final String name;
        public final String host;
        public final int port;
        public final boolean pinRequired;
        public final String via;

        Tv(String name, String host, int port, boolean pinRequired, String via) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.pinRequired = pinRequired;
            this.via = via;
        }

        String key() {
            return host + ":" + port;
        }

        @Override
        public String toString() {
            return name + "  ·  " + host;
        }
    }

    public interface Listener {
        void onDevicesChanged(List<Tv> devices);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Tv> found = new LinkedHashMap<>();

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;

    /** NsdManager rejects a second resolve while one is in flight, so they are queued. */
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;

    public TvDiscovery(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public List<Tv> devices() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(found.values()));
    }

    // ---- mDNS ------------------------------------------------------------

    public void startNetworkDiscovery() {
        stopNetworkDiscovery();
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) {
                return;
            }
            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                    Log.i(TAG, "Looking for Fire TVs on the network");
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    if (BplayProtocol.SERVICE_TYPE.startsWith(info.getServiceType())
                            || info.getServiceType().contains("bplay")) {
                        enqueueResolve(info);
                    }
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                    // Only the service name is populated here, so match on it.
                    main.post(() -> {
                        String name = info.getServiceName();
                        // An explicit iterator rather than removeIf: that arrived in API 24 and
                        // this app runs back to 22, where it would be a NoSuchMethodError.
                        boolean removed = false;
                        Iterator<Tv> iterator = found.values().iterator();
                        while (iterator.hasNext()) {
                            Tv tv = iterator.next();
                            if (tv.name.equals(name) && "Wi-Fi".equals(tv.via)) {
                                iterator.remove();
                                removed = true;
                            }
                        }
                        if (removed) {
                            listener.onDevicesChanged(devices());
                        }
                    });
                }

                @Override
                public void onDiscoveryStopped(String serviceType) { }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    Log.w(TAG, "Network discovery failed (" + errorCode
                            + "); the address can still be typed in");
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) { }
            };
            nsdManager.discoverServices(BplayProtocol.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.w(TAG, "Could not start network discovery", e);
        }
    }

    private void enqueueResolve(NsdServiceInfo info) {
        synchronized (resolveQueue) {
            resolveQueue.add(info);
            if (resolving) {
                return;
            }
            resolving = true;
        }
        resolveNext();
    }

    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (resolveQueue) {
            next = resolveQueue.poll();
            if (next == null) {
                resolving = false;
                return;
            }
        }
        try {
            nsdManager.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    resolveNext();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    if (info.getHost() != null) {
                        String name = readAttribute(info, "name", info.getServiceName());
                        boolean pinRequired = !"0".equals(readAttribute(info, "pin", "1"));
                        add(new Tv(name, info.getHost().getHostAddress(), info.getPort(),
                                pinRequired, "Wi-Fi"));
                    }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            resolveNext();
        }
    }

    private static String readAttribute(NsdServiceInfo info, String key, String fallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return fallback;
        }
        Map<String, byte[]> attributes = info.getAttributes();
        if (attributes == null) {
            return fallback;
        }
        byte[] value = attributes.get(key);
        if (value == null || value.length == 0) {
            return fallback;
        }
        return new String(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public void stopNetworkDiscovery() {
        try {
            if (nsdManager != null && discoveryListener != null) {
                nsdManager.stopServiceDiscovery(discoveryListener);
            }
        } catch (Exception ignored) {
            // Discovery was not running.
        }
        discoveryListener = null;
    }

    // ---- Bluetooth LE ----------------------------------------------------

    /** The same 16-bit UUID the TV advertises under. */
    private static final ParcelUuid SERVICE_UUID =
            ParcelUuid.fromString("0000b91a-0000-1000-8000-00805f9b34fb");

    public static boolean hasScanPermission(Context context) {
        // ContextCompat rather than Context#checkSelfPermission, which is API 23.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        // Before Android 12 a BLE scan was legally a location operation.
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static String[] scanPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    /** @return false when Bluetooth is off, unsupported, or not permitted */
    // Lint cannot see through hasScanPermission(), which gates every path below; the
    // SecurityException catch is the second line of defence.
    @android.annotation.SuppressLint("MissingPermission")
    public boolean startBluetoothDiscovery() {
        stopBluetoothDiscovery();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                || !hasScanPermission(context)) {
            return false;
        }
        try {
            BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            if (adapter == null || !adapter.isEnabled()) {
                return false;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                return false;
            }
            scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    if (result.getScanRecord() == null) {
                        return;
                    }
                    byte[] data = result.getScanRecord().getServiceData(SERVICE_UUID);
                    Tv tv = decodeBeacon(data);
                    if (tv != null) {
                        main.post(() -> add(tv));
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.w(TAG, "Bluetooth scan failed (" + errorCode + ")");
                }
            };
            scanner.startScan(
                    Collections.singletonList(
                            new ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()),
                    new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build(),
                    scanCallback);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to scan for the pairing beacon");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Could not start the Bluetooth scan", e);
            return false;
        }
    }

    /** Reverses {@code BleBeacon.encode}: 4 address bytes, PIN as u16, port as u16. */
    static Tv decodeBeacon(byte[] data) {
        if (data == null || data.length < 8) {
            return null;
        }
        String host = String.format(Locale.US, "%d.%d.%d.%d",
                data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF, data[3] & 0xFF);
        int pinValue = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        int port = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        if (port <= 0 || port > 65535) {
            return null;
        }
        return new Tv("Fire TV (via Bluetooth)", host, port, pinValue != 0xFFFF, "Bluetooth");
    }

    // Same as above: the hasScanPermission() gate is there, lint just cannot follow it.
    @android.annotation.SuppressLint("MissingPermission")
    public void stopBluetoothDiscovery() {
        try {
            if (scanner != null && scanCallback != null && hasScanPermission(context)) {
                scanner.stopScan(scanCallback);
            }
        } catch (Exception ignored) {
            // Bluetooth may have been switched off.
        }
        scanCallback = null;
    }

    // ---- shared ----------------------------------------------------------

    private void add(Tv tv) {
        main.post(() -> {
            Tv existing = found.get(tv.key());
            // mDNS carries the TV's real name, so never let a Bluetooth beacon overwrite it.
            if (existing != null && "Wi-Fi".equals(existing.via) && !"Wi-Fi".equals(tv.via)) {
                return;
            }
            found.put(tv.key(), tv);
            listener.onDevicesChanged(devices());
        });
    }

    public void clear() {
        found.clear();
        listener.onDevicesChanged(devices());
    }

    public void stopAll() {
        stopNetworkDiscovery();
        stopBluetoothDiscovery();
    }
}
