package com.bplay.protocol;

/**
 * Wire constants for the BPlay mirroring protocol ("BMP/1").
 *
 * <p>A session is a single TCP connection (or a single WebSocket, which carries the exact same
 * packets as binary messages). The sender opens it, performs a {@link HandshakeCodec handshake},
 * then streams elementary-stream packets until it disconnects.
 *
 * <p>Deliberately container-less: the payload of a {@link #TYPE_VIDEO} packet is raw Annex-B H.264
 * that can be handed straight to {@code MediaCodec}, whether it came from Android's
 * {@code MediaCodec} encoder, iOS {@code VideoToolbox}, or a browser's {@code WebCodecs}
 * {@code VideoEncoder}. That is what lets every platform share one decode path on the TV.
 */
public final class BplayProtocol {

    private BplayProtocol() {}

    /** First four bytes a sender writes. */
    public static final byte[] MAGIC = {'B', 'P', 'L', 'Y'};

    /** Bumped only for incompatible changes; receivers reject mismatches. */
    public static final int VERSION = 1;

    /** Raw TCP stream port (native senders). */
    public static final int DEFAULT_STREAM_PORT = 7100;

    /** HTTPS + WebSocket port (browser senders, control UI). */
    public static final int DEFAULT_WEB_PORT = 8443;

    /** mDNS/Bonjour service type used for discovery on the local network. */
    public static final String SERVICE_TYPE = "_bplay._tcp.";

    // ---- Packet types ----------------------------------------------------

    /** Codec-specific data (Annex-B SPS/PPS). Sent before the first video packet. */
    public static final int TYPE_VIDEO_CONFIG = 1;
    /** One access unit of Annex-B H.264. */
    public static final int TYPE_VIDEO = 2;
    /** Codec-specific data for audio (AAC AudioSpecificConfig, or Opus identification header). */
    public static final int TYPE_AUDIO_CONFIG = 3;
    /** One audio access unit. */
    public static final int TYPE_AUDIO = 4;
    /** Keep-alive; payload ignored. Lets the receiver notice a silently dropped Wi-Fi link. */
    public static final int TYPE_PING = 5;
    /** Sender is going away cleanly. */
    public static final int TYPE_BYE = 6;
    /** UTF-8 {@link Params} blob: rotation or resolution changed mid-session. */
    public static final int TYPE_META = 7;

    // ---- Packet flags ----------------------------------------------------

    /** Payload is a keyframe/IDR. */
    public static final int FLAG_KEYFRAME = 0x01;

    // ---- Handshake status codes -----------------------------------------

    public static final int STATUS_OK = 0;
    public static final int STATUS_BAD_PIN = 1;
    public static final int STATUS_BUSY = 2;
    public static final int STATUS_BAD_VERSION = 3;

    /** Fixed-size packet header: type(1) flags(1) length(4) ptsUs(8). */
    public static final int HEADER_SIZE = 14;

    /**
     * Hard ceiling on a single packet, to stop a malformed or hostile sender from making the TV
     * allocate an arbitrary buffer. 8 MB is far above any real 4K keyframe.
     */
    public static final int MAX_PACKET_SIZE = 8 * 1024 * 1024;

    public static String statusMessage(int status) {
        switch (status) {
            case STATUS_OK: return "Connected";
            case STATUS_BAD_PIN: return "Wrong PIN";
            case STATUS_BUSY: return "The TV is already mirroring another device";
            case STATUS_BAD_VERSION: return "Version mismatch - update BPlay on both devices";
            default: return "Unknown error (" + status + ")";
        }
    }
}
