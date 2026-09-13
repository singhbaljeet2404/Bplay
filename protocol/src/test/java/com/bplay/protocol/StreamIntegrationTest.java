package com.bplay.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Drives a whole mirroring session over a real loopback socket.
 *
 * <p>The unit tests prove the codecs round-trip in memory, which is not the same claim. A real
 * socket splits writes wherever it likes: a 200 KB keyframe arrives as a dozen partial reads, and
 * a packet header can straddle two of them. Framing bugs of that shape are invisible in a
 * ByteArrayInputStream and catastrophic on a television, because once the reader loses alignment
 * every subsequent packet is garbage.
 */
public class StreamIntegrationTest {

    private static final String PIN = "4821";

    /** The receiver's side of a session, using the same codecs TcpStreamServer uses. */
    private static final class Receiver implements AutoCloseable {
        final ServerSocket serverSocket;
        final CountDownLatch finished = new CountDownLatch(1);
        final List<Packet> packets = new ArrayList<>();
        final AtomicReference<HandshakeCodec.Request> request = new AtomicReference<>();
        final AtomicInteger statusSent = new AtomicInteger(-1);
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final boolean fragmentReads;

        Receiver(boolean fragmentReads) throws IOException {
            this.fragmentReads = fragmentReads;
            serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(this::serve, "stream-test-receiver");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(10_000);
                InputStream raw = socket.getInputStream();
                InputStream in = fragmentReads
                        ? new OneByteAtATimeStream(raw)
                        : new BufferedInputStream(raw, 64 * 1024);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());

                HandshakeCodec.Request incoming = HandshakeCodec.readRequest(in);
                request.set(incoming);

                if (!HandshakeCodec.pinMatches(PIN,
                        incoming.params.get(HandshakeCodec.KEY_PIN, ""))) {
                    statusSent.set(BplayProtocol.STATUS_BAD_PIN);
                    HandshakeCodec.writeResponse(out, BplayProtocol.STATUS_BAD_PIN,
                            new Params().put(HandshakeCodec.KEY_ERROR, "Wrong PIN"));
                    return;
                }
                statusSent.set(BplayProtocol.STATUS_OK);
                HandshakeCodec.writeResponse(out, BplayProtocol.STATUS_OK, new Params()
                        .put(HandshakeCodec.KEY_NAME, "Living Room Fire TV")
                        .put(HandshakeCodec.KEY_MAX_HEIGHT, 1080)
                        .put(HandshakeCodec.KEY_MAX_BITRATE, 8_000_000));

                while (true) {
                    Packet packet = Packet.read(in);
                    packets.add(packet);
                    if (packet.type == BplayProtocol.TYPE_BYE) {
                        return;
                    }
                }
            } catch (Exception e) {
                failure.set(e);
            } finally {
                finished.countDown();
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void awaitAndRethrow() throws Exception {
            assertTrue("receiver did not finish", finished.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw new AssertionError("receiver failed", failure.get());
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    /** Worst-case fragmentation: every read returns a single byte. */
    private static final class OneByteAtATimeStream extends FilterInputStream {
        OneByteAtATimeStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int value = in.read();
            if (value < 0) {
                return -1;
            }
            b[off] = (byte) value;
            return 1;
        }
    }

    private static Params senderHandshake(String pin) {
        return new Params()
                .put(HandshakeCodec.KEY_PIN, pin)
                .put(HandshakeCodec.KEY_NAME, "Pixel 7")
                .put(HandshakeCodec.KEY_PLATFORM, "android")
                .put(HandshakeCodec.KEY_WIDTH, 1080)
                .put(HandshakeCodec.KEY_HEIGHT, 2400)
                .put(HandshakeCodec.KEY_VIDEO_CODEC, "video/avc");
    }

    @Test
    public void aWholeSessionSurvivesARealSocket() throws Exception {
        try (Receiver receiver = new Receiver(false)) {
            Socket socket = new Socket("127.0.0.1", receiver.port());
            socket.setTcpNoDelay(true);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 128 * 1024);
            InputStream in = new BufferedInputStream(socket.getInputStream());

            HandshakeCodec.writeRequest(out, senderHandshake(PIN));
            Params[] response = new Params[1];
            assertEquals(BplayProtocol.STATUS_OK, HandshakeCodec.readResponse(in, response));
            assertEquals("Living Room Fire TV", response[0].get(HandshakeCodec.KEY_NAME, null));
            assertEquals(1080, response[0].getInt(HandshakeCodec.KEY_MAX_HEIGHT, 0));

            Random random = new Random(11);
            byte[] csd = new byte[]{0, 0, 0, 1, 0x67, 0x42, (byte) 0xe0, 0x1f,
                                    0, 0, 0, 1, 0x68, (byte) 0xce};
            Packet.write(out, BplayProtocol.TYPE_VIDEO_CONFIG, 0, 0, csd, 0, csd.length);

            // A big keyframe followed by a run of small frames: the mix a real encoder produces,
            // and the one most likely to expose a framing bug.
            byte[] keyframe = new byte[200_000];
            random.nextBytes(keyframe);
            Packet.write(out, BplayProtocol.TYPE_VIDEO, BplayProtocol.FLAG_KEYFRAME,
                    0, keyframe, 0, keyframe.length);

            List<byte[]> deltas = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                byte[] frame = new byte[500 + random.nextInt(9000)];
                random.nextBytes(frame);
                deltas.add(frame);
                Packet.write(out, BplayProtocol.TYPE_VIDEO, 0, (i + 1) * 33_333L,
                        frame, 0, frame.length);
            }
            Packet.write(out, BplayProtocol.TYPE_BYE, 0, 0, new byte[0], 0, 0);
            out.flush();

            receiver.awaitAndRethrow();
            socket.close();

            assertEquals("Pixel 7", receiver.request.get().deviceName());
            assertEquals(1 + 1 + 60 + 1, receiver.packets.size());

            assertEquals(BplayProtocol.TYPE_VIDEO_CONFIG, receiver.packets.get(0).type);
            assertArrayEquals(csd, receiver.packets.get(0).payload);

            Packet key = receiver.packets.get(1);
            assertTrue("keyframe flag lost in transit", key.isKeyframe());
            assertArrayEquals(keyframe, key.payload);

            for (int i = 0; i < 60; i++) {
                Packet delta = receiver.packets.get(2 + i);
                assertEquals((i + 1) * 33_333L, delta.ptsUs);
                assertArrayEquals("frame " + i + " corrupted", deltas.get(i), delta.payload);
            }
            assertEquals(BplayProtocol.TYPE_BYE,
                    receiver.packets.get(receiver.packets.size() - 1).type);
        }
    }

