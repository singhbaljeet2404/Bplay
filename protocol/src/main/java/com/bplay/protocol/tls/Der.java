package com.bplay.protocol.tls;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/** Minimal DER encoder -- only the handful of types an X.509 server certificate needs. */
final class Der {

    static final int TAG_BOOLEAN = 0x01;
    static final int TAG_INTEGER = 0x02;
    static final int TAG_BIT_STRING = 0x03;
    static final int TAG_OCTET_STRING = 0x04;
    static final int TAG_NULL = 0x05;
    static final int TAG_OID = 0x06;
    static final int TAG_UTF8_STRING = 0x0C;
    static final int TAG_SEQUENCE = 0x30;
    static final int TAG_SET = 0x31;
    static final int TAG_UTC_TIME = 0x17;

    private Der() {}

    /** Wraps {@code content} in a tag-length-value triple. */
    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    /** An explicitly tagged, constructed context element, e.g. {@code [0] EXPLICIT}. */
    static byte[] explicit(int contextTag, byte[] content) {
        return tlv(0xA0 | contextTag, content);
    }

    /** A primitive context element, e.g. a SAN {@code dNSName [2]}. */
    static byte[] implicitPrimitive(int contextTag, byte[] content) {
        return tlv(0x80 | contextTag, content);
    }

    static byte[] sequence(byte[]... parts) {
        return tlv(TAG_SEQUENCE, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(TAG_SET, concat(parts));
    }

    static byte[] integer(BigInteger value) {
        // BigInteger.toByteArray() is already two's-complement big-endian with the leading zero
        // byte DER wants whenever the high bit would otherwise read as negative.
        return tlv(TAG_INTEGER, value.toByteArray());
    }

    static byte[] integer(int value) {
        return integer(BigInteger.valueOf(value));
    }

    static byte[] bool(boolean value) {
        return tlv(TAG_BOOLEAN, new byte[]{(byte) (value ? 0xFF : 0x00)});
    }

    static byte[] nul() {
        return tlv(TAG_NULL, new byte[0]);
    }

    static byte[] octetString(byte[] value) {
        return tlv(TAG_OCTET_STRING, value);
    }

    static byte[] utf8String(String value) {
        return tlv(TAG_UTF8_STRING, value.getBytes(StandardCharsets.UTF_8));
    }

    /** BIT STRING with no unused trailing bits. */
    static byte[] bitString(byte[] value) {
        byte[] withPad = new byte[value.length + 1];
        withPad[0] = 0; // unused bits
        System.arraycopy(value, 0, withPad, 1, value.length);
        return tlv(TAG_BIT_STRING, withPad);
    }

    static byte[] bitString(byte[] value, int unusedBits) {
        byte[] withPad = new byte[value.length + 1];
        withPad[0] = (byte) unusedBits;
        System.arraycopy(value, 0, withPad, 1, value.length);
        return tlv(TAG_BIT_STRING, withPad);
    }

    static byte[] utcTime(String yyMMddHHmmssZ) {
        return tlv(TAG_UTC_TIME, yyMMddHHmmssZ.getBytes(StandardCharsets.US_ASCII));
    }

    /** Encodes a dotted OID such as {@code 1.2.840.113549.1.1.11}. */
    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Bad OID: " + dotted);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            writeBase128(body, Long.parseLong(parts[i]));
        }
        return tlv(TAG_OID, body.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream out, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Negative OID arc");
        }
        byte[] groups = new byte[10];
        int count = 0;
        do {
            groups[count++] = (byte) (value & 0x7F);
            value >>>= 7;
        } while (value != 0);
        for (int i = count - 1; i > 0; i--) {
            out.write(groups[i] | 0x80);
        }
        out.write(groups[0]);
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        int bytes = 1;
        while ((length >>> (8 * bytes)) != 0) {
            bytes++;
        }
        out.write(0x80 | bytes);
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((length >>> (8 * i)) & 0xFF);
        }
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
