package com.bplay.mirror;

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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The native sender path: a plain TCP stream carrying {@link Packet}s.
 *
 * <p>Used by the Android and iOS sender apps. No TLS and no framing overhead beyond the 14-byte
 * header -- on a LAN that is exactly what you want, and the PIN handshake plus single-session
 * rule are what keep a stranger on the same Wi-Fi off the screen.
 */
public final class TcpStreamServer {

    private static final String TAG = "BPlayTcp";

    /** Long enough to ride out a Wi-Fi roam, short enough to free the screen after a real drop. */
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 8_000;

    /** Cheap defence against a port scanner tying up threads. */
    private static final int MAX_PENDING_CONNECTIONS = 8;

    private final SessionHost host;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger pending = new AtomicInteger();

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public TcpStreamServer(SessionHost host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new java.net.InetSocketAddress(port));
        acceptThread = new Thread(this::acceptLoop, "bplay-tcp-accept");
        acceptThread.start();
        Log.i(TAG, "Listening on tcp/" + port);
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // Closing the socket is what unblocks accept(); failures here are not actionable.
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (pending.get() >= MAX_PENDING_CONNECTIONS) {
                    Log.w(TAG, "Too many connections in flight; dropping " + socket.getInetAddress());
                    closeQuietly(socket);
                    continue;
                }
                pending.incrementAndGet();
                Thread worker = new Thread(() -> {
                    try {
                        handle(socket);
                    } finally {
                        pending.decrementAndGet();
                        closeQuietly(socket);
                    }
                }, "bplay-tcp-session");
                worker.start();
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "Accept failed", e);
                    try {
                        Thread.sleep(250); // avoid a hot loop if the interface went down
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void handle(Socket socket) {
        MirrorSession session = null;
        try {
            socket.setTcpNoDelay(true);   // Nagle would add a frame of latency to every packet
            socket.setKeepAlive(true);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 4096);

            HandshakeCodec.Request request = HandshakeCodec.readRequest(in);
            Log.i(TAG, "Handshake from " + request.deviceName() + " (" + request.platform() + ")");

            if (request.version != BplayProtocol.VERSION) {
                HandshakeCodec.writeResponse(out, BplayProtocol.STATUS_BAD_VERSION,
                        new Params().put(HandshakeCodec.KEY_ERROR,
                                "This TV speaks BPlay v" + BplayProtocol.VERSION));
                return;
            }
            if (!HandshakeCodec.pinMatches(host.requiredPin(),
                    request.params.get(HandshakeCodec.KEY_PIN, ""))) {
                Log.w(TAG, "Rejected " + request.deviceName() + ": wrong PIN");
                HandshakeCodec.writeResponse(out, BplayProtocol.STATUS_BAD_PIN,
                        new Params().put(HandshakeCodec.KEY_ERROR,
                                "Check the PIN shown on the TV"));
                // Slow a brute-force attempt to a crawl without blocking a legitimate retry.
                Thread.sleep(1000);
                return;
            }

            session = host.tryBeginSession(request, MirrorSession.Transport.TCP);
            if (session == null) {
                HandshakeCodec.writeResponse(out, BplayProtocol.STATUS_BUSY,
                        new Params().put(HandshakeCodec.KEY_ERROR,
                                "Another device is already mirroring"));
                return;
            }

            HandshakeCodec.writeResponse(out, BplayProtocol.STATUS_OK, new Params()
                    .put(HandshakeCodec.KEY_NAME, host.deviceName())
                    .put(HandshakeCodec.KEY_MAX_WIDTH, host.maxHeight() * 16 / 9)
                    .put(HandshakeCodec.KEY_MAX_HEIGHT, host.maxHeight())
                    .put(HandshakeCodec.KEY_MAX_BITRATE, host.maxBitrate())
                    .put(BplayProtocol.KEY_SUPPORTS_MEDIA, true));

            socket.setSoTimeout(READ_TIMEOUT_MS);
            final MirrorSession active = session;
            session.setOnClose(() -> closeQuietly(socket));

            // The stream was one-way until native file playback; range requests go back this way.
            final Object writeLock = new Object();
            session.setReverseChannel((type, flags, ptsUs, payload) -> {
                try {
                    synchronized (writeLock) {
                        Packet.write(out, type, flags, ptsUs, payload, 0, payload.length);
                        out.flush();
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Could not reach the sender", e);
                }
            });

            while (running.get() && !active.isClosed()) {
                Packet packet = Packet.read(in);
                active.handlePacket(packet);
            }
        } catch (SocketTimeoutException e) {
            Log.w(TAG, "Sender went quiet; ending the session");
        } catch (IOException e) {
            Log.i(TAG, "Connection closed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "Session failed", e);
        } finally {
            if (session != null) {
                session.close("Connection closed");
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed, or the peer vanished; either way nothing to do.
        }
    }
}
