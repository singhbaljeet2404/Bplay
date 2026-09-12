package com.bplay.protocol;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One stream packet: a 14-byte big-endian header followed by its payload.
 *
 * <pre>
 *   offset  size  field
 *   0       1     type    (BplayProtocol.TYPE_*)
 *   1       1     flags   (BplayProtocol.FLAG_*)
 *   2       4     length  (payload bytes, unsigned)
 *   6       8     ptsUs   (presentation timestamp, microseconds)
 *   14      n     payload
 * </pre>
 *
 * <p>Identical on the TCP path and inside a WebSocket binary message, so the receiver's decoder
 * never needs to know which transport a frame arrived on.
 */
public final class Packet {

    public final int type;
    public final int flags;
    public final long ptsUs;
    public final byte[] payload;

    public Packet(int type, int flags, long ptsUs, byte[] payload) {
        this.type = type;
        this.flags = flags;
        this.ptsUs = ptsUs;
        this.payload = payload != null ? payload : new byte[0];
    }

    public boolean isKeyframe() {
        return (flags & BplayProtocol.FLAG_KEYFRAME) != 0;
    }

    // ---- writing ---------------------------------------------------------

    public static void write(OutputStream out, int type, int flags, long ptsUs, byte[] payload,
                             int offset, int length) throws IOException {
        byte[] header = header(type, flags, ptsUs, length);
        // One write per packet where possible: two syscalls on a small frame is measurably
        // worse over Wi-Fi than a single combined buffer.
        if (length <= 64 * 1024) {
            byte[] combined = new byte[BplayProtocol.HEADER_SIZE + length];
            System.arraycopy(header, 0, combined, 0, BplayProtocol.HEADER_SIZE);
            if (length > 0) {
                System.arraycopy(payload, offset, combined, BplayProtocol.HEADER_SIZE, length);
            }
            out.write(combined);
        } else {
            out.write(header);
            out.write(payload, offset, length);
        }
    }

    public static void write(DataOutputStream out, Packet packet) throws IOException {
        write(out, packet.type, packet.flags, packet.ptsUs, packet.payload, 0, packet.payload.length);
    }

    public static byte[] header(int type, int flags, long ptsUs, int length) {
        byte[] h = new byte[BplayProtocol.HEADER_SIZE];
        h[0] = (byte) type;
        h[1] = (byte) flags;
        putInt(h, 2, length);
        putLong(h, 6, ptsUs);
        return h;
    }

    /** Encodes a whole packet into one array -- used by the WebSocket path. */
    public static byte[] toBytes(int type, int flags, long ptsUs, byte[] payload) {
        int length = payload != null ? payload.length : 0;
        byte[] out = new byte[BplayProtocol.HEADER_SIZE + length];
        System.arraycopy(header(type, flags, ptsUs, length), 0, out, 0, BplayProtocol.HEADER_SIZE);
        if (length > 0) {
            System.arraycopy(payload, 0, out, BplayProtocol.HEADER_SIZE, length);
        }
        return out;
    }

    // ---- reading ---------------------------------------------------------

    /** Reads the next packet, blocking until it is complete. */
    public static Packet read(InputStream in) throws IOException {
        byte[] header = new byte[BplayProtocol.HEADER_SIZE];
        readFully(in, header, 0, header.length);
        int type = header[0] & 0xFF;
        int flags = header[1] & 0xFF;
        long length = getInt(header, 2) & 0xFFFFFFFFL;
        long ptsUs = getLong(header, 6);
        if (length > BplayProtocol.MAX_PACKET_SIZE) {
            throw new IOException("Packet too large: " + length + " bytes (type " + type + ")");
        }
        byte[] payload = new byte[(int) length];
        readFully(in, payload, 0, payload.length);
        return new Packet(type, flags, ptsUs, payload);
    }

    /** Parses a packet already sitting in memory (a WebSocket binary message). */
    public static Packet parse(byte[] data) throws IOException {
        if (data.length < BplayProtocol.HEADER_SIZE) {
            throw new IOException("Runt packet: " + data.length + " bytes");
        }
        int type = data[0] & 0xFF;
        int flags = data[1] & 0xFF;
        long length = getInt(data, 2) & 0xFFFFFFFFL;
        long ptsUs = getLong(data, 6);
        if (length != data.length - BplayProtocol.HEADER_SIZE) {
            throw new IOException("Declared length " + length + " != actual "
                    + (data.length - BplayProtocol.HEADER_SIZE));
        }
        byte[] payload = new byte[(int) length];
        System.arraycopy(data, BplayProtocol.HEADER_SIZE, payload, 0, payload.length);
        return new Packet(type, flags, ptsUs, payload);
    }

    public static void readFully(InputStream in, byte[] buf, int offset, int length)
            throws IOException {
        int read = 0;
        while (read < length) {
            int n = in.read(buf, offset + read, length - read);
            if (n < 0) {
                throw new EOFException("Stream closed after " + read + " of " + length + " bytes");
            }
            read += n;
        }
    }

    static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static void putLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >>> (56 - 8 * i));
        }
    }

    static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    static long getLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }
}
