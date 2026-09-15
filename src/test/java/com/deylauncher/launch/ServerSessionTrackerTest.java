package com.deylauncher.launch;

import com.deylauncher.friends.PlayState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Every line below is copied verbatim from real 1.21.11 client logs that produced the presence bug
 * being fixed here (see {@code ~/.deylauncher/instances/1.21.11-fabric/logs}), so this test pins the
 * exact wording the game prints for each situation rather than an idealized guess.
 */
class ServerSessionTrackerTest {

    private static final long T0 = 1_000_000L;

    /** Same shape as a real timestamped log line: "[13:40:05] [Render thread/INFO]: Connecting to ...". */
    private static String line(String timestamp, String threadAndLevel, String message) {
        return "[" + timestamp + "] [" + threadAndLevel + "]: " + message;
    }

    private static ServerSessionTracker joined(String username) {
        ServerSessionTracker tracker = new ServerSessionTracker(username, null);
        assertEquals(ServerSessionTracker.Event.JOINED,
                tracker.feed(line("13:40:05", "Render thread/INFO", "Connecting to donutsmp.net, 25565"), T0));
        return tracker;
    }

    @Test
    void connectingPublishesThatServer() {
        ServerSessionTracker tracker = joined("Deyronn");
        assertEquals(PlayState.SERVER, tracker.state());
        assertEquals("donutsmp.net:25565", tracker.address());
    }

    @Test
    void launchingStraightIntoAServerCountsImmediately() {
        ServerSessionTracker tracker = new ServerSessionTracker("Deyronn", "donutsmp.net");
        assertEquals(PlayState.SERVER, tracker.state());
        assertEquals("donutsmp.net", tracker.address());
    }

    @Test
    void leavingThroughThePauseMenuClearsTheServerOnceTheGraceWindowPasses() {
        // Real sequence: the client prints ONE line when you hit Disconnect, then goes silent for as
        // long as you sit in the multiplayer menu (12 minutes in the log this came from).
        ServerSessionTracker tracker = joined("Deyronn");
        assertEquals(ServerSessionTracker.Event.NONE,
                tracker.feed(line("13:40:17", "Render thread/INFO", "Stopping worker threads"), T0 + 1_000));

        assertEquals(ServerSessionTracker.Event.NONE,
                tracker.poll(T0 + 1_000 + ServerSessionTracker.LEAVE_GRACE_MS - 1));
        assertEquals(ServerSessionTracker.Event.LEFT,
                tracker.poll(T0 + 1_000 + ServerSessionTracker.LEAVE_GRACE_MS));
        assertEquals(PlayState.IN_LAUNCHER, tracker.state());
        assertNull(tracker.address());
    }

    @Test
    void proxyRegionHandoffIsNotADisconnect() {
        // Real mcpvp.club sequence: hand-offs arrive seconds apart and must keep presence on the game,
        // not clear it (the old pattern treated "disconnected with reason" as leaving, so it flapped).
        ServerSessionTracker tracker = joined("Deyronn");
        tracker.feed(line("13:20:56", "Render thread/INFO", "Connecting to mcpvp.club, 25565"), T0 + 1_000);
        assertEquals(ServerSessionTracker.Event.NONE,
                tracker.feed(line("13:20:57", "Render thread/WARN",
                        "Client disconnected with reason: Transferring region"), T0 + 2_000));
        assertEquals(ServerSessionTracker.Event.JOINED,
                tracker.feed(line("13:20:57", "Render thread/INFO",
                        "Connecting to eu.mcpvp.club, 25565"), T0 + 2_100));

        // Even much later, the cancelled hand-off must not be re-resolved as a leave.
        assertEquals(ServerSessionTracker.Event.NONE, tracker.poll(T0 + 60_000));
        assertEquals("eu.mcpvp.club:25565", tracker.address());
    }

    @Test
    void workerThreadChurnOnDimensionChangeIsNotADisconnect() {
        // Real sequence on entering a world: the worker pool restarts, which looks like a teardown.
        ServerSessionTracker tracker = joined("Deyronn");
        tracker.feed(line("13:20:59", "Render thread/INFO", "Started 10 worker threads"), T0 + 1_000);
        assertEquals(ServerSessionTracker.Event.NONE,
                tracker.feed(line("13:20:59", "Render thread/INFO", "Stopping worker threads"), T0 + 1_100));
        tracker.feed(line("13:20:59", "Render thread/INFO", "Started 10 worker threads"), T0 + 1_200);
        assertEquals(ServerSessionTracker.Event.NONE, tracker.poll(T0 + 120_000));
        assertEquals("donutsmp.net:25565", tracker.address());
    }

