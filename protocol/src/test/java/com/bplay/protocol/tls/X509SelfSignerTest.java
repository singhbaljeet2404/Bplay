package com.bplay.protocol.tls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.Test;

public class X509SelfSignerTest {

    private static X509SelfSigner.Result generate() throws Exception {
        return X509SelfSigner.generate("Living Room Fire TV",
                Arrays.asList("localhost", "bplay.local"),
                Arrays.asList("192.168.1.42", "10.0.0.7"), 3650);
    }

    @Test
    public void producesACertificateJavaCanParseAndVerify() throws Exception {
        X509SelfSigner.Result result = generate();
        X509Certificate cert = result.certificate;
        assertNotNull(cert);
        // Self-signed: it must verify under its own public key.
        cert.verify(result.keyPair.getPublic());
        cert.checkValidity();
        assertEquals("SHA256withRSA", cert.getSigAlgName());
        assertEquals(3, cert.getVersion());
        assertTrue(cert.getSerialNumber().signum() > 0);
        assertTrue(cert.getSubjectX500Principal().getName().contains("Living Room Fire TV"));
        assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
    }

    @Test
    public void carriesEveryNameTheBrowserWillBeAskedToMatch() throws Exception {
        Collection<List<?>> sans = generate().certificate.getSubjectAlternativeNames();
        assertNotNull("Chrome refuses a certificate with no SAN at all", sans);
        StringBuilder found = new StringBuilder();
        for (List<?> entry : sans) {
            found.append(entry.get(0)).append(':').append(entry.get(1)).append(' ');
        }
        String all = found.toString();
        assertTrue(all, all.contains("2:localhost"));
        assertTrue(all, all.contains("2:bplay.local"));
        assertTrue(all, all.contains("7:192.168.1.42"));
        assertTrue(all, all.contains("7:10.0.0.7"));
    }

    @Test
    public void setsTheExtensionsARequiredForATlsServerCert() throws Exception {
        X509Certificate cert = generate().certificate;
        boolean[] keyUsage = cert.getKeyUsage();
        assertNotNull(keyUsage);
        assertTrue("digitalSignature", keyUsage[0]);
        assertTrue("keyEncipherment", keyUsage[2]);
        assertTrue(cert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"));
        assertEquals("must not look like a CA", -1, cert.getBasicConstraints());
        assertNotNull(cert.getExtensionValue("2.5.29.14")); // subjectKeyIdentifier
    }

    @Test
    public void skipsHostnamesThatAreNotAddresses() throws Exception {
        // parseIp must not silently turn a hostname into a bogus 4-byte SAN.
        org.junit.Assert.assertNull(X509SelfSigner.parseIp("not.an.ip.address"));
        org.junit.Assert.assertNull(X509SelfSigner.parseIp("999.1.1.1"));
        org.junit.Assert.assertNull(X509SelfSigner.parseIp("1.2.3"));
        assertEquals(4, X509SelfSigner.parseIp("255.0.128.1").length);
        assertEquals(16, X509SelfSigner.parseIp("fe80::1%wlan0").length);
    }

    /** Writes the DER out so the build can additionally check it with openssl. */
    @Test
    public void writesDerForExternalValidation() throws Exception {
        File out = new File(System.getProperty("bplay.certOut",
                System.getProperty("java.io.tmpdir") + "/bplay-test-cert.der"));
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(generate().certificate.getEncoded());
        }
        assertTrue(out.length() > 500);
    }
}
