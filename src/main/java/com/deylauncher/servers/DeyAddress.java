package com.deylauncher.servers;

import java.util.Locale;

/**
 * The DEY address scheme: {@code dey|<alias>}, e.g. {@code dey|mySMP}.
 *
 * <p>A DEY address is an <b>indirect</b> server address that only DeyLauncher understands. It never
 * names a host directly -- it names a <i>semi self-hosted server</i>, and whoever is currently
 * hosting that server publishes the real address (typically a free playit.gg tunnel like
 * {@code practicing-downhill.ply.gg}) into the shared servers repo. So the same DEY address keeps
 * working when hosting moves from one moderator's PC to another, which is the whole point: players
 * add {@code dey|mySMP} once and never have to be told a new address.
 *
 * <p>Pure string logic on purpose (no JavaFX, no stores, no network) so every rule here is
 * unit-testable, in the same spirit as {@link com.deylauncher.launch.ServerAddressMatch}. Parsing is
 * deliberately forgiving about surrounding whitespace and about the case of the {@code dey|} prefix,
 * because people paste addresses from chat messages, but the alias itself keeps whatever case the
 * owner chose (aliases are compared case-insensitively).
 */
public final class DeyAddress {

    /** The literal prefix that marks an address as a DEY address. */
    public static final String PREFIX = "dey|";

    /** Longest alias we accept -- long enough for a friendly name, short enough to type. */
    public static final int MAX_ALIAS_LENGTH = 24;

    private DeyAddress() {
    }

    /** True when {@code address} is a DEY address ({@code dey|something}), ignoring case/whitespace. */
    public static boolean isDeyAddress(String address) {
        return aliasOf(address) != null;
    }

    /**
     * The alias inside a DEY address, or null when this isn't one. An address that is exactly
     * {@code dey|} (or only whitespace after it) is NOT a DEY address -- it names no server, and
     * letting it through would make the Add Server dialog accept a value that can never resolve.
     */
    public static String aliasOf(String address) {
        String a = trim(address);
        if (a == null || a.length() <= PREFIX.length()) return null;
        if (!a.toLowerCase(Locale.ROOT).startsWith(PREFIX)) return null;
        String alias = trim(a.substring(PREFIX.length()));
        return alias == null ? null : alias;
    }

    /** Formats an alias as the DEY address a user types or sees. Blank aliases yield null. */
    public static String of(String alias) {
        String a = trim(alias);
        return a == null ? null : PREFIX + a;
    }

    /** The display form used in the UI: the DEY address when known, otherwise null. */
    public static String displayOf(String alias) {
        return of(alias);
    }

    /**
     * Whether {@code alias} may be claimed as a server's DEY address: letters, digits, {@code _} and
     * {@code -} only. A space or a dot is rejected rather than silently stripped, because an alias is
     * also used in repo file paths and in chat, where an ambiguous name is worse than a rejected one.
     */
    public static boolean isValidAlias(String alias) {
        String a = trim(alias);
        if (a == null || a.length() > MAX_ALIAS_LENGTH) return false;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Canonical form for storage and comparison: trimmed and lower-cased. Aliases are matched
     * case-insensitively (so {@code dey|MySMP} and {@code dey|mysmp} are the same server) while the
     * owner's original spelling is what the UI displays.
     */
    public static String normalizeAlias(String alias) {
        String a = trim(alias);
        return a == null ? null : a.toLowerCase(Locale.ROOT);
    }

    /** A short human explanation of the alias rules, shown next to the alias field. */
    public static String rulesHint() {
        return "Letters, numbers, \"_\" and \"-\" only, up to " + MAX_ALIAS_LENGTH
                + " characters -- e.g. dey|" + "mySMP";
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
