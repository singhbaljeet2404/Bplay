package com.bplay.sender;

import android.util.Log;

import com.bplay.protocol.BplayProtocol;
import com.bplay.protocol.HandshakeCodec;
import com.bplay.protocol.Packet;
import com.bplay.protocol.Params;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The sender's half of a mirroring session: connect, handshake, then write packets.
 *
 * <p>All writes funnel through one lock because video and audio are encoded on separate threads
 * and a half-written packet would desynchronise the stream permanently.
 */
public final class SenderConnection {

    private static final String TAG = "BPlaySender";
    private static final int CONNECT_TIMEOUT_MS = 6000;

    /** Thrown when the TV answers but refuses: wrong PIN, busy, or version mismatch. */
    public static final class RejectedException extends IOException {
        public final int status;

        RejectedException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Object writeLock = new Object();
    private Socket socket;
    private OutputStream out;

    private String tvName = "Fire TV";
    private int maxHeight = 1080;
    private int maxBitrate = 8_000_000;

    public String tvName() {
        return tvName;
    }

    public int maxHeight() {
        return maxHeight;
    }

    public int maxBitrate() {
        return maxBitrate;
    }

    public void connect(String host, int port, String pin, String deviceName,
                        int width, int height, boolean withAudio) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true); // every frame matters; Nagle would batch them into extra latency
        s.setKeepAlive(true);
        s.setSoTimeout(CONNECT_TIMEOUT_MS);

        InputStream in = new BufferedInputStream(s.getInputStream(), 4096);
        OutputStream rawOut = new BufferedOutputStream(s.getOutputStream(), 128 * 1024);

        Params request = new Params()
                .put(HandshakeCodec.KEY_PIN, pin == null ? "" : pin)
                .put(HandshakeCodec.KEY_NAME, deviceName)
                .put(HandshakeCodec.KEY_PLATFORM, "android")
                .put(HandshakeCodec.KEY_WIDTH, width)
                .put(HandshakeCodec.KEY_HEIGHT, height)
                .put(HandshakeCodec.KEY_FPS, 30)
                .put(HandshakeCodec.KEY_VIDEO_CODEC, "video/avc");
        if (withAudio) {
            request.put(HandshakeCodec.KEY_AUDIO_CODEC, "audio/mp4a-latm")
                    .put("asamplerate", 44100)
                    .put("achannels", 2);
        }
        HandshakeCodec.writeRequest(rawOut, request);

        Params[] response = new Params[1];
        int status = HandshakeCodec.readResponse(in, response);
        if (status != BplayProtocol.STATUS_OK) {
            String error = response[0] != null
                    ? response[0].get(HandshakeCodec.KEY_ERROR, "") : "";
            closeQuietly(s);
            throw new RejectedException(status,
                    error.isEmpty() ? BplayProtocol.statusMessage(status) : error);
        }

        if (response[0] != null) {
            tvName = response[0].get(HandshakeCodec.KEY_NAME, tvName);
            maxHeight = response[0].getInt(HandshakeCodec.KEY_MAX_HEIGHT, maxHeight);
            maxBitrate = response[0].getInt(HandshakeCodec.KEY_MAX_BITRATE, maxBitrate);
        }
        // No further reads: the TV says nothing after the handshake, so a read timeout on an
        // idle socket would be a false alarm.
        s.setSoTimeout(0);

        this.socket = s;
        this.out = rawOut;
        Log.i(TAG, "Connected to " + tvName + " (max " + maxHeight + "p / "
                + maxBitrate / 1_000_000 + " Mbps)");
    }

    public void send(int type, int flags, long ptsUs, byte[] payload, int offset, int length)
            throws IOException {
        OutputStream stream = out;
        if (stream == null) {
            throw new IOException("Not connected");
        }
        synchronized (writeLock) {
            Packet.write(stream, type, flags, ptsUs, payload, offset, length);
            stream.flush();
        }
    }

    public void send(int type, byte[] payload) throws IOException {
        send(type, 0, 0, payload, 0, payload.length);
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    public void close() {
        try {
            if (out != null) {
                send(BplayProtocol.TYPE_BYE, new byte[0]);
            }
        } catch (IOException ignored) {
            // The socket is going away regardless.
        }
        closeQuietly(socket);
        socket = null;
        out = null;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }
}
