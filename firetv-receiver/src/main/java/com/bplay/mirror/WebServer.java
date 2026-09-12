package com.bplay.mirror;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.bplay.protocol.BplayProtocol;
import com.bplay.protocol.HandshakeCodec;
import com.bplay.protocol.Packet;
import com.bplay.protocol.Params;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * Serves the browser sender and carries its video.
 *
 * <p>This is what makes Windows, macOS and Linux work with nothing installed: the user opens
 * {@code https://<tv-ip>:8443}, picks a window or screen, and the page encodes H.264 with
 * WebCodecs and pushes it down a WebSocket as the very same packets the native apps send.
 *
 * <p>HTTPS is mandatory rather than a nicety -- {@code getDisplayMedia()} does not exist outside
 * a secure context, so a plain HTTP page could not capture the screen at all.
 */
public final class WebServer extends NanoWSD {

    private static final String TAG = "BPlayWeb";
    private static final String ASSET_ROOT = "web";

    private final Context context;
    private final SessionHost host;

    public WebServer(Context context, SessionHost host, int port) {
        super(port);
        this.context = context.getApplicationContext();
        this.host = host;
    }

    // ---- plain HTTP ------------------------------------------------------

    @Override
    protected Response serveHttp(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null || uri.isEmpty() || "/".equals(uri)) {
            uri = "/index.html";
        }
        if ("/info".equals(uri)) {
            return noStore(newFixedLengthResponse(Response.Status.OK, "application/json",
                    String.format(Locale.US,
                            "{\"name\":%s,\"version\":\"%s\",\"protocol\":%d,\"pinRequired\":%s}",
                            jsonString(host.deviceName()),
                            BuildConfig.VERSION_NAME,
                            BplayProtocol.VERSION,
                            host.requiredPin().isEmpty() ? "false" : "true")));
        }
        if ("/health".equals(uri)) {
            return noStore(newFixedLengthResponse("ok"));
        }

        byte[] body = readAsset(uri);
        if (body == null) {
            return noStore(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain",
                    "Not found"));
        }
        Response response = newFixedLengthResponse(Response.Status.OK, mimeFor(uri),
                new ByteArrayInputStream(body), body.length);
        return noStore(response);
    }

    private Response noStore(Response response) {
        // An updated APK must not be shadowed by a page the browser cached from the old one.
        response.addHeader("Cache-Control", "no-store, must-revalidate");
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }

    private byte[] readAsset(String uri) {
        // Reject traversal before touching the AssetManager.
        if (uri.contains("..") || uri.contains("//")) {
            return null;
        }
        String path = ASSET_ROOT + (uri.startsWith("/") ? uri : "/" + uri);
        AssetManager assets = context.getAssets();
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static String mimeFor(String uri) {
        String lower = uri.toLowerCase(Locale.US);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    // ---- WebSocket -------------------------------------------------------

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new StreamSocket(handshake);
    }

    /**
     * One browser's mirroring session. Message framing is identical to the TCP path, so the
     * browser's first binary message is a normal BPlay handshake and every message after it is a
     * normal packet.
     */
    private final class StreamSocket extends WebSocket {

        private MirrorSession session;
        private boolean handshakeDone;

        StreamSocket(IHTTPSession handshakeRequest) {
            super(handshakeRequest);
        }

        @Override
        protected void onOpen() {
            Log.i(TAG, "Browser connected, awaiting handshake");
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason,
                               boolean initiatedByRemote) {
            if (session != null) {
                session.close("Browser disconnected");
                session = null;
            }
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            try {
                byte[] payload = message.getBinaryPayload();
                if (payload == null || payload.length == 0) {
                    return;
                }
                if (!handshakeDone) {
                    doHandshake(payload);
                    return;
                }
                if (session != null) {
                    session.handlePacket(Packet.parse(payload));
                }
            } catch (Exception e) {
                Log.w(TAG, "Bad message from browser", e);
                closeQuietly("Protocol error");
            }
        }

        private void doHandshake(byte[] payload) throws IOException {
            HandshakeCodec.Request request =
                    HandshakeCodec.readRequest(new ByteArrayInputStream(payload));
            handshakeDone = true;

            if (request.version != BplayProtocol.VERSION) {
                respond(BplayProtocol.STATUS_BAD_VERSION, "Reload the page to update");
                closeQuietly("Version mismatch");
                return;
            }
            if (!HandshakeCodec.pinMatches(host.requiredPin(),
                    request.params.get(HandshakeCodec.KEY_PIN, ""))) {
                Log.w(TAG, "Browser supplied the wrong PIN");
                respond(BplayProtocol.STATUS_BAD_PIN, "Check the PIN shown on the TV");
                closeQuietly("Wrong PIN");
                return;
            }

            session = host.tryBeginSession(request, MirrorSession.Transport.WEBSOCKET);
            if (session == null) {
                respond(BplayProtocol.STATUS_BUSY, "Another device is already mirroring");
                closeQuietly("Busy");
                return;
            }
            session.setOnClose(() -> closeQuietly("Session ended"));

            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            HandshakeCodec.writeResponse(reply, BplayProtocol.STATUS_OK, new Params()
                    .put(HandshakeCodec.KEY_NAME, host.deviceName())
                    .put(HandshakeCodec.KEY_MAX_WIDTH, host.maxHeight() * 16 / 9)
                    .put(HandshakeCodec.KEY_MAX_HEIGHT, host.maxHeight())
                    .put(HandshakeCodec.KEY_MAX_BITRATE, host.maxBitrate()));
            send(reply.toByteArray());
        }

        private void respond(int status, String error) throws IOException {
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            HandshakeCodec.writeResponse(reply, status,
                    new Params().put(HandshakeCodec.KEY_ERROR, error));
            send(reply.toByteArray());
        }

        @Override
        protected void onPong(WebSocketFrame pong) {
            // Presence is the whole message.
        }

        @Override
        protected void onException(IOException exception) {
            Log.i(TAG, "Browser socket closed: " + exception.getMessage());
            if (session != null) {
                session.close("Browser disconnected");
                session = null;
            }
        }

        private void closeQuietly(String reason) {
            try {
                close(WebSocketFrame.CloseCode.NormalClosure, reason, false);
            } catch (IOException ignored) {
                // The socket is already gone; nothing to clean up.
            }
        }
    }

    /**
     * NanoHTTPD's default read timeout is five seconds, which is fine for a page load and fatal
     * for a WebSocket: an idle socket would be torn down mid-session. Senders ping every two
     * seconds, so a minute of silence genuinely means the device is gone.
     */
    public void startServer() throws IOException {
        start(60_000, true);
    }
}
