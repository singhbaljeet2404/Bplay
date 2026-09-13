package com.bplay.mirror;

import android.util.Log;

import com.bplay.protocol.BplayProtocol;
import com.bplay.protocol.MediaChunk;
import com.bplay.protocol.Params;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes a file that lives on the sender readable here, a range at a time.
 *
 * <p>The alternative was uploading the whole file first, which means minutes of waiting before a
 * video starts and needing somewhere to put a few gigabytes on a stick that has little room. So
 * instead the player reads from this, and every read the player performs turns into a range
 * request back over the connection the sender already has open. Playback starts immediately and
 * seeking works, because a seek is just a read at a different offset.
 */
public final class MediaRelay {

    private static final String TAG = "BPlayMedia";

    /** How much is asked for at a time. Large enough to stream, small enough to seek promptly. */
    private static final int WINDOW = 256 * 1024;

    /** Two windows in flight: one being read, one arriving. */
    private static final int MAX_IN_FLIGHT = 2 * WINDOW;

    private static final long READ_TIMEOUT_MS = 20_000;

    /** What the sender has offered. */
    public static final class Offer {
        public final String id;
        public final String name;
        public final String mime;
        public final long size;
        public final boolean isImage;

        Offer(Params params) {
            this.id = params.get("id", "0");
            this.name = params.get("name", "media");
            this.mime = params.get("mime", "application/octet-stream");
            long declared;
            try {
                declared = Long.parseLong(params.get("size", "0"));
            } catch (NumberFormatException e) {
                declared = 0;
            }
            this.size = declared;
            this.isImage = "image".equals(params.get("kind", ""))
                    || this.mime.startsWith("image/");
        }
    }

    public interface Requester {
        void requestRange(int requestId, String mediaId, long offset, int length);
    }

    private final Requester requester;
    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final Map<Integer, RangeStream> streams = new ConcurrentHashMap<>();
    private volatile Offer offer;

    public MediaRelay(Requester requester) {
        this.requester = requester;
    }

    public void setOffer(Params params) {
        Offer incoming = new Offer(params);
        this.offer = incoming;
        Log.i(TAG, "Sender offered " + incoming.name + " (" + incoming.mime + ", "
                + incoming.size + " bytes)");
    }

    public Offer offer() {
        return offer;
    }

    /** Opens a readable window over the offered file. {@code last} is inclusive. */
    public InputStream open(long first, long last) {
        Offer current = offer;
        if (current == null) {
            throw new IllegalStateException("No file has been offered");
        }
        RangeStream stream = new RangeStream(current.id, first, last);
        return stream;
    }

    public void onData(byte[] payload, boolean lastChunk) {
        MediaChunk chunk;
        try {
            chunk = MediaChunk.decode(payload);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Malformed media chunk", e);
            return;
        }
        RangeStream stream = streams.get(chunk.requestId);
        if (stream != null) {
            stream.accept(chunk, lastChunk);
        }
    }

    public void onEnd(Params params) {
        int requestId = params.getInt("req", -1);
        String error = params.get("error", "");
        RangeStream stream = streams.get(requestId);
        if (stream != null) {
            stream.finish(error);
        }
    }

    /** Nothing to play any more; wake every blocked reader instead of leaving it hanging. */
    public void close() {
        offer = null;
        for (RangeStream stream : streams.values()) {
            stream.finish("Session ended");
        }
        streams.clear();
    }

    /**
     * The stream the player actually reads. Arriving chunks are filed by offset rather than by
     * arrival order: a player reading ahead while the user drags the scrub bar produces several
     * outstanding requests, and answers can come back interleaved.
     */
    private final class RangeStream extends InputStream {

        private final String mediaId;
        private final long last;
        private final TreeMap<Long, byte[]> arrived = new TreeMap<>();
        private final Object lock = new Object();

        private long readPosition;
        private long requestedTo;
        private long bufferedBytes;
        private boolean done;
        private String failure;
        private int activeRequestId = -1;

        RangeStream(String mediaId, long first, long last) {
            this.mediaId = mediaId;
            this.last = last;
            this.readPosition = first;
            this.requestedTo = first;
        }

        private void pump() {
            while (requestedTo <= last && bufferedBytes < MAX_IN_FLIGHT) {
                long remaining = last - requestedTo + 1;
                int length = (int) Math.min(WINDOW, remaining);
                int requestId = nextRequestId.getAndIncrement();
                streams.put(requestId, this);
                activeRequestId = requestId;
                long offset = requestedTo;
                requestedTo += length;
                bufferedBytes += length; // reserved until the bytes land
                try {
                    requester.requestRange(requestId, mediaId, offset, length);
                } catch (Exception e) {
                    failure = "Could not ask the sender for more of the file";
                    lock.notifyAll();
                    return;
                }
            }
        }

        void accept(MediaChunk chunk, boolean lastChunk) {
            synchronized (lock) {
                if (chunk.data.length > 0) {
                    arrived.put(chunk.offset, chunk.data);
                }
                if (lastChunk) {
                    streams.remove(chunk.requestId);
                }
                lock.notifyAll();
            }
        }

        void finish(String error) {
            synchronized (lock) {
                if (error != null && !error.isEmpty()) {
                    failure = error;
                }
                done = true;
                lock.notifyAll();
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            synchronized (lock) {
                if (readPosition > last) {
                    return -1;
                }
                long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;
                while (true) {
                    Map.Entry<Long, byte[]> head = arrived.firstEntry();
                    // Only the chunk that continues exactly where we left off can be handed over;
                    // anything further ahead waits so the file is never stitched out of order.
                    if (head != null && head.getKey() <= readPosition) {
                        byte[] data = arrived.remove(head.getKey());
                        int skip = (int) (readPosition - head.getKey());
                        int available = data.length - skip;
                        if (available <= 0) {
                            continue;
                        }
                        int copied = Math.min(length, available);
                        System.arraycopy(data, skip, target, offset, copied);
                        readPosition += copied;
                        bufferedBytes = Math.max(0, bufferedBytes - copied);
                        if (copied < available) {
                            byte[] rest = new byte[available - copied];
                            System.arraycopy(data, skip + copied, rest, 0, rest.length);
                            arrived.put(readPosition, rest);
                        }
                        pump();
                        return copied;
                    }
                    if (failure != null) {
                        throw new IOException(failure);
                    }
                    if (done && arrived.isEmpty()) {
                        return -1;
                    }
                    pump();
                    long waitFor = deadline - System.currentTimeMillis();
                    if (waitFor <= 0) {
                        throw new IOException("The sender stopped supplying the file");
                    }
                    try {
                        lock.wait(Math.min(waitFor, 500));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while reading");
                    }
                }
            }
        }

        @Override
        public int available() {
            synchronized (lock) {
                Map.Entry<Long, byte[]> head = arrived.firstEntry();
                return head != null && head.getKey() <= readPosition ? head.getValue().length : 0;
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                done = true;
                arrived.clear();
                lock.notifyAll();
            }
            if (activeRequestId >= 0) {
                streams.remove(activeRequestId);
            }
        }
    }

    /** Builds the params a receiver sends to ask for a range. */
    public static Params rangeRequest(int requestId, String mediaId, long offset, int length) {
        return new Params()
                .put("req", requestId)
                .put("id", mediaId)
                .put("offset", Long.toString(offset))
                .put("length", length);
    }

    static int packetTypeForOffer() {
        return BplayProtocol.TYPE_MEDIA_OFFER;
    }
}
