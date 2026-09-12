package com.bplay.mirror;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import com.bplay.protocol.BplayProtocol;

/**
 * Advertises the TV over mDNS so the phone app can list it by name instead of asking the user to
 * read an IP address off the screen and type it on a touch keyboard.
 *
 * <p>Best-effort throughout. Plenty of home routers and nearly every corporate or hotel network
 * block multicast between clients, so the manual-address path in the sender app is the guarantee
 * and this is the convenience.
 */
public final class Discovery {

    private static final String TAG = "BPlayDiscovery";

    private final Context context;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener listener;
    private WifiManager.MulticastLock multicastLock;

    public Discovery(Context context) {
        this.context = context.getApplicationContext();
    }

    public void register(String deviceName, int streamPort, int webPort, boolean pinRequired) {
        unregister();
        multicastLock = NetworkUtils.acquireMulticastLock(context);
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) {
                Log.w(TAG, "No NSD service on this device; discovery disabled");
                return;
            }
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(deviceName);
            info.setServiceType(BplayProtocol.SERVICE_TYPE);
            info.setPort(streamPort);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                info.setAttribute("v", Integer.toString(BplayProtocol.VERSION));
                info.setAttribute("web", Integer.toString(webPort));
                info.setAttribute("pin", pinRequired ? "1" : "0");
                info.setAttribute("name", deviceName);
            }

            listener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo info) {
                    Log.i(TAG, "Advertising as \"" + info.getServiceName() + "\"");
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "mDNS registration failed (" + errorCode
                            + "); senders can still connect by address");
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo info) {
                    Log.i(TAG, "Stopped advertising");
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "mDNS unregistration failed (" + errorCode + ")");
                }
            };
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Exception e) {
            Log.w(TAG, "Could not start mDNS advertising", e);
        }
    }

    public void unregister() {
        try {
            if (nsdManager != null && listener != null) {
                nsdManager.unregisterService(listener);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unregister failed", e);
        } finally {
            listener = null;
            NetworkUtils.releaseQuietly(multicastLock);
            multicastLock = null;
        }
    }
}
