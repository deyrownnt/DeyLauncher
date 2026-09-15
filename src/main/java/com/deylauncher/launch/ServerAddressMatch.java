package com.deylauncher.launch;

import java.util.Locale;

/**
 * Compares a live "host:port" address against the addresses a user has saved locally (bookmarked
 * "Added Servers", or their own hosted servers' {@code localhost:<port>} forms), so the launcher can
 * show the friendly name a user gave a server instead of a bare address.
 *
 * <p>Pure logic on purpose (no JavaFX, no stores) so the matching rules are unit-testable -- this is
 * what decides whether "the server my friend is on" is recognised as a server I already have a name
 * for.
 *
 * <p>Matching order matters, which is why this returns a {@link #score(String, String) score} rather
 * than a boolean:
 * <ul>
 *   <li><b>2</b> -- same host AND same explicit port (or identical strings).</li>
 *   <li><b>1</b> -- same host, and at least one side has no explicit port, so the default port is
 *       implied (a bookmark saved as {@code play.example.com} matches the live
 *       {@code play.example.com:25565}).</li>
 *   <li><b>0</b> -- not the same server.</li>
 * </ul>
 * Crucially, two entries on the same host with <i>different explicit ports</i> are NOT the same
 * server (score 0). Comparing hosts alone used to make a friend on {@code eu.mcpvp.club:25566} show
 * up under whichever same-host bookmark happened to be first in the list -- the "it always says I'm
 * playing the first server I added" half of the presence bug.
 */
public final class ServerAddressMatch {

    /** Highest score: exact same endpoint (host and explicit port both equal). */
    public static final int SCORE_EXACT = 2;
    /** Same host, default port implied because at least one side omitted it. */
    public static final int SCORE_HOST_ONLY = 1;
    /** Different endpoints. */
    public static final int SCORE_NONE = 0;

    private ServerAddressMatch() {
    }

    /** True when the two addresses refer to the same server (any score above zero). */
    public static boolean matches(String candidate, String address) {
        return score(candidate, address) > 0;
    }

    /** Same host and same explicit port (or literally the same string). */
    public static boolean matchesExact(String candidate, String address) {
        return score(candidate, address) == SCORE_EXACT;
    }

    /** Whether this address spells out a port ("host:25565"), as opposed to relying on the default. */
    public static boolean hasExplicitPort(String address) {
        String host = hostOf(address);
        return host != null && !host.equalsIgnoreCase(trim(address));
    }

    /**
     * How well {@code candidate} (a saved address) matches {@code address} (the live one being
     * resolved). See the class doc for what each score means.
     */
    public static int score(String candidate, String address) {
        String c = trim(candidate);
        String a = trim(address);
        if (c == null || a == null) return SCORE_NONE;
        if (c.equalsIgnoreCase(a)) return SCORE_EXACT;

        String ch = hostOf(c);
        String ah = hostOf(a);
        if (ch == null || ah == null || !ch.equalsIgnoreCase(ah)) return SCORE_NONE;

        String cp = portOf(c);
        String ap = portOf(a);
        if (cp != null && ap != null) {
            return cp.equals(ap) ? SCORE_EXACT : SCORE_NONE; // same host, different ports = different server
        }
        return SCORE_HOST_ONLY; // one side omitted the port, so the default port is implied
    }

    /** The host part of "host" or "host:port" (null for null/blank input). */
    public static String hostOf(String address) {
        String a = trim(address);
        if (a == null) return null;
        if (a.startsWith("[")) { // bracketed IPv6 literal, optionally followed by ":port"
            int end = a.indexOf(']');
            return end > 0 ? a.substring(0, end + 1) : a;
        }
        // A bare IPv6 literal ("::1") has several colons and carries no port -- never chop it up.
        if (a.indexOf(':') != a.lastIndexOf(':')) return a;
        int colon = a.lastIndexOf(':');
        // Only treat the colon as a port separator when what follows is a numeric port.
        if (colon > 0 && isNumericPort(a.substring(colon + 1))) return a.substring(0, colon).trim();
        return a;
    }

    /** The explicit port of "host:port" (or "[::1]:port"), or null when the address has none. */
    public static String portOf(String address) {
        String a = trim(address);
        if (a == null) return null;
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            if (end > 0 && end + 1 < a.length() && a.charAt(end + 1) == ':') {
                String port = a.substring(end + 2).trim();
                return isNumericPort(port) ? port : null;
            }
            return null;
        }
        if (a.indexOf(':') != a.lastIndexOf(':')) return null; // bare IPv6 literal -- no port
        int colon = a.lastIndexOf(':');
        if (colon <= 0) return null;
        String port = a.substring(colon + 1).trim();
        return isNumericPort(port) ? port : null;
    }

    /** True for a loopback address or an empty host -- i.e. a server no friend could reach remotely. */
    public static boolean isLoopback(String address) {
        String host = hostOf(address);
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    }

    /**
     * True when a friend could actually reach {@code address}: not a loopback address, and not this
     * machine's own LAN address. {@code localHostHint} is this machine's LAN IP (see
     * LauncherApp#localIpAddress), or null/blank when it isn't known -- the loopback rule still
     * applies then.
     *
     * <p>This decides whether a server we just joined is worth publishing to friends: joining our OWN
     * locally-hosted server still counts as being on a server, but publishing {@code localhost} (or our
     * own LAN IP) would only ever hand a friend a dead Join button.
     */
    public static boolean isShareable(String address, String localHostHint) {
        String a = trim(address);
        if (a == null || isLoopback(a)) return false;
        String local = trim(localHostHint);
        String host = hostOf(a);
        return !(local != null && host != null && local.equalsIgnoreCase(host));
    }

    private static boolean isNumericPort(String s) {
        if (s == null || s.isEmpty() || s.length() > 5) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
