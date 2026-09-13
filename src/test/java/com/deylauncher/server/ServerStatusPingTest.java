package com.deylauncher.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the Server List Ping client: address parsing, the VarInt framing, status JSON
 * parsing, and a real end-to-end read against a local stub server (no external network needed).
 */
class ServerStatusPingTest {

    @Test
    void parseAddress_handlesTheShapesUsersPaste() {
        assertEquals(new ServerStatusPing.Address("mc.example.com", 25565),
                ServerStatusPing.parseAddress("mc.example.com"));
        assertEquals(new ServerStatusPing.Address("mc.example.com", 25566),
                ServerStatusPing.parseAddress("mc.example.com:25566"));
        assertEquals(new ServerStatusPing.Address("mc.example.com", 25565),
                ServerStatusPing.parseAddress("https://mc.example.com/path"));
        assertEquals(new ServerStatusPing.Address("mc.example.com", 1234),
                ServerStatusPing.parseAddress("http://mc.example.com:1234/"));
        assertEquals(new ServerStatusPing.Address("", 25565), ServerStatusPing.parseAddress("   "));
        // A bare IPv6 literal keeps its colons; a bracketed one can carry a port.
        assertEquals(new ServerStatusPing.Address("::1", 25565), ServerStatusPing.parseAddress("::1"));
        assertEquals(new ServerStatusPing.Address("::1", 25566), ServerStatusPing.parseAddress("[::1]:25566"));
        // An invalid port falls back rather than throwing.
        assertEquals(new ServerStatusPing.Address("host", 25565), ServerStatusPing.parseAddress("host:notaport"));
    }

    @Test
    void varInt_roundTripsIncludingTheStatusPingNegativeOne() throws Exception {
        for (int value : new int[] {0, 1, 127, 128, 255, 2147483647, -1}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ServerStatusPing.writeVarInt(out, value);
            int read = ServerStatusPing.readVarInt(new ByteArrayInputStream(out.toByteArray()));
            assertEquals(value, read);
        }
        // -1 must be the canonical 5-byte form the status handshake relies on.
        ByteArrayOutputStream neg = new ByteArrayOutputStream();
        ServerStatusPing.writeVarInt(neg, -1);
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x0F},
                neg.toByteArray());
    }

    @Test
    void parseStatusJson_readsPlayersVersionMotdAndFavicon() {
        String json = "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},"
                + "\"players\":{\"online\":3,\"max\":20},"
                + "\"description\":{\"text\":\"Hello \",\"extra\":[{\"text\":\"world\"}]},"
                + "\"favicon\":\"data:image/png;base64,AAAA\"}";
        var st = ServerStatusPing.parseStatusJson(json);
        assertTrue(st.online());
        assertEquals(3, st.onlinePlayers());
        assertEquals(20, st.maxPlayers());
        assertEquals("1.21.1", st.version());
        assertEquals("Hello world", st.motd());
        assertEquals("data:image/png;base64,AAAA", st.faviconDataUri());
    }

    @Test
    void parseStatusJson_flattensStringMotdAndStripsLegacyColors() {
        var st = ServerStatusPing.parseStatusJson(
                "{\"description\":\"\u00A7aA \u00A7lGreen\u00A7r MOTD\",\"players\":{\"online\":0,\"max\":10}}");
        assertTrue(st.online());
        assertEquals("A Green MOTD", st.motd());
    }

    @Test
    void parseStatusJson_reportsOfflineForGarbage() {
        assertFalse(ServerStatusPing.parseStatusJson("not json at all").online());
        assertFalse(ServerStatusPing.parseStatusJson("").online());
    }

    @Test
    void ping_readsARealStatusResponseOverTheWire() throws Exception {
        String json = "{\"version\":{\"name\":\"1.20.4\"},\"players\":{\"online\":7,\"max\":50},"
                + "\"description\":\"Test server\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread responder = new Thread(() -> {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    // Read exactly the handshake and the status request (their own VarInt lengths),
                    // so we never block waiting for bytes the client won't send.
                    in.readNBytes(ServerStatusPing.readVarInt(in));
                    in.readNBytes(ServerStatusPing.readVarInt(in));
                    ByteArrayOutputStream payload = new ByteArrayOutputStream();
                    ServerStatusPing.writeVarInt(payload, 0x00); // packet id
                    ServerStatusPing.writeString(payload, json);
                    ByteArrayOutputStream framed = new ByteArrayOutputStream();
                    ServerStatusPing.writeVarInt(framed, payload.size());
                    framed.write(payload.toByteArray());
                    OutputStream out = client.getOutputStream();
                    out.write(framed.toByteArray());
                    out.flush();
                } catch (Exception ignored) {
                }
            });
            responder.setDaemon(true);
            responder.start();

            var st = ServerStatusPing.ping("127.0.0.1", port, 3000);
            assertTrue(st.online(), "a well-formed status response should read back as online");
            assertEquals(7, st.onlinePlayers());
            assertEquals(50, st.maxPlayers());
            assertEquals("1.20.4", st.version());
            assertEquals("Test server", st.motd());
        }
    }

    @Test
    void ping_reportsOfflineWhenNothingIsListening() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        } // closed immediately, so nothing is listening on it any more
        assertFalse(ServerStatusPing.ping("127.0.0.1", port, 500).online());
    }

    @Test
    void hasExplicitPort_distinguishesTypedPortsFromBareHosts() {
        assertFalse(ServerStatusPing.hasExplicitPort("play.example.com"));
        assertFalse(ServerStatusPing.hasExplicitPort("https://play.example.com/path"));
        assertTrue(ServerStatusPing.hasExplicitPort("play.example.com:25565"));
        assertTrue(ServerStatusPing.hasExplicitPort("::1")); // IPv6 literal -> never SRV
    }

    @Test
    void resolveSrv_isSkippedForExplicitPortsIpsAndEmptyHosts() {
        // These paths must never issue a DNS query -- they return the input unchanged.
        var withPort = new ServerStatusPing.Address("play.example.com", 25565);
        assertEquals(withPort, ServerStatusPing.resolveSrv(withPort, true));
        var ip = new ServerStatusPing.Address("10.0.0.5", 25565);
        assertEquals(ip, ServerStatusPing.resolveSrv(ip, false));
        var v6 = new ServerStatusPing.Address("::1", 25565);
        assertEquals(v6, ServerStatusPing.resolveSrv(v6, false));
        var empty = new ServerStatusPing.Address("", 25565);
        assertEquals(empty, ServerStatusPing.resolveSrv(empty, false));
    }
}
