package com.bplay.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

public class AnnexBTest {

    private static final byte[] START4 = {0, 0, 0, 1};
    private static final byte[] START3 = {0, 0, 1};

    private static byte[] nal(byte[] startCode, int type, int payloadLength) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(startCode);
        out.write(type & 0x1F);
        for (int i = 0; i < payloadLength; i++) {
            out.write(0x10 + i); // never 0, so no accidental start code
        }
        return out.toByteArray();
    }

    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part);
        }
        return out.toByteArray();
    }

    @Test
    public void pullsSpsAndPpsOutOfAKeyframe() throws IOException {
        byte[] sps = nal(START4, 7, 10);
        byte[] pps = nal(START4, 8, 4);
        byte[] idr = nal(START4, 5, 40);
        byte[] csd = AnnexB.extractCsd(join(sps, pps, idr));
        assertArrayEquals(join(sps, pps), csd);
    }

    @Test
    public void handlesThreeByteStartCodes() throws IOException {
        // WebCodecs and some hardware encoders mix 3- and 4-byte start codes in one stream.
        byte[] sps = nal(START3, 7, 8);
        byte[] pps = nal(START4, 8, 3);
        byte[] idr = nal(START3, 5, 20);
        assertArrayEquals(join(sps, pps), AnnexB.extractCsd(join(sps, pps, idr)));
    }

    @Test
    public void skipsAccessUnitDelimitersAndSeiBeforeTheParameterSets() throws IOException {
        byte[] aud = nal(START4, 9, 1);
        byte[] sei = nal(START4, 6, 12);
        byte[] sps = nal(START4, 7, 9);
        byte[] pps = nal(START4, 8, 4);
        byte[] idr = nal(START4, 5, 30);
        assertArrayEquals(join(sps, pps), AnnexB.extractCsd(join(aud, sei, sps, pps, idr)));
    }

    @Test
    public void returnsNullWhenTheFrameCarriesNoParameterSets() throws IOException {
        // A P-frame: nothing to configure a decoder with.
        assertNull(AnnexB.extractCsd(join(nal(START4, 1, 50))));
        assertNull(AnnexB.extractCsd(null));
        assertNull(AnnexB.extractCsd(new byte[]{0, 0, 1}));
    }

    @Test
    public void treatsAnIncompleteParameterSetAsAbsent() throws IOException {
        // SPS with no PPS cannot configure MediaCodec, so it must not be handed over as if it could.
        assertNull(AnnexB.extractCsd(join(nal(START4, 7, 10), nal(START4, 5, 20))));
    }

    @Test
    public void detectsKeyframes() throws IOException {
        assertTrue(AnnexB.isKeyframe(join(nal(START4, 7, 4), nal(START4, 8, 4),
                nal(START4, 5, 20))));
        assertFalse(AnnexB.isKeyframe(join(nal(START4, 1, 20))));
        assertFalse(AnnexB.isKeyframe(new byte[0]));
    }
}
