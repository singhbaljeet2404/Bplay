package com.bplay.mirror;

import android.content.Context;
import android.util.Log;

import com.bplay.protocol.tls.X509SelfSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Owns the TV's TLS identity: generate once, cache on disk, reuse forever.
 *
 * <p>Caching is the point. The certificate is self-signed, so each browser has to be told once to
 * trust it; if the TV generated a fresh one on every boot the user would face that warning every
 * single time. The cached key is only regenerated when the TV gains an address the existing
 * certificate does not cover.
 */
public final class CertificateStore {

    private static final String TAG = "BPlayCert";
    private static final String KEYSTORE_FILE = "bplay-tls.p12";
    private static final char[] KEYSTORE_PASSWORD = "bplay-local".toCharArray();
    private static final String ALIAS = "bplay";
    private static final int VALIDITY_DAYS = 3650;

    private final Context context;
    private KeyStore keyStore;
    private X509Certificate certificate;

    public CertificateStore(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Loads or creates the certificate. Slow the first time (RSA-2048 keygen on Fire TV hardware
     * takes a couple of seconds), so callers must stay off the main thread.
     */
    public synchronized void prepare() throws Exception {
        Set<String> addresses = new LinkedHashSet<>(Prefs.knownAddresses(context));
        List<String> current = NetworkUtils.localAddresses();
        boolean gainedAddress = addresses.addAll(current);

        File file = new File(context.getFilesDir(), KEYSTORE_FILE);
        if (file.exists() && !gainedAddress) {
            try {
                load(file);
                Log.i(TAG, "Reusing cached certificate for " + addresses);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Cached certificate unusable, regenerating", e);
            }
        }

        Prefs.setKnownAddresses(context, addresses);
        generate(file, new ArrayList<>(addresses));
    }

    private void load(File file) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(file)) {
            ks.load(in, KEYSTORE_PASSWORD);
        }
        Certificate cert = ks.getCertificate(ALIAS);
        if (!(cert instanceof X509Certificate)) {
            throw new IllegalStateException("Keystore has no BPlay certificate");
        }
        ((X509Certificate) cert).checkValidity();
        this.keyStore = ks;
        this.certificate = (X509Certificate) cert;
    }

    private void generate(File file, List<String> addresses) throws Exception {
        Log.i(TAG, "Generating a TLS certificate for " + addresses);
        String name = Prefs.deviceName(context) + " (BPlay)";
        X509SelfSigner.Result result = X509SelfSigner.generate(
                name,
                Arrays.asList("localhost", "bplay.local"),
                addresses,
                VALIDITY_DAYS);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, KEYSTORE_PASSWORD);
        ks.setKeyEntry(ALIAS, result.keyPair.getPrivate(), KEYSTORE_PASSWORD,
                new Certificate[]{result.certificate});

        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            ks.store(out, KEYSTORE_PASSWORD);
        }
        // Rename only after a complete write, so a power cut mid-save cannot leave a truncated
        // keystore that fails to load on every subsequent boot.
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temp.renameTo(file)) {
                Log.w(TAG, "Could not persist the keystore; it will be regenerated next boot");
            }
        }
        this.keyStore = ks;
        this.certificate = result.certificate;
    }

    public synchronized SSLServerSocketFactory serverSocketFactory() throws Exception {
        if (keyStore == null) {
            throw new IllegalStateException("prepare() was not called");
        }
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        return ssl.getServerSocketFactory();
    }

    /**
     * TLS versions to offer, newest first, filtered to what this Fire OS build actually supports.
     * Fire OS 5 is Android 5.1, where TLS 1.2 exists but is not enabled by default on every
     * socket -- naming it explicitly is what keeps current Chrome and Safari willing to connect.
     */
    public static String[] enabledProtocols() {
        List<String> wanted = Arrays.asList("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1");
        List<String> available = new ArrayList<>();
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, null, null);
            List<String> supported =
                    Arrays.asList(ssl.getSupportedSSLParameters().getProtocols());
            for (String protocol : wanted) {
                if (supported.contains(protocol)) {
                    available.add(protocol);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not query supported TLS versions", e);
        }
        if (available.isEmpty()) {
            available.add("TLSv1.2");
        }
        return available.toArray(new String[0]);
    }

    /** SHA-256 fingerprint, shown on the TV so a cautious user can check what they are trusting. */
    public synchronized String fingerprint() {
        if (certificate == null) {
            return "";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02X", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
