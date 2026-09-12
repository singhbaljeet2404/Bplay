package com.bplay.mirror;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** User-visible settings plus the small amount of state that must survive a reboot. */
public final class Prefs {

    private static final String FILE = "bplay";
    private static final String KEY_DEVICE_NAME = "deviceName";
    private static final String KEY_PIN = "pin";
    private static final String KEY_PIN_ENABLED = "pinEnabled";
    private static final String KEY_KNOWN_ADDRESSES = "knownAddresses";
    private static final String KEY_MAX_HEIGHT = "maxHeight";
    private static final String KEY_MAX_BITRATE = "maxBitrate";

    private Prefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String deviceName(Context context) {
        String stored = prefs(context).getString(KEY_DEVICE_NAME, null);
        if (stored != null && !stored.trim().isEmpty()) {
            return stored;
        }
        // Build.MODEL on a Fire TV Stick reads like "AFTKA"/"AFTMM", which means nothing to a
        // user picking a target on their phone, so fall back to something recognisable.
        String model = Build.MODEL != null ? Build.MODEL : "";
        if (model.toUpperCase(Locale.US).startsWith("AFT") || model.isEmpty()) {
            return "Fire TV";
        }
        return model;
    }

    public static void setDeviceName(Context context, String name) {
        prefs(context).edit().putString(KEY_DEVICE_NAME, name).apply();
    }

    public static String pin(Context context) {
        SharedPreferences p = prefs(context);
        String pin = p.getString(KEY_PIN, null);
        if (pin == null || pin.length() != 4) {
            pin = generatePin();
            p.edit().putString(KEY_PIN, pin).apply();
        }
        return pin;
    }

    public static String regeneratePin(Context context) {
        String pin = generatePin();
        prefs(context).edit().putString(KEY_PIN, pin).apply();
        return pin;
    }

    private static String generatePin() {
        return String.format(Locale.US, "%04d", new Random().nextInt(10000));
    }

    public static boolean pinEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PIN_ENABLED, true);
    }

    public static void setPinEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PIN_ENABLED, enabled).apply();
    }

    /** The PIN a sender must present, or "" when the user has turned the PIN off. */
    public static String requiredPin(Context context) {
        return pinEnabled(context) ? pin(context) : "";
    }

    public static int maxHeight(Context context) {
        return prefs(context).getInt(KEY_MAX_HEIGHT, 1080);
    }

    public static void setMaxHeight(Context context, int height) {
        prefs(context).edit().putInt(KEY_MAX_HEIGHT, height).apply();
    }

    public static int maxBitrate(Context context) {
        return prefs(context).getInt(KEY_MAX_BITRATE, 8_000_000);
    }

    public static void setMaxBitrate(Context context, int bitrate) {
        prefs(context).edit().putInt(KEY_MAX_BITRATE, bitrate).apply();
    }

    /**
     * Every LAN address this TV has ever had. The TLS certificate covers all of them, so a DHCP
     * lease change does not force the user to re-accept the browser warning on every device the
     * next time the old address comes back around.
     */
    public static Set<String> knownAddresses(Context context) {
        return new LinkedHashSet<>(
                prefs(context).getStringSet(KEY_KNOWN_ADDRESSES, Collections.<String>emptySet()));
    }

    public static void setKnownAddresses(Context context, Set<String> addresses) {
        // Keep this bounded: a device on a flaky network could otherwise accumulate addresses
        // until the certificate's SAN list became unreasonable.
        LinkedHashSet<String> bounded = new LinkedHashSet<>(addresses);
        while (bounded.size() > 16) {
            bounded.remove(bounded.iterator().next());
        }
        prefs(context).edit().putStringSet(KEY_KNOWN_ADDRESSES, bounded).apply();
    }
}
