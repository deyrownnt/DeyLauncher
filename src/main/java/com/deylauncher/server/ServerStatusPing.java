package com.deylauncher.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Minimal Minecraft "Server List Ping" (SLP) client -- the same handshake the game itself uses to
 * draw the multiplayer server list. Gives DeyLauncher the one thing it can't know locally:
 * whether an address is actually online, how many players are on it, its MOTD, and its favicon.
 *
 * <p>This speaks the wire protocol directly (raw TCP + VarInt framing) rather than shelling out or
 * asking a third-party API, so it works for the user's own servers (localhost) and any external
 * address, offline, with no dependency and no rate limit. Every failure -- refused connection,
 * timeout, malformed reply -- is reported as {@link Status#offline()} rather than thrown, since a
 * server-status probe is always best-effort.
 *
 * <p>The packet helpers are package-private/static so they can be exercised headlessly by tests.
 */
public final class ServerStatusPing {

    /** Default Minecraft server port when an address has none. */
    public static final int DEFAULT_PORT = 25565;

    /** A resolved host + port pair. */
    public record Address(String host, int port) {}

    /** Result of a status probe. {@code online} false means every other field is meaningless. */
    public record Status(boolean online, int onlinePlayers, int maxPlayers,
                         String motd, String version, String faviconDataUri) {
        public static Status offline() {
            return new Status(false, 0, 0, null, null, null);
        }
    }

    private ServerStatusPing() {}

    /** Daemon threads only, so an abandoned/hung probe (see {@link #ping(String, int)}) can never
     *  keep the JVM alive or block application shutdown. */
    private static final ExecutorService PROBE_POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "server-ping-probe");
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * Runs {@code task} with a hard wall-clock cap, returning {@link Status#offline()} instead of
     * blocking forever if it's exceeded. This exists because the individual timeouts inside a probe
     * (socket connect/read, the SRV lookup) only bound their own step -- hostname resolution done by
     * {@code new InetSocketAddress(host, port)} has NO timeout of its own and can hang indefinitely
     * on a slow/broken resolver, which used to leave the status badge stuck on "Checking..." forever
     * even though every socket-level timeout was respected. Wrapping the whole probe closes that gap.
     * The worker thread is abandoned (not join()'d) on timeout since a blocked DNS call generally
     * can't be interrupted -- it's a daemon thread and pool-owned, so it can't leak past JVM exit.
     */
    private static Status withHardTimeout(int timeoutMs, java.util.function.Supplier<Status> task) {
        Future<Status> future = PROBE_POOL.submit(task::get);
        try {
            // Generous multiplier: covers a full SRV lookup (~4s worst case) plus connect + read,
            // on top of the caller's own per-step timeout.
            return future.get(timeoutMs * 3L + 4000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return Status.offline();
        } catch (Exception e) {
            return Status.offline();
        }
    }

    /**
     * Parses a user-entered server address into host + port. Tolerates the shapes users actually
     * paste: {@code host}, {@code host:port}, {@code https://host:port/path}, and bracketed IPv6
     * ({@code [::1]:25565}). A missing/invalid port falls back to {@link #DEFAULT_PORT}.
     */
    public static Address parseAddress(String raw) {
        if (raw == null) return new Address("", DEFAULT_PORT);
        String s = raw.trim();
        if (s.isEmpty()) return new Address("", DEFAULT_PORT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.startsWith("[")) { // bracketed IPv6 literal, optional :port after the ]
            int close = s.indexOf(']');
            if (close > 0) {
                String host = s.substring(1, close);
                int port = DEFAULT_PORT;
                if (close + 1 < s.length() && s.charAt(close + 1) == ':') {
                    port = parsePort(s.substring(close + 2), DEFAULT_PORT);
                }
                return new Address(host, port);
            }
        }
        int colon = s.lastIndexOf(':');
        // Exactly one colon means host:port; multiple colons is a bare IPv6 literal (no port).
        if (colon > 0 && s.indexOf(':') == colon) {
            return new Address(s.substring(0, colon), parsePort(s.substring(colon + 1), DEFAULT_PORT));
        }
        return new Address(s, DEFAULT_PORT);
    }

    private static int parsePort(String s, int fallback) {
        try {
            int p = Integer.parseInt(s.trim());
            return (p > 0 && p <= 65535) ? p : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** True when the raw address carried an explicit port (so SRV must not overwrite it). */
    static boolean hasExplicitPort(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s.lastIndexOf(':') > 0; // bare "::1" also lands here, which is correct: no SRV for it
    }

    private static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.indexOf(':') >= 0) return true; // IPv6
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /**
     * Best-effort Minecraft SRV lookup: "_minecraft._tcp.&lt;host&gt;" is how the game finds the real
     * host + port for an address like {@code play.example.com}, so honoring it is what makes the
     * online check work for the many servers that rely on it. Skipped when a port was typed or the
     * host is already an IP. Any failure (no record, no DNS provider in this runtime, timeout) simply
     * returns the literal address, so this can only ever add reachability, never remove it.
     */
    static Address resolveSrv(Address parsed, boolean explicitPort) {
        if (explicitPort || parsed.host().isEmpty() || isIpLiteral(parsed.host())) return parsed;
        try {
            java.util.Hashtable<String, String> env = new java.util.Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            // Bound the query so a slow/unreachable resolver can't stall the status badge.
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            javax.naming.directory.InitialDirContext ctx = new javax.naming.directory.InitialDirContext(env);
            try {
                javax.naming.directory.Attributes attrs =
                        ctx.getAttributes("_minecraft._tcp." + parsed.host(), new String[] {"SRV"});
                javax.naming.directory.Attribute srv = attrs.get("SRV");
                if (srv == null || srv.size() == 0) return parsed;
                String bestTarget = null;
                int bestPort = parsed.port();
                int bestPriority = Integer.MAX_VALUE;
                for (int i = 0; i < srv.size(); i++) {
                    String[] parts = String.valueOf(srv.get(i)).trim().split("\\s+"); // priority weight port target
                    if (parts.length < 4) continue;
                    int priority = Integer.parseInt(parts[0]);
                    int port = Integer.parseInt(parts[2]);
                    String target = parts[3].endsWith(".")
                            ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
                    if (priority < bestPriority) {
                        bestPriority = priority;
                        bestPort = port;
                        bestTarget = target;
                    }
                }
                return bestTarget == null ? parsed : new Address(bestTarget, bestPort);
            } finally {
                try { ctx.close(); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            return parsed;
        }
    }

    /** Probes {@code rawAddress} with {@code timeoutMs} for both connect and read. Never throws. */
    public static Status ping(String rawAddress, int timeoutMs) {
        return withHardTimeout(timeoutMs, () -> {
            Address a = parseAddress(rawAddress);
            if (a.host().isEmpty()) return Status.offline();
            a = resolveSrv(a, hasExplicitPort(rawAddress)); // honor _minecraft._tcp SRV when relevant
            return pingInternal(a.host(), a.port(), timeoutMs);
        });
    }

    /** Same as {@link #ping(String, int)} but retries on a miss before giving up. A single probe can
     *  genuinely miss a server that IS online -- a slow/high-latency link (player-hosted servers
     *  behind a playit.gg tunnel in particular), a cold TCP path on the very first check, or the
     *  best-effort SRV lookup eating into the round trip can each make one attempt time out even
     *  though the exact same server answers fine a moment later. Retrying once before calling a
     *  server OFFLINE is what the background status badge should actually do. */
    public static Status pingWithRetry(String rawAddress, int timeoutMs, int retries) {
        Status last = Status.offline();
        for (int attempt = 0; attempt <= retries; attempt++) {
            last = ping(rawAddress, timeoutMs);
            if (last.online()) return last;
        }
        return last;
    }

    /** Probes {@code host:port}, guarded by the same hard wall-clock cap as {@link #ping(String, int)}
     *  (hostname resolution here has no timeout of its own either). Never throws. */
    public static Status ping(String host, int port, int timeoutMs) {
        return withHardTimeout(timeoutMs, () -> pingInternal(host, port, timeoutMs));
    }

    /** Unguarded probe -- only called from within {@link #withHardTimeout}. Never throws on its own,
     *  but hostname resolution inside the Socket connect can still block without bound, which is
     *  exactly what the wrapping timeout exists to catch. */
    private static Status pingInternal(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(handshakePacket(host, port));
            out.write(statusRequestPacket());
            out.flush();

            int length = readVarInt(in);
            if (length <= 0) return Status.offline();
            int packetId = readVarInt(in);
            if (packetId != 0) return Status.offline();
            int jsonLen = readVarInt(in);
            if (jsonLen <= 0 || jsonLen > 4 * 1024 * 1024) return Status.offline();
            byte[] jsonBytes = new byte[jsonLen];
            int read = 0;
            while (read < jsonLen) {
                int n = in.read(jsonBytes, read, jsonLen - read);
                if (n < 0) throw new EOFException("truncated status json");
                read += n;
            }
            return parseStatusJson(new String(jsonBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Status.offline();
        }
    }

    /** Parses the JSON body of a status response. Package-private for tests; never throws. */
    static Status parseStatusJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int online = 0, max = 0;
            if (root.has("players") && root.get("players").isJsonObject()) {
                JsonObject players = root.getAsJsonObject("players");
                if (players.has("online")) online = players.get("online").getAsInt();
                if (players.has("max")) max = players.get("max").getAsInt();
            }
            String version = (root.has("version") && root.get("version").isJsonObject())
                    ? asString(root.getAsJsonObject("version"), "name") : null;
            String favicon = asString(root, "favicon");
            String motd = stripLegacyColors(motdToString(root.get("description")));
            return new Status(true, Math.max(0, online), Math.max(0, max), motd, version, favicon);
        } catch (Exception e) {
            return Status.offline();
        }
    }

    private static String asString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Flattens a MOTD, which may be a plain string or a chat component (text + extra + arrays). */
    static String motdToString(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement child : el.getAsJsonArray()) sb.append(motdToString(child));
            return sb.toString();
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            StringBuilder sb = new StringBuilder();
            if (o.has("text") && o.get("text").isJsonPrimitive()) sb.append(o.get("text").getAsString());
            if (o.has("extra")) sb.append(motdToString(o.get("extra")));
            return sb.toString();
        }
        return null;
    }

    /** Drops legacy color/format codes so the MOTD is safe to show as plain text. */
    static String stripLegacyColors(String s) {
        if (s == null) return null;
        String cleaned = s.replaceAll("\u00A7.", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    // ---- Wire encoding (package-private so tests can build/verify packets) ----

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & 0xFFFFFF80) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    static void writeString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    static byte[] handshakePacket(String host, int port) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0x00);          // packet id
        writeVarInt(body, -1);            // protocol version: -1 is the conventional status ping value
        writeString(body, host);          // server address (hostname, no port)
        body.write((port >>> 8) & 0xFF);  // port as an unsigned big-endian short
        body.write(port & 0xFF);
        writeVarInt(body, 0x01);          // next state = status
        return frame(body.toByteArray());
    }

    static byte[] statusRequestPacket() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0x00);
        return frame(body.toByteArray());
    }

    private static byte[] frame(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, body.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0, position = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new EOFException("stream ended inside a VarInt");
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) return value;
            position += 7;
            if (position >= 35) throw new IOException("VarInt is too big");
        }
    }
}
