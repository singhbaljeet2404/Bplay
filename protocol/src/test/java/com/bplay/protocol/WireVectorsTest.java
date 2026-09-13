package com.bplay.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Checks the Java implementation against the same bytes the browser sender produces.
 *
 * <p>The wire format exists twice -- once here, once in JavaScript for the browser sender -- and
 * the pair only works if they agree exactly. The vectors are generated from the JavaScript
 * (tools/wire-check.js) and parsed here, so a change to either side that breaks compatibility
 * fails a build rather than a living room.
 */
public class WireVectorsTest {

    private static Map<String, byte[]> vectors;

    @BeforeClass
    public static void loadVectors() throws IOException {
        File file = new File("../tools/wire-vectors.txt");
        assertTrue("Missing " + file.getAbsolutePath() + " -- run: node tools/wire-check.js --write",
                file.exists());
        vectors = new HashMap<>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            vectors.put(trimmed.substring(0, space), hex(trimmed.substring(space + 1)));
        }
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] vector(String name) {
        byte[] bytes = vectors.get(name);
        assertTrue("No vector named " + name, bytes != null);
        return bytes;
    }

    @Test
    public void readsAHandshakeProducedByTheBrowserSender() throws IOException {
        HandshakeCodec.Request request =
                HandshakeCodec.readRequest(new ByteArrayInputStream(vector("handshake_android")));
        assertEquals(BplayProtocol.VERSION, request.version);
        assertEquals("Pixel 7", request.deviceName());
        assertEquals("android", request.platform());
        assertEquals("4821", request.params.get(HandshakeCodec.KEY_PIN, null));
        assertEquals(1080, request.params.getInt(HandshakeCodec.KEY_WIDTH, 0));
        assertEquals(2400, request.params.getInt(HandshakeCodec.KEY_HEIGHT, 0));
        assertEquals("audio/mp4a-latm", request.audioCodec());
    }

    @Test
    public void treatsAnEmptyPinAsNoPin() throws IOException {
        HandshakeCodec.Request request =
                HandshakeCodec.readRequest(new ByteArrayInputStream(vector("handshake_web_nopin")));
        assertEquals("Windows PC (Chrome)", request.deviceName());
        assertEquals("web", request.platform());
        assertEquals("", request.params.get(HandshakeCodec.KEY_PIN, "absent"));
        assertEquals("", request.audioCodec());
    }

    @Test
    public void unescapesExactlyTheWayTheBrowserEscapes() throws IOException {
        HandshakeCodec.Request request =
                HandshakeCodec.readRequest(new ByteArrayInputStream(vector("handshake_escapes")));
        assertEquals("Bob=Home\nTV\\Backslash", request.deviceName());
        assertEquals("web", request.platform());
    }

    @Test
    public void readsAKeyframePacket() throws IOException {
        Packet packet = Packet.parse(vector("packet_keyframe"));
        assertEquals(BplayProtocol.TYPE_VIDEO, packet.type);
        assertTrue(packet.isKeyframe());
        assertEquals(1234567L, packet.ptsUs);
        // Annex-B start code, then an SPS NAL (type 7).
        assertArrayEquals(new byte[]{0, 0, 0, 1}, java.util.Arrays.copyOf(packet.payload, 4));
        assertEquals(7, packet.payload[4] & 0x1F);
    }

    @Test
    public void readsATimestampBeyondThirtyTwoBits() throws IOException {
        // ~2.5 hours of microseconds. A 32-bit field would have wrapped long before here.
        Packet packet = Packet.parse(vector("packet_large_pts"));
        assertEquals(9007199254L, packet.ptsUs);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, packet.payload);
    }

    @Test
    public void readsAnEmptyKeepAlive() throws IOException {
        Packet packet = Packet.parse(vector("packet_ping"));
        assertEquals(BplayProtocol.TYPE_PING, packet.type);
        assertEquals(0, packet.payload.length);
    }

    @Test
    public void readsTheBrowsersAudioDescriptor() {
        Params params = Params.decode(vector("params_audio_config"));
        assertEquals("opus", params.get("codec", null));
        assertEquals(48000, params.getInt("sampleRate", 0));
        assertEquals(2, params.getInt("channels", 0));
    }

    @Test
    public void readsAFileOfferFromTheBrowser() throws IOException {
        Packet packet = Packet.parse(vector("media_offer"));
        assertEquals(BplayProtocol.TYPE_MEDIA_OFFER, packet.type);
        Params offer = Params.decode(packet.payload);
        assertEquals("Holiday.mp4", offer.get("name", null));
        assertEquals("video/mp4", offer.get("mime", null));
        assertEquals("video", offer.get("kind", null));
        // 3 GB: the size of a real film, and well past what an int could hold.
        assertEquals(3221225472L, Long.parseLong(offer.get("size", "0")));
    }

    @Test
    public void readsAByteRangeFromNearTheEndOfALargeFile() throws IOException {
        Packet packet = Packet.parse(vector("media_chunk"));
        assertEquals(BplayProtocol.TYPE_MEDIA_DATA, packet.type);
        assertTrue("must be marked as completing the request",
                (packet.flags & BplayProtocol.FLAG_LAST_CHUNK) != 0);

        MediaChunk chunk = MediaChunk.decode(packet.payload);
        assertEquals(7, chunk.requestId);
        // Seeking near the end of a 3 GB file is precisely where a 32-bit offset would wrap.
        assertEquals(3221225000L, chunk.offset);
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
                chunk.data);
    }

    @Test
    public void mediaChunkRoundTripsThroughJava() {
        byte[] data = new byte[4096];
        new java.util.Random(3).nextBytes(data);
        byte[] encoded = MediaChunk.encode(42, 9_000_000_000L, data, 0, data.length);
        MediaChunk decoded = MediaChunk.decode(encoded);
        assertEquals(42, decoded.requestId);
        assertEquals(9_000_000_000L, decoded.offset);
        assertArrayEquals(data, decoded.data);
    }

    @Test
    public void javaProducesTheSameBytesTheBrowserDoes() throws IOException {
        // The other direction: encoding the same fields here must reproduce the vector exactly.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HandshakeCodec.writeRequest(out, new Params()
                .put("pin", "4821")
                .put("name", "Pixel 7")
                .put("platform", "android")
                .put("width", 1080)
                .put("height", 2400)
                .put("fps", 30)
                .put("vcodec", "video/avc")
                .put("acodec", "audio/mp4a-latm"));
        assertArrayEquals(vector("handshake_android"), out.toByteArray());

        assertArrayEquals(vector("packet_ping"),
                Packet.toBytes(BplayProtocol.TYPE_PING, 0, 0, new byte[0]));
    }
}
