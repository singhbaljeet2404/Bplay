package com.bplay.mirror;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Works out which address a phone on the same network should be pointed at. */
public final class NetworkUtils {

    private static final String TAG = "BPlayNet";

    private NetworkUtils() {}

    /**
     * LAN addresses of this device, best candidate first.
     *
     * <p>Ordering matters: a Fire TV Cube may be on Ethernet and Wi-Fi at once, and tunnel
     * interfaces from a VPN app will happily show up too. A private IPv4 on a real interface is
     * what a phone can actually reach, so that sorts first.
     */
    public static List<String> localAddresses() {
        List<String> priority = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return priority;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName() == null ? "" : nif.getName();
                boolean tunnel = name.startsWith("tun") || name.startsWith("ppp")
                        || name.startsWith("rmnet");
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }
                    if (!(address instanceof Inet4Address)) {
                        continue; // browsers handle bracketed IPv6 poorly; IPv4 keeps the UI simple
                    }
                    String text = address.getHostAddress();
                    if (text == null) {
                        continue;
                    }
                    if (!tunnel && address.isSiteLocalAddress()) {
                        priority.add(text);
                    } else {
                        secondary.add(text);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate network interfaces", e);
        }
        priority.addAll(secondary);
        return priority;
    }

    /** The single address to print on the TV, or null when there is no network at all. */
    public static String primaryAddress() {
        List<String> all = localAddresses();
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Multicast DNS is filtered out by default on some Fire OS builds unless a multicast lock is
     * held. Without it the TV is still reachable by typing the address, but auto-discovery from
     * the phone app silently finds nothing.
     */
    public static WifiManager.MulticastLock acquireMulticastLock(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return null;
            }
            WifiManager.MulticastLock lock = wifi.createMulticastLock("bplay-mdns");
            lock.setReferenceCounted(false);
            lock.acquire();
            return lock;
        } catch (Exception e) {
            Log.w(TAG, "Multicast lock unavailable; discovery may not work", e);
            return null;
        }
    }

    public static void releaseQuietly(WifiManager.MulticastLock lock) {
        if (lock == null) {
            return;
        }
        try {
            if (lock.isHeld()) {
                lock.release();
            }
        } catch (Exception ignored) {
            // Releasing an already-released lock throws; nothing useful to do about it.
        }
    }
}