    @Test
    void failedConnectEndsUpBackInTheLauncherState() {
        ServerSessionTracker tracker = joined("Deyronn");
        tracker.feed(line("13:20:43", "Render thread/WARN",
                "Client disconnected with reason: There are no available servers to connect you to. "
                        + "Try again later or contact an admin."), T0 + 1_000);
        assertEquals(ServerSessionTracker.Event.LEFT,
                tracker.poll(T0 + 1_000 + ServerSessionTracker.LEAVE_GRACE_MS));
        assertNull(tracker.address());
        assertEquals(PlayState.IN_LAUNCHER, tracker.state());
    }

    @Test
    void aQuickPlayWhoseConnectionIsRefusedStillClears() {
        // Launching straight into a server (Friends > Join, or a server card's Join) starts the session
        // already on that server. If the connect is then refused, that must still end up clearing --
        // otherwise a failed Join keeps announcing the server until the game process exits, which is
        // exactly how the old on-disk address behaved.
        ServerSessionTracker tracker = new ServerSessionTracker("Deyronn", "donutsmp.net:25565");
        assertEquals(PlayState.SERVER, tracker.state());
        assertEquals(ServerSessionTracker.Event.NONE,
                tracker.feed(line("13:20:43", "Render thread/WARN",
                        "Client disconnected with reason: There are no available servers to connect "
                                + "you to. Try again later or contact an admin."), T0 + 1_000));
        assertEquals(ServerSessionTracker.Event.LEFT,
                tracker.poll(T0 + 1_000 + ServerSessionTracker.LEAVE_GRACE_MS));
        assertNull(tracker.address());
        assertEquals(PlayState.IN_LAUNCHER, tracker.state());
    }

    @Test
    void singlePlayerIsNotAServer() {
        ServerSessionTracker tracker = joined("Deyronn");
        assertEquals(ServerSessionTracker.Event.SINGLE_PLAYER_ENTERED,
                tracker.feed(line("13:38:54", "Server thread/INFO",
                        "Starting integrated minecraft server version 1.21.11"), T0 + 1_000));
        assertEquals(PlayState.SINGLE_PLAYER, tracker.state());
        assertNull(tracker.address()); // opening a world must never keep advertising the server we left

        // A long quiet stretch inside the world still must not look like a server leave.
        assertEquals(ServerSessionTracker.Event.NONE, tracker.poll(T0 + 600_000));
        assertEquals(PlayState.SINGLE_PLAYER, tracker.state());
    }

    @Test
    void closingYourOwnWorldLeavesSinglePlayer() {
        ServerSessionTracker tracker = new ServerSessionTracker("Deyronn", null);
        tracker.feed(line("13:34:50", "Server thread/INFO",
                "Starting integrated minecraft server version 1.21.11"), T0);
        assertEquals(ServerSessionTracker.Event.LEFT,
                tracker.feed(line("13:36:39", "Server thread/INFO",
                        "Deyronn lost connection: Disconnected"), T0 + 60_000));
        assertEquals(PlayState.IN_LAUNCHER, tracker.state());
        assertNull(tracker.address());
    }

    @Test
    void aLanGuestLeavingYourWorldIsNotYouLeaving() {
        ServerSessionTracker tracker = new ServerSessionTracker("Deyronn", null);
        tracker.feed(line("13:34:50", "Server thread/INFO",
                "Starting integrated minecraft server version 1.21.11"), T0);
        assertEquals(ServerSessionTracker.Event.NONE,
                tracker.feed(line("13:36:39", "Server thread/INFO",
                        "Steve123 lost connection: Disconnected"), T0 + 60_000));
        assertEquals(PlayState.SINGLE_PLAYER, tracker.state());
    }

    @Test
    void gameExitEndsTheSession() {
        ServerSessionTracker tracker = joined("Deyronn");
        assertEquals(ServerSessionTracker.Event.LEFT, tracker.processExited());
        assertEquals(PlayState.IN_LAUNCHER, tracker.state());
        assertNull(tracker.address());
        // Nothing left to clear after that, so a second call reports no change.
        assertEquals(ServerSessionTracker.Event.NONE, tracker.processExited());
    }

    @Test
    void unrelatedAndNullLinesAreIgnored() {
        ServerSessionTracker tracker = new ServerSessionTracker("Deyronn", null);
        assertEquals(ServerSessionTracker.Event.NONE, tracker.feed(null, T0));
        assertEquals(ServerSessionTracker.Event.NONE,
                tracker.feed(line("13:40:14", "Render thread/INFO", "[System] [CHAT] <givq> yo"), T0));
        assertEquals(ServerSessionTracker.Event.NONE,
                tracker.feed(line("13:52:34", "Render thread/INFO", "Stopping!"), T0 + 1_000));
        assertEquals(PlayState.IN_LAUNCHER, tracker.state());
    }
}