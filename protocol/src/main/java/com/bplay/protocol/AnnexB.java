package com.bplay.protocol;

import java.io.ByteArrayOutputStream;

/**
 * Just enough H.264 bitstream awareness to start a decoder.
 *
 * <p>Senders do not all hand over codec-specific data the same way. Android's {@code MediaCodec}
 * emits SPS/PPS as a separate config buffer, but a browser's {@code WebCodecs} encoder in
 * {@code annexb} mode reports no description at all and simply inlines SPS/PPS ahead of every
 * keyframe. {@code MediaCodec} on the TV needs that data up front to configure, so when a sender
 * does not send it explicitly we recover it from the first keyframe.
 */
public final class AnnexB {

    /** NAL unit types we care about, from the low 5 bits of the header byte. */
    private static final int NAL_IDR = 5;
    private static final int NAL_SPS = 7;
    private static final int NAL_PPS = 8;

    private AnnexB() {}

    /**
     * Extracts the SPS and PPS NAL units, start codes included, in the order they appear.
     *
     * @return the codec-specific data, or null when the frame carries no parameter sets
     */
    public static byte[] extractCsd(byte[] frame) {
        if (frame == null || frame.length < 5) {
            return null;
        }
        ByteArrayOutputStream csd = new ByteArrayOutputStream();
        boolean foundSps = false;
        boolean foundPps = false;

        int position = nextStartCode(frame, 0);
        while (position >= 0) {
            int headerLength = startCodeLength(frame, position);
            int nalStart = position + headerLength;
            if (nalStart >= frame.length) {
                break;
            }
            int type = frame[nalStart] & 0x1F;
            int nextPosition = nextStartCode(frame, nalStart);
            int nalEnd = nextPosition < 0 ? frame.length : nextPosition;

            if (type == NAL_SPS || type == NAL_PPS) {
                csd.write(frame, position, nalEnd - position);
                foundSps |= type == NAL_SPS;
                foundPps |= type == NAL_PPS;
            } else if (type == NAL_IDR && foundSps && foundPps) {
                break; // everything before the picture data is what the decoder needs
            }
            position = nextPosition;
        }
        // A PPS without its SPS configures nothing; treat a partial set as absent.
        return foundSps && foundPps ? csd.toByteArray() : null;
    }

    /** True when the access unit contains an IDR picture. */
    public static boolean isKeyframe(byte[] frame) {
        if (frame == null) {
            return false;
        }
        int position = nextStartCode(frame, 0);
        while (position >= 0) {
            int nalStart = position + startCodeLength(frame, position);
            if (nalStart >= frame.length) {
                return false;
            }
            if ((frame[nalStart] & 0x1F) == NAL_IDR) {
                return true;
            }
            position = nextStartCode(frame, nalStart);
        }
        return false;
    }

    /** Index of the next {@code 00 00 01} or {@code 00 00 00 01}, at or after {@code from}. */
    static int nextStartCode(byte[] data, int from) {
        for (int i = Math.max(from, 0); i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    static int startCodeLength(byte[] data, int position) {
        return (position + 3 < data.length && data[position + 2] == 0) ? 4 : 3;
    }
}
