package com.bplay.protocol.tls;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Generates the self-signed TLS certificate the TV serves its sender page over.
 *
 * <p>HTTPS is not optional here: browsers only expose {@code getDisplayMedia()} -- the screen
 * capture API the Windows/macOS/Linux senders are built on -- in a secure context, and a plain
 * {@code http://192.168.x.x} origin is not one. Since no public CA will ever issue for a private
 * LAN address, the certificate is generated on the TV, and the user clicks through the browser
 * warning once per device.
 *
 * <p>Written against raw DER rather than BouncyCastle so the receiver APK stays small and so this
 * can be unit-tested on a plain JVM (and cross-checked with {@code openssl}) instead of only on
 * a Fire TV. The subjectAltName entries matter most: Chrome rejects a certificate with no SAN
 * outright, without even offering the "proceed anyway" escape hatch.
 */
public final class X509SelfSigner {

    private static final String OID_SHA256_RSA = "1.2.840.113549.1.1.11";
    private static final String OID_CN = "2.5.4.3";
    private static final String OID_O = "2.5.4.10";
    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String OID_KEY_USAGE = "2.5.29.15";
    private static final String OID_EXT_KEY_USAGE = "2.5.29.37";
    private static final String OID_SUBJECT_ALT_NAME = "2.5.29.17";
    private static final String OID_SUBJECT_KEY_ID = "2.5.29.14";
    private static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

    private X509SelfSigner() {}

    /** A generated key pair plus its matching certificate. */
    public static final class Result {
        public final KeyPair keyPair;
        public final X509Certificate certificate;

        Result(KeyPair keyPair, X509Certificate certificate) {
            this.keyPair = keyPair;
            this.certificate = certificate;
        }
    }

    /**
     * @param commonName    human-readable name shown in the browser's certificate viewer
     * @param dnsNames      DNS SANs (at minimum "localhost")
     * @param ipAddresses   IPv4/IPv6 literals the TV can be reached at on the LAN
     * @param validityDays  certificate lifetime
     */
    public static Result generate(String commonName, List<String> dnsNames,
                                  List<String> ipAddresses, int validityDays)
            throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair keyPair = gen.generateKeyPair();

        byte[] spki = keyPair.getPublic().getEncoded(); // already a DER SubjectPublicKeyInfo
        byte[] algorithmId = Der.sequence(Der.oid(OID_SHA256_RSA), Der.nul());

        BigInteger serial = new BigInteger(159, new SecureRandom()).add(BigInteger.ONE);

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        // Backdate slightly: a Fire TV that has not yet reached an NTP server after a cold boot
        // can briefly believe it is minutes in the past, which would make the cert "not yet valid".
        cal.add(Calendar.DAY_OF_YEAR, -1);
        Date notBefore = cal.getTime();
        cal.add(Calendar.DAY_OF_YEAR, validityDays + 1);
        Date notAfter = cal.getTime();

        byte[] name = Der.sequence(
                Der.set(Der.sequence(Der.oid(OID_CN), Der.utf8String(commonName))),
                Der.set(Der.sequence(Der.oid(OID_O), Der.utf8String("BPlay Mirror"))));

        byte[] tbs = Der.sequence(
                Der.explicit(0, Der.integer(2)),          // v3
                Der.integer(serial),
                algorithmId,
                name,                                      // issuer == subject (self-signed)
                Der.sequence(Der.utcTime(utc(notBefore)), Der.utcTime(utc(notAfter))),
                name,
                spki,
                Der.explicit(3, extensions(spki, dnsNames, ipAddresses)));

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();

        byte[] certDer = Der.sequence(tbs, algorithmId, Der.bitString(signature));

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate)
                factory.generateCertificate(new ByteArrayInputStream(certDer));
        return new Result(keyPair, certificate);
    }

    private static byte[] extensions(byte[] spki, List<String> dnsNames, List<String> ipAddresses)
            throws GeneralSecurityException {
        List<byte[]> exts = new ArrayList<>();

        // basicConstraints: cA = FALSE (an empty SEQUENCE means the default, FALSE).
        exts.add(extension(OID_BASIC_CONSTRAINTS, true, Der.sequence()));

        // keyUsage: digitalSignature (bit 0) + keyEncipherment (bit 2) -> 0b10100000, 5 unused.
        exts.add(extension(OID_KEY_USAGE, true,
                Der.bitString(new byte[]{(byte) 0xA0}, 5)));

        exts.add(extension(OID_EXT_KEY_USAGE, false, Der.sequence(Der.oid(OID_SERVER_AUTH))));

        List<byte[]> generalNames = new ArrayList<>();
        for (String dns : dnsNames) {
            // dNSName is [2] IA5String, primitive.
            generalNames.add(Der.implicitPrimitive(2,
                    dns.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        }
        for (String ip : ipAddresses) {
            byte[] raw = parseIp(ip);
            if (raw != null) {
                generalNames.add(Der.implicitPrimitive(7, raw)); // iPAddress is [7] OCTET STRING
            }
        }
        if (!generalNames.isEmpty()) {
            exts.add(extension(OID_SUBJECT_ALT_NAME, false,
                    Der.sequence(generalNames.toArray(new byte[0][]))));
        }

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        exts.add(extension(OID_SUBJECT_KEY_ID, false, Der.octetString(sha1.digest(spki))));

        return Der.sequence(exts.toArray(new byte[0][]));
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        byte[] wrapped = Der.octetString(value);
        return critical
                ? Der.sequence(Der.oid(oid), Der.bool(true), wrapped)
                : Der.sequence(Der.oid(oid), wrapped);
    }

    /** Parses a literal address without a DNS lookup; returns null if it is not one. */
    public static byte[] parseIp(String text) {
        if (text == null) {
            return null;
        }
        if (text.indexOf(':') >= 0) {
            return parseIpv6(text);
        }
        String[] parts = text.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private static byte[] parseIpv6(String text) {
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone); // strip "%wlan0" style scope ids
        }
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(text);
            byte[] raw = address.getAddress();
            return raw.length == 16 ? raw : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String utc(Date date) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        cal.setTime(date);
        return String.format(Locale.US, "%02d%02d%02d%02d%02d%02dZ",
                cal.get(Calendar.YEAR) % 100,
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND));
    }
}