    @Test
    public void framingHoldsWhenEveryReadReturnsOneByte() throws Exception {
        // The pathological case a congested Wi-Fi link approximates: headers split across reads.
        try (Receiver receiver = new Receiver(true)) {
            Socket socket = new Socket("127.0.0.1", receiver.port());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());

            HandshakeCodec.writeRequest(out, senderHandshake(PIN));
            assertEquals(BplayProtocol.STATUS_OK,
                    HandshakeCodec.readResponse(in, new Params[1]));

            Random random = new Random(23);
            List<byte[]> sent = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                byte[] frame = new byte[300 + random.nextInt(2000)];
                random.nextBytes(frame);
                sent.add(frame);
                Packet.write(out, BplayProtocol.TYPE_VIDEO,
                        i == 0 ? BplayProtocol.FLAG_KEYFRAME : 0,
                        i * 33_333L, frame, 0, frame.length);
            }
            Packet.write(out, BplayProtocol.TYPE_BYE, 0, 0, new byte[0], 0, 0);
            out.flush();

            receiver.awaitAndRethrow();
            socket.close();

            assertEquals(13, receiver.packets.size());
            for (int i = 0; i < 12; i++) {
                assertArrayEquals("frame " + i + " misaligned",
                        sent.get(i), receiver.packets.get(i).payload);
                assertEquals(i * 33_333L, receiver.packets.get(i).ptsUs);
            }
        }
    }

    @Test
    public void aWrongPinIsRefusedBeforeAnyVideoFlows() throws Exception {
        try (Receiver receiver = new Receiver(false)) {
            Socket socket = new Socket("127.0.0.1", receiver.port());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());

            HandshakeCodec.writeRequest(out, senderHandshake("0000"));
            Params[] response = new Params[1];
            int status = HandshakeCodec.readResponse(in, response);

            assertEquals(BplayProtocol.STATUS_BAD_PIN, status);
            assertEquals("Wrong PIN", response[0].get(HandshakeCodec.KEY_ERROR, null));

            receiver.awaitAndRethrow();
            socket.close();
            assertTrue("no packets may be accepted after a failed PIN",
                    receiver.packets.isEmpty());
        }
    }

    @Test
    public void aPortScannerIsRejectedRatherThanHangingTheReceiver() throws Exception {
        // Anything on the network can connect to an open port; a stray HTTP request must not
        // wedge the accept loop.
        try (Receiver receiver = new Receiver(false)) {
            Socket socket = new Socket("127.0.0.1", receiver.port());
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes("UTF-8"));
            socket.getOutputStream().flush();

            assertTrue("receiver did not reject the junk connection",
                    receiver.finished.await(15, TimeUnit.SECONDS));
            assertTrue("expected a protocol error", receiver.failure.get() instanceof IOException);
            assertTrue(receiver.failure.get().getMessage().contains("bad magic"));
            socket.close();
        }
    }
}
