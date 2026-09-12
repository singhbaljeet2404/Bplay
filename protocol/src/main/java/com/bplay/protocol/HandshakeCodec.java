package com.bplay.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The opening exchange of a mirroring session.
 *
 * <pre>
 *   sender   -> receiver : "BPLY" version:u8 len:u16 {@link Params}
 *   receiver -> sender   : status:u8 len:u16 {@link Params}
 * </pre>
 *
 * <p>The receiver answers with a status before any video flows, so a wrong PIN or a busy TV
 * surfaces as a message on the phone rather than a stream that silently goes nowhere.
 */
public final class HandshakeCodec {

    private HandshakeCodec() {}

    // Request keys
    public static final String KEY_PIN = "pin";
    public static final String KEY_NAME = "name";
    public static final String KEY_PLATFORM = "platform";
    public static final String KEY_WIDTH = "width";
    public static final String KEY_HEIGHT = "height";
    public static final String KEY_FPS = "fps";
    public static final String KEY_VIDEO_CODEC = "vcodec";
    public static final String KEY_AUDIO_CODEC = "acodec";

    // Response keys
    public static final String KEY_MAX_WIDTH = "maxWidth";
    public static final String KEY_MAX_HEIGHT = "maxHeight";
    public static final String KEY_MAX_BITRATE = "maxBitrate";
    public static final String KEY_ERROR = "error";

    /** A sender's opening message, already validated. */
    public static final class Request {
        public final int version;
        public final Params params;

        Request(int version, Params params) {
            this.version = version;
            this.params = params;
        }

        public String deviceName() {
            return params.get(KEY_NAME, "Unknown device");
        }

        public String platform() {
            return params.get(KEY_PLATFORM, "unknown");
        }

        public String videoCodec() {
            return params.get(KEY_VIDEO_CODEC, "video/avc");
        }

        /** Empty when the sender is not sending audio at all. */
        public String audioCodec() {
            return params.get(KEY_AUDIO_CODEC, "");
        }
    }

    public static void writeRequest(OutputStream out, Params params) throws IOException {
        byte[] body = params.encode();
        if (body.length > 0xFFFF) {
            throw new IOException("Handshake too large: " + body.length);
        }
        byte[] head = new byte[BplayProtocol.MAGIC.length + 1 + 2];
        System.arraycopy(BplayProtocol.MAGIC, 0, head, 0, BplayProtocol.MAGIC.length);
        head[4] = (byte) BplayProtocol.VERSION;
        head[5] = (byte) (body.length >>> 8);
        head[6] = (byte) body.length;
        out.write(head);
        out.write(body);
        out.flush();
    }

    public static Request readRequest(InputStream in) throws IOException {
        byte[] head = new byte[BplayProtocol.MAGIC.length + 1 + 2];
        Packet.readFully(in, head, 0, head.length);
        for (int i = 0; i < BplayProtocol.MAGIC.length; i++) {
            if (head[i] != BplayProtocol.MAGIC[i]) {
                throw new IOException("Not a BPlay sender (bad magic)");
            }
        }
        int version = head[4] & 0xFF;
        int len = ((head[5] & 0xFF) << 8) | (head[6] & 0xFF);
        byte[] body = new byte[len];
        Packet.readFully(in, body, 0, len);
        return new Request(version, Params.decode(body));
    }

    public static void writeResponse(OutputStream out, int status, Params params)
            throws IOException {
        byte[] body = params != null ? params.encode() : new byte[0];
        if (body.length > 0xFFFF) {
            throw new IOException("Handshake response too large: " + body.length);
        }
        byte[] head = new byte[3];
        head[0] = (byte) status;
        head[1] = (byte) (body.length >>> 8);
        head[2] = (byte) body.length;
        out.write(head);
        out.write(body);
        out.flush();
    }

    /** @return the status code; {@code params} is filled in with the receiver's reply. */
    public static int readResponse(InputStream in, Params[] paramsOut) throws IOException {
        byte[] head = new byte[3];
        Packet.readFully(in, head, 0, head.length);
        int status = head[0] & 0xFF;
        int len = ((head[1] & 0xFF) << 8) | (head[2] & 0xFF);
        byte[] body = new byte[len];
        Packet.readFully(in, body, 0, len);
        if (paramsOut != null && paramsOut.length > 0) {
            paramsOut[0] = Params.decode(body);
        }
        return status;
    }

    /**
     * Compares PINs without leaking how many leading characters matched. A four-digit PIN on an
     * open Wi-Fi network is weak enough already; it should at least not be brute-forceable one
     * character at a time by timing the reply.
     */
    public static boolean pinMatches(String expected, String supplied) {
        if (expected == null || expected.isEmpty()) {
            return true; // PIN disabled by the user
        }
        if (supplied == null) {
            return false;
        }
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ (i < b.length ? b[i] : 0);
        }
        return diff == 0;
    }
}
