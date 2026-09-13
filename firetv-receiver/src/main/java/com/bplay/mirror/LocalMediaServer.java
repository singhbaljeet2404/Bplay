package com.bplay.mirror;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import fi.iki.elonen.NanoHTTPD;

/**
 * A plain HTTP server bound to loopback, so the television's own media player can read a file that
 * is still sitting on the sender.
 *
 * <p>Two reasons it is separate from the main server. {@code MediaPlayer} will not accept the
 * self-signed certificate the sender page needs, and it speaks HTTP range requests, which is
 * exactly the shape {@link MediaRelay} wants. Binding to 127.0.0.1 keeps it off the network
 * entirely: nothing outside this device can reach it, so serving it unencrypted costs nothing.
 */
public final class LocalMediaServer extends NanoHTTPD {

    private static final String TAG = "BPlayMedia";

    private final MediaRelay relay;

    public LocalMediaServer(MediaRelay relay) throws IOException {
        // Port 0: the system picks a free one, avoiding a clash with anything else on the stick.
        super("127.0.0.1", 0);
        this.relay = relay;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        Log.i(TAG, "Local media server on 127.0.0.1:" + getListeningPort());
    }

    public String urlFor(String mediaId) {
        return "http://127.0.0.1:" + getListeningPort() + "/media/" + mediaId;
    }

    @Override
    public Response serve(IHTTPSession session) {
        MediaRelay.Offer offer = relay.offer();
        if (offer == null || session.getUri() == null
                || !session.getUri().startsWith("/media/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No media");
        }

        long size = offer.size;
        long first = 0;
        long last = size > 0 ? size - 1 : Long.MAX_VALUE;
        boolean partial = false;

        String range = session.getHeaders().get("range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    String from = spec.substring(0, dash).trim();
                    String to = spec.substring(dash + 1).trim();
                    if (!from.isEmpty()) {
                        first = Long.parseLong(from);
                        if (!to.isEmpty()) {
                            last = Math.min(Long.parseLong(to), last);
                        }
                        partial = true;
                    } else if (!to.isEmpty()) {
                        // "bytes=-500" means the last 500 bytes.
                        long tail = Long.parseLong(to);
                        first = Math.max(0, size - tail);
                        partial = true;
                    }
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Unparseable Range header: " + range);
                }
            }
        }

        if (size > 0 && first >= size) {
            Response response = newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
            response.addHeader("Content-Range", "bytes */" + size);
            return response;
        }

        long length = last - first + 1;
        InputStream stream;
        try {
            stream = relay.open(first, last);
        } catch (IllegalStateException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No media");
        }

        Response response = newFixedLengthResponse(
                partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                offer.mime, stream, length);
        response.addHeader("Accept-Ranges", "bytes");
        if (partial) {
            response.addHeader("Content-Range", String.format(Locale.US, "bytes %d-%d/%d",
                    first, last, size));
        }
        return response;
    }
}
