package com.bplay.protocol.tls;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Turns a generated key and certificate into something that can serve TLS.
 *
 * <p>Deliberately free of Android imports so the whole path -- generate, load into a keystore,
 * complete a real handshake with a real client -- can be exercised on a plain JVM. A broken TLS
 * setup would otherwise only show up as a browser refusing to connect to the television.
 */
public final class TlsServer {

    private TlsServer() {}

    /** Wraps a key pair and its self-signed certificate in an in-memory PKCS12 keystore. */
    public static KeyStore newKeyStore(String alias, PrivateKey privateKey,
                                       X509Certificate certificate, char[] password)
            throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry(alias, privateKey, password, new Certificate[]{certificate});
        return keyStore;
    }

    public static SSLServerSocketFactory serverSocketFactory(KeyStore keyStore, char[] password)
            throws Exception {
        KeyManagerFactory managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context.getServerSocketFactory();
    }
}
