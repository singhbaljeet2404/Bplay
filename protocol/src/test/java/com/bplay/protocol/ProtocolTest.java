package com.bplay.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import org.junit.Test;

public class ProtocolTest {

    @Test
    public void paramsRoundTripPlainValues() {
        Params p = new Params().put("pin", "4821").put("name", "Pixel 7").put("width", 1080);
        Params decoded = Params.decode(p.encode());
        assertEquals("4821", decoded.get("pin", null));
        assertEquals("Pixel 7", decoded.get("name", null));
        assertEquals(1080, decoded.getInt("width", 0));
    }

    @Test
    public void paramsSurviveSeparatorsInsideValues() {
        // A user really can name a phone "Bob=Home\nTV" via the OS device-name field.
        String nasty = "Bob=Home\nTV\\Backslash";
        Params decoded = Params.decode(new Params().put("name", nasty).put("after", "ok").encode());
        assertEquals(nasty, decoded.get("name", null));
        assertEquals("ok", decoded.get("after", null));
    }

    @Test
    public void paramsFallBackWhenKeyMissingOrMalformed() {
        Params p = Params.decode(new Params().put("fps", "not-a-number").encode());
        assertEquals(30, p.getInt("fps", 30));
        assertEquals(60, p.getInt("absent", 60));
        assertFalse(p.has("absent"));
    }

    @Test
    public void packetRoundTripsThroughAStream() throws IOException {
        byte[] payload = new byte[5000];
        new Random(7).nextBytes(payload);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Packet.write(buffer, BplayProtocol.TYPE_VIDEO, BplayProtocol.FLAG_KEYFRAME,
                1234567890L, payload, 0, payload.length);

        Packet read = Packet.read(new ByteArrayInputStream(buffer.toByteArray()));
        assertEquals(BplayProtocol.TYPE_VIDEO, read.type);
        assertTrue(read.isKeyframe());
        assertEquals(1234567890L, read.ptsUs);
        assertArrayEquals(payload, read.payload);
    }

    @Test
    public void packetRoundTripsAboveTheSingleWriteThreshold() throws IOException {
        // Exercises the two-write branch used for large keyframes.
        byte[] payload = new byte[200_000];
        new Random(9).nextBytes(payload);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Packet.write(buffer, BplayProtocol.TYPE_VIDEO, 0, 42L, payload, 0, payload.length);
        Packet read = Packet.read(new ByteArrayInputStream(buffer.toByteArray()));
        assertArrayEquals(payload, read.payload);
        assertFalse(read.isKeyframe());
    }

    @Test
    public void severalPacketsReadBackInOrder() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (int i = 0; i < 20; i++) {
            byte[] payload = ("frame-" + i).getBytes("UTF-8");
            Packet.write(buffer, BplayProtocol.TYPE_VIDEO, i % 5 == 0
                    ? BplayProtocol.FLAG_KEYFRAME : 0, i * 33_000L, payload, 0, payload.length);
        }
        ByteArrayInputStream in = new ByteArrayInputStream(buffer.toByteArray());
        for (int i = 0; i < 20; i++) {
            Packet p = Packet.read(in);
            assertEquals("frame-" + i, new String(p.payload, "UTF-8"));
            assertEquals(i * 33_000L, p.ptsUs);
            assertEquals(i % 5 == 0, p.isKeyframe());
        }
    }

    @Test
    public void parseMatchesTheStreamEncoding() throws IOException {
        byte[] payload = "web-sender-chunk".getBytes("UTF-8");
        byte[] wire = Packet.toBytes(BplayProtocol.TYPE_VIDEO, BplayProtocol.FLAG_KEYFRAME,
                99L, payload);
        Packet fromArray = Packet.parse(wire);
        Packet fromStream = Packet.read(new ByteArrayInputStream(wire));
        assertEquals(fromStream.type, fromArray.type);
        assertEquals(fromStream.ptsUs, fromArray.ptsUs);
        assertArrayEquals(fromStream.payload, fromArray.payload);
    }

    @Test
    public void parseRejectsATruncatedMessage() {
        byte[] wire = Packet.toBytes(BplayProtocol.TYPE_VIDEO, 0, 0L, new byte[100]);
        byte[] truncated = new byte[wire.length - 10];
        System.arraycopy(wire, 0, truncated, 0, truncated.length);
        try {
            Packet.parse(truncated);
            fail("expected a length mismatch");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Declared length"));
        }
    }

    @Test
    public void readRejectsAnAbsurdlyLargePacket() {
        byte[] header = Packet.header(BplayProtocol.TYPE_VIDEO, 0, 0L, Integer.MAX_VALUE);
        try {
            Packet.read(new ByteArrayInputStream(header));
            fail("expected the size guard to trip");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("too large"));
        }
    }

    @Test
    public void handshakeRoundTrips() throws IOException {
        ByteArrayOutputStream toReceiver = new ByteArrayOutputStream();
        HandshakeCodec.writeRequest(toReceiver, new Params()
                .put(HandshakeCodec.KEY_PIN, "1234")
                .put(HandshakeCodec.KEY_NAME, "iPad Pro")
                .put(HandshakeCodec.KEY_PLATFORM, "ios")
                .put(HandshakeCodec.KEY_WIDTH, 1668)
                .put(HandshakeCodec.KEY_HEIGHT, 2388));

        HandshakeCodec.Request request =
                HandshakeCodec.readRequest(new ByteArrayInputStream(toReceiver.toByteArray()));
        assertEquals(BplayProtocol.VERSION, request.version);
        assertEquals("iPad Pro", request.deviceName());
        assertEquals("ios", request.platform());
        assertEquals("video/avc", request.videoCodec());
        assertEquals("", request.audioCodec());

        ByteArrayOutputStream toSender = new ByteArrayOutputStream();
        HandshakeCodec.writeResponse(toSender, BplayProtocol.STATUS_OK,
                new Params().put(HandshakeCodec.KEY_MAX_WIDTH, 1920)
                        .put(HandshakeCodec.KEY_MAX_HEIGHT, 1080));
        Params[] out = new Params[1];
        int status = HandshakeCodec.readResponse(
                new ByteArrayInputStream(toSender.toByteArray()), out);
        assertEquals(BplayProtocol.STATUS_OK, status);
        assertEquals(1920, out[0].getInt(HandshakeCodec.KEY_MAX_WIDTH, 0));
    }

    @Test
    public void handshakeRejectsNonBplayClients() throws IOException {
        // e.g. a port scanner, or a browser that hit the stream port by mistake
        byte[] junk = "GET / HTTP/1.1\r\n\r\n".getBytes("UTF-8");
        try {
            HandshakeCodec.readRequest(new ByteArrayInputStream(junk));
            fail("expected magic check to reject");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("bad magic"));
        }
    }

    @Test
    public void pinComparison() {
        assertTrue(HandshakeCodec.pinMatches("1234", "1234"));
        assertFalse(HandshakeCodec.pinMatches("1234", "1235"));
        assertFalse(HandshakeCodec.pinMatches("1234", "123"));
        assertFalse(HandshakeCodec.pinMatches("1234", null));
        assertTrue("an empty expected PIN means the user turned the PIN off",
                HandshakeCodec.pinMatches("", "anything"));
    }
}
