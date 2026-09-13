package com.bplay.protocol.tls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Completes a real TLS handshake against the certificate the television generates for itself.
 *
 * <p>This is the part of the system that cannot be checked by reading code. If the generated
 * certificate is malformed, or the key does not load, or the IP address in the SAN is encoded
 * wrongly, the only symptom is a browser that refuses to open the sender page -- on someone
 * else's television, with no logs. So the whole path runs here instead: generate, load into a
 * keystore, serve, and connect with a client doing the same certificate and hostname checks a
 * browser does.
 */
public class TlsHandshakeTest {

    private static final char[] PASSWORD = "test-password".toCharArray();
    private static final String LOOPBACK = "127.0.0.1";

    private static X509SelfSigner.Result certificate;
    private static KeyStore serverKeyStore;

    @BeforeClass
    public static void generate() throws Exception {
        certificate = X509SelfSigner.generate("Living Room Fire TV",
                Arrays.asList("localhost", "bplay.local"),
                Arrays.asList(LOOPBACK, "192.168.1.42"), 3650);
        serverKeyStore = TlsServer.newKeyStore("bplay", certificate.keyPair.getPrivate(),
                certificate.certificate, PASSWORD);
    }

    /** A client that trusts exactly this certificate, as a browser does after the user accepts it. */
    private static SSLContext trustingClientContext() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, PASSWORD);
        trust.setCertificateEntry("tv", certificate.certificate);
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    /** Runs one TLS server exchange and hands back whatever the client sent. */
    private static final class OneShotServer implements AutoCloseable {
        final SSLServerSocket socket;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> received = new AtomicReference<>();
        final AtomicReference<String> negotiatedProtocol = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();

        OneShotServer() throws Exception {
            socket = (SSLServerSocket) TlsServer.serverSocketFactory(serverKeyStore, PASSWORD)
                    .createServerSocket(0, 1, InetAddress.getByName(LOOPBACK));
            Thread thread = new Thread(this::serve, "tls-test-server");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            try (SSLSocket client = (SSLSocket) socket.accept()) {
                client.setSoTimeout(10_000);
                InputStream in = client.getInputStream();
                byte[] buffer = new byte[64];
                int read = in.read(buffer);
                received.set(read > 0 ? new String(buffer, 0, read, "UTF-8") : "");
                negotiatedProtocol.set(client.getSession().getProtocol());
                OutputStream out = client.getOutputStream();
                out.write("pong".getBytes("UTF-8"));
                out.flush();
            } catch (Exception e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    @Test
    public void aTrustingClientCompletesTheHandshakeAndExchangesData() throws Exception {
        try (OneShotServer server = new OneShotServer()) {
            SSLSocket client = (SSLSocket) trustingClientContext().getSocketFactory()
                    .createSocket(LOOPBACK, server.port());
            client.setSoTimeout(10_000);

            // Exactly what a browser does: verify the certificate chain *and* that the address
            // being connected to appears in it.
            SSLParameters parameters = client.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            client.setSSLParameters(parameters);

            client.startHandshake();
            client.getOutputStream().write("ping".getBytes("UTF-8"));
            client.getOutputStream().flush();

            byte[] reply = new byte[4];
            int read = client.getInputStream().read(reply);
            client.close();

            assertTrue("server thread did not finish", server.done.await(10, TimeUnit.SECONDS));
            if (server.failure.get() != null) {
                throw new AssertionError("server failed", server.failure.get());
            }
            assertEquals("ping", server.received.get());
            assertEquals(4, read);
            assertEquals("pong", new String(reply, "UTF-8"));

            String protocol = server.negotiatedProtocol.get();
            assertTrue("Modern browsers require TLS 1.2 or better, negotiated " + protocol,
                    "TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol));
        }
    }

    @Test
    public void hostnameVerificationAcceptsTheIpAddressFromTheSan() throws Exception {
        // The whole reason X509SelfSigner writes iPAddress SANs. Without them a browser reports
        // ERR_CERT_COMMON_NAME_INVALID and, in Chrome, offers no way through at all.
        try (OneShotServer server = new OneShotServer()) {
            SSLSocket client = (SSLSocket) trustingClientContext().getSocketFactory()
                    .createSocket(LOOPBACK, server.port());
            SSLParameters parameters = client.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            client.setSSLParameters(parameters);

            client.startHandshake(); // throws if 127.0.0.1 is not matched by the certificate
            assertEquals(LOOPBACK, client.getInetAddress().getHostAddress());
            client.close();
        }
    }

    @Test
    public void anUntrustingClientIsRejected() throws Exception {
        // Proves the handshake above succeeded because the certificate was trusted, not because
        // verification was switched off somewhere.
        try (OneShotServer server = new OneShotServer()) {
            SSLContext plain = SSLContext.getInstance("TLS");
            plain.init(null, null, null); // default trust store: no self-signed certificate in it
            try (SSLSocket client = (SSLSocket) plain.getSocketFactory()
                    .createSocket(LOOPBACK, server.port())) {
                client.setSoTimeout(10_000);
                client.startHandshake();
                fail("an untrusted self-signed certificate must not validate");
            } catch (SSLHandshakeException expected) {
                assertTrue(expected.getMessage(), expected.getMessage() != null);
            }
        }
    }

    @Test
    public void aCertificateWithoutTheAddressFailsVerification() throws Exception {
        // Guards the cache-invalidation rule in CertificateStore: when the television gains a new
        // address, reusing the old certificate would fail exactly like this.
        X509SelfSigner.Result elsewhere = X509SelfSigner.generate("Elsewhere",
                Collections.singletonList("localhost"),
                Collections.singletonList("10.9.9.9"), 30);
        KeyStore store = TlsServer.newKeyStore("bplay", elsewhere.keyPair.getPrivate(),
                elsewhere.certificate, PASSWORD);

        SSLServerSocket serverSocket = (SSLServerSocket)
                TlsServer.serverSocketFactory(store, PASSWORD)
                        .createServerSocket(0, 1, InetAddress.getByName(LOOPBACK));
        Thread thread = new Thread(() -> {
            try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                accepted.startHandshake();
            } catch (Exception ignored) {
                // The client is expected to reject us; that is the point of the test.
            }
        }, "tls-mismatch-server");
        thread.setDaemon(true);
        thread.start();

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, PASSWORD);
        trust.setCertificateEntry("tv", elsewhere.certificate);
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);

        try (SSLSocket client = (SSLSocket) context.getSocketFactory()
                .createSocket(LOOPBACK, serverSocket.getLocalPort())) {
            client.setSoTimeout(10_000);
            SSLParameters parameters = client.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            client.setSSLParameters(parameters);
            client.startHandshake();
            fail("127.0.0.1 is not in that certificate, so verification must fail");
        } catch (SSLHandshakeException expected) {
            assertTrue(true);
        } finally {
            serverSocket.close();
        }
    }
}
