package com.bplay.protocol;

/**
 * The payload of a {@link BplayProtocol#TYPE_MEDIA_DATA} packet: which request it answers, where
 * in the file it came from, and the bytes.
 *
 * <pre>
 *   offset  size  field
 *   0       4     request id
 *   4       8     byte offset within the file
 *   12      n     data
 * </pre>
 *
 * <p>Self-describing on purpose. Several ranges can be outstanding at once -- a player commonly
 * reads ahead while the user drags the scrub bar -- and replies may come back interleaved or out
 * of order, so a chunk that only made sense in arrival order would corrupt the file.
 */
public final class MediaChunk {

    /** Bytes before the data itself. */
    public static final int PREFIX_SIZE = 12;

    public final int requestId;
    public final long offset;
    public final byte[] data;

    public MediaChunk(int requestId, long offset, byte[] data) {
        this.requestId = requestId;
        this.offset = offset;
        this.data = data != null ? data : new byte[0];
    }

    public static byte[] encode(int requestId, long offset, byte[] data, int from, int length) {
        byte[] out = new byte[PREFIX_SIZE + length];
        Packet.putInt(out, 0, requestId);
        Packet.putLong(out, 4, offset);
        if (length > 0) {
            System.arraycopy(data, from, out, PREFIX_SIZE, length);
        }
        return out;
    }

    public static MediaChunk decode(byte[] payload) {
        if (payload == null || payload.length < PREFIX_SIZE) {
            throw new IllegalArgumentException("Runt media chunk: "
                    + (payload == null ? 0 : payload.length) + " bytes");
        }
        int requestId = Packet.getInt(payload, 0);
        long offset = Packet.getLong(payload, 4);
        byte[] data = new byte[payload.length - PREFIX_SIZE];
        System.arraycopy(payload, PREFIX_SIZE, data, 0, data.length);
        return new MediaChunk(requestId, offset, data);
    }
}
