package com.deylauncher.launch;

import com.deylauncher.friends.PlayState;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one launched Minecraft client's log lines into the live play state DeyLauncher publishes to
 * friends. Pure logic (no JavaFX, no I/O, no clock of its own) so every rule below is unit-testable
 * against the exact log lines the game really prints.
 *
 * <p>Why this exists: the launcher used to guess presence from two regexes on the game's output plus
 * an address remembered on disk. Verified against real 1.21.11 client logs (see
 * {@code ~/.deylauncher/instances/*&#47;logs}), that mismatched the game in three ways:
 *
 * <ul>
 *   <li><b>Leaving a multiplayer server prints no "disconnect" line at all</b> -- only
 *       {@code [Render thread/INFO]: Stopping worker threads}. So the old pattern never fired and
 *       presence stayed on the last server until the whole game process exited.</li>
 *   <li><b>Proxy hand-offs are not disconnects.</b> A network like {@code mcpvp.club} moves you
 *       between regions with {@code Client disconnected with reason: Transferring region} followed
 *       (usually within a second) by another {@code Connecting to ...} line. The old pattern treated
 *       that as "left the server", so presence flapped several times a minute.</li>
 *   <li><b>Single player is not a server.</b> Leaving an integrated (single-player/LAN) world logs
 *       {@code [Server thread/INFO]: <you> lost connection: Disconnected}, which the old pattern also
 *       matched -- so a single-player session could clear/claim presence like a multiplayer one.</li>
 * </ul>
 *
 * <p>Timeout resolution happens in {@link #poll(long)} (driven by the launcher's light presence
 * ticker while the game process is alive), because after a real disconnect the client can go
 * completely silent -- in the logs that produced this fix, the client printed
 * {@code Stopping worker threads} and then nothing for twelve minutes while the player sat in the
 * multiplayer menu.
 */
public final class ServerSessionTracker {

    /** What {@link #feed}/{@link #poll}/{@link #processExited} report about the session. */
    public enum Event {
        /** Nothing changed for friends. */
        NONE,
        /** Connected to a multiplayer server -- see {@link #address()}. */
        JOINED,
        /** No longer on a server (disconnected, kicked, hand-off lost, or the game exited). */
        LEFT,
        /** Now inside an integrated (single-player/LAN) world. */
        SINGLE_PLAYER_ENTERED
    }

    /**
     * How long a pending leave is allowed to stay unresolved before it counts as a real disconnect.
     * Comfortably longer than a region hand-off (which lands in well under a second in the logs) and
     * short enough that friends stop seeing "playing on ..." within a few seconds of you leaving.
     */
    public static final long LEAVE_GRACE_MS = 4_000L;

    /** "Connecting to mc.example.com, 25565" -- printed by the client on every connection attempt. */
    private static final Pattern CONNECTING =
            Pattern.compile("Connecting to ([^,]+),\\s*(\\d+)");

    /** Disconnect-with-reason lines: plain kicks, failed connects, and proxy region transfers. */
    private static final Pattern DISCONNECT_REASON =
            Pattern.compile("(?:client\\s+)?disconnected with reason", Pattern.CASE_INSENSITIVE);

    /** Printed whenever the client tears down a connection -- including on a real menu "Disconnect". */
    private static final Pattern WORKER_THREADS_STOPPED =
            Pattern.compile("Stopping worker threads", Pattern.CASE_INSENSITIVE);

    /** The worker pool coming back up: fires on dimension changes too, so it cancels a pending leave. */
    private static final Pattern WORKER_THREADS_STARTED =
            Pattern.compile("Started \\d+ worker threads", Pattern.CASE_INSENSITIVE);

    /** "Starting integrated minecraft server version 1.21.11" -- we're in a local world now. */
    private static final Pattern INTEGRATED_SERVER_STARTED =
            Pattern.compile("Starting integrated minecraft server", Pattern.CASE_INSENSITIVE);

    private final Pattern integratedLeave;

    private PlayState state = PlayState.IN_LAUNCHER;
    private String address;
    private long pendingLeaveAtMs = -1L;

    public ServerSessionTracker() {
        this(null, null);
    }

    /**
     * @param localUsername  the username this game process is signed in as, so a LAN guest leaving
     *                       your world can never be mistaken for you leaving it (null = match any name)
     * @param initialTarget  the "host:port" the launcher is launching straight into (Friends &gt; Join
     *                       or a server card's Join button), or null for a normal launch; it counts as
     *                       the current server until the game's own log says otherwise
     */
    public ServerSessionTracker(String localUsername, String initialTarget) {
        this.integratedLeave = Pattern.compile(
                "\\[Server thread[^\\]]*\\]\\s*:\\s*"
                        + (localUsername == null || localUsername.isBlank()
                                ? "[^\\s]+" : Pattern.quote(localUsername))
                        + "\\s+lost connection",
                Pattern.CASE_INSENSITIVE);
        if (initialTarget != null && !initialTarget.isBlank()) {
            this.address = initialTarget.trim();
            this.state = PlayState.SERVER;
        }
    }

    /** What friends should see for this session right now. */
    public PlayState state() {
        return state;
    }

    /** The current multiplayer server address, or null when not on one. */
    public String address() {
        return state == PlayState.SERVER ? address : null;
    }

    /**
     * Feeds one line of the game's own console output. {@code nowMs} is passed in (rather than read
     * from the system clock here) so the timing rules can be tested deterministically.
     */
    public Event feed(String line, long nowMs) {
        if (line == null) return Event.NONE;

        // A connection attempt wins over everything else -- including over a pending leave, which is
        // exactly how a proxy region hand-off ("disconnected with reason: Transferring region" then
        // "Connecting to <other region>") stops counting as "you left".
        Matcher join = CONNECTING.matcher(line);
        if (join.find()) {
            pendingLeaveAtMs = -1L;
            address = join.group(1).trim() + ":" + join.group(2).trim();
            state = PlayState.SERVER;
            return Event.JOINED;
        }

        // A single-player/LAN world starting: local play, so nothing about it is advertised as a server.
        if (INTEGRATED_SERVER_STARTED.matcher(line).find()) {
            pendingLeaveAtMs = -1L;
            address = null;
            state = PlayState.SINGLE_PLAYER;
            return Event.SINGLE_PLAYER_ENTERED;
        }

        // YOU leaving your own integrated world (a LAN guest leaving prints their own name instead).
        if (state == PlayState.SINGLE_PLAYER && integratedLeave.matcher(line).find()) {
            pendingLeaveAtMs = -1L;
            address = null;
            state = PlayState.IN_LAUNCHER;
            return Event.LEFT;
        }

        // The worker pool coming back up means the client is still in a world (it churns on dimension
        // changes), so it cancels a pending leave rather than confirming one.
        if (WORKER_THREADS_STARTED.matcher(line).find()) {
            pendingLeaveAtMs = -1L;
            return Event.NONE;
        }

        if (DISCONNECT_REASON.matcher(line).find() || WORKER_THREADS_STOPPED.matcher(line).find()) {
            if (state == PlayState.SERVER) pendingLeaveAtMs = nowMs; // pending until proven otherwise
            return Event.NONE;
        }

        // Any other line simply gives us a chance to notice that a pending leave has gone unresolved
        // for long enough to be real (a busy server prints chat constantly, so this fires promptly).
        return poll(nowMs);
    }

    /**
     * Resolves a pending leave whose grace window has expired. Called from the launcher's presence
     * ticker while the game process runs, so a quiet client sitting in the multiplayer menu still
     * stops advertising the server it left.
     */
    public Event poll(long nowMs) {
        if (pendingLeaveAtMs >= 0 && state == PlayState.SERVER
                && nowMs - pendingLeaveAtMs >= LEAVE_GRACE_MS) {
            pendingLeaveAtMs = -1L;
            address = null;
            state = PlayState.IN_LAUNCHER;
            return Event.LEFT;
        }
        return Event.NONE;
    }

    /**
     * The game process is gone, so whatever it was doing is over. Returns {@link Event#LEFT} when a
     * server still had to be cleared (so the caller republishes), {@link Event#NONE} otherwise.
     */
    public Event processExited() {
        pendingLeaveAtMs = -1L;
        boolean wasOnServer = state == PlayState.SERVER;
        address = null;
        state = PlayState.IN_LAUNCHER;
        return wasOnServer ? Event.LEFT : Event.NONE;
    }
}