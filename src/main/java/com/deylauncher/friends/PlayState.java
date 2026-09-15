package com.deylauncher.friends;

/**
 * What a DeyLauncher client is actually doing right now, published alongside presence so friends see
 * the truth instead of inferring it from a bare server address.
 *
 * <p>Historically the only thing on the wire was {@code serverAddress}/{@code currentServerName}, and
 * the address was remembered the moment you joined a server *and kept on disk* -- so a client that
 * was sitting in the launcher (or in a single-player world) could still be published as "playing on
 * <server>" forever. The address is now derived from the live game process only (see
 * {@code ServerSessionTracker}), and this enum carries the part of the state that an address alone
 * cannot express.
 *
 * <p>{@link #UNKNOWN} is what a missing/unrecognized {@code playState} value parses to: entries
 * written by older builds, or by a build that had no game process of its own to report. Callers must
 * render it the legacy way (an address, if present, still means "on a server").
 */
public enum PlayState {

    /** Launcher is open, but no game process launched by this install is connected anywhere. */
    IN_LAUNCHER("IN_LAUNCHER"),

    /** A game process is running an integrated (single-player / LAN) world. */
    SINGLE_PLAYER("SINGLE_PLAYER"),

    /** A game process is connected to a multiplayer server ({@code serverAddress} should be set). */
    SERVER("SERVER"),

    /** Not known (older build, no game process, or invisible mode) -- render the legacy way. */
    UNKNOWN(null);

    /** Exact string used on the wire in friends.json; null means "don't publish a state at all". */
    public final String wire;

    PlayState(String wire) {
        this.wire = wire;
    }

    /**
     * Parses a published value. Anything null/blank/unknown (including values a future build might
     * add) maps to {@link #UNKNOWN} rather than throwing, so one odd entry can never break the
     * Friends page.
     */
    public static PlayState fromWire(String wire) {
        if (wire == null || wire.isBlank()) return UNKNOWN;
        for (PlayState s : values()) {
            if (s.wire != null && s.wire.equalsIgnoreCase(wire.trim())) return s;
        }
        return UNKNOWN;
    }
}
