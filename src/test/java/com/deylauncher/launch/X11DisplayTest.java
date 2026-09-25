package com.deylauncher.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The decision table behind "which X display can this game use for Java's AWT/Swing". Every branch that
 * could hand a child process a display that isn't really there is covered on purpose: a login that
 * works today (X11, ssh -X, a pure Wayland session) must not be given a different one, and a socket left
 * behind by a dead X server must never be treated as live.
 */
class X11DisplayTest {

    /** Unix-domain sockets are what X servers listen on; the uid attribute is POSIX-only. */
    private static void assumeUnix() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        assumeTrue(os.contains("linux") || os.contains("mac"), "requires a Unix-like OS");
    }

    /** A really-listening unix socket where an X server would put one: {@code <dir>/X<n>}. */
    private static ServerSocketChannel bind(Path socket) throws Exception {
        Files.createDirectories(socket.getParent());
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        return server;
    }

    /** A socket file with nothing behind it -- exactly what a crashed X server leaves in /tmp/.X11-unix. */
    private static void writeStale(Path socket) throws Exception {
        Files.createDirectories(socket.getParent());
        Files.writeString(socket, "stale");
    }

    @Test
    void aDisplayTheLauncherAlreadyHasIsNeverSecondGuessed(@TempDir Path tmp) {
        // DISPLAY already set (a real X11 login, or ssh -X's "localhost:10.0"): nothing is probed at all.
        assertEquals(":1", X11Display.effectiveDisplay(":1", tmp, 1000L, socket -> {
            fail("the socket probe must not even run when DISPLAY is already set");
            return false;
        }));
        assertEquals("localhost:10.0", X11Display.effectiveDisplay("localhost:10.0", tmp, 1000L, socket -> false));
    }

    @Test
    void onlyADisplayValueAwtCouldUseCounts() {
        assertTrue(X11Display.hasUsableDisplay(":0"));
        assertTrue(X11Display.hasUsableDisplay(":1"));
        assertTrue(X11Display.hasUsableDisplay("localhost:10.0"));
        assertTrue(X11Display.hasUsableDisplay("host.example:0"));
        assertFalse(X11Display.hasUsableDisplay(null));
        assertFalse(X11Display.hasUsableDisplay(""));
        assertFalse(X11Display.hasUsableDisplay("   "));
        // Some CI containers set DISPLAY to a bare host name with nothing listening behind it.
        assertFalse(X11Display.hasUsableDisplay("ci-runner"));
    }

    @Test
    void aLiveSocketIsReportedAsItsDisplay(@TempDir Path tmp) throws Exception {
        assumeUnix();
        try (ServerSocketChannel ignored = bind(tmp.resolve("X0"))) {
            assertTrue(X11Display.isLive(tmp.resolve("X0")), "a bound socket must be seen as live");
            assertEquals(":0", X11Display.firstLiveDisplay(tmp, null, X11Display::isLive));
            assertEquals(":0", X11Display.effectiveDisplay(null, tmp, null, X11Display::isLive));
        }
    }

    @Test
    void aStaleSocketIsSkippedAndTheNextLiveOneIsUsed(@TempDir Path tmp) throws Exception {
        assumeUnix();
        writeStale(tmp.resolve("X0"));
        assertFalse(X11Display.isLive(tmp.resolve("X0")), "nothing is listening on a stale socket file");
        assertFalse(X11Display.isLive(tmp.resolve("X9")), "a socket file that does not exist cannot be live");
        assertFalse(X11Display.isLive(null));

        try (ServerSocketChannel ignored = bind(tmp.resolve("X1"))) {
            assertEquals(":1", X11Display.firstLiveDisplay(tmp, null, X11Display::isLive),
                    "the dead server's leftover must not win over the one that is really running");
        }
    }

    @Test
    void theLowestDisplayNumberWinsAsANumberNotAsText(@TempDir Path tmp) throws Exception {
        writeStale(tmp.resolve("X9"));
        writeStale(tmp.resolve("X10"));
        // Text ordering would pick X10 here; display ordering must pick display 9.
        assertEquals(":9", X11Display.firstLiveDisplay(tmp, null, socket -> true));
    }

    @Test
    void anotherUsersSocketIsNeverUsed(@TempDir Path tmp) throws Exception {
        assumeUnix();
        try (ServerSocketChannel ignored = bind(tmp.resolve("X0"))) {
            Long ours = X11Display.ownerUid(tmp.resolve("X0"));
            assertNotNull(ours, "a POSIX file has a readable owner, which is what the uid check rides on");
            assertEquals(":0", X11Display.firstLiveDisplay(tmp, ours, X11Display::isLive));
            // The socket is live, but it is not this session's: using it would point the game at an X
            // server this user cannot talk to (and drag a working Wayland GLFW launch onto X11).
            assertNull(X11Display.firstLiveDisplay(tmp, ours + 1, X11Display::isLive));
        }
    }

    @Test
    void anUnreadableOwnerFallsBackToTheLivenessProbe(@TempDir Path tmp) throws Exception {
        writeStale(tmp.resolve("X3"));
        // Owner unknown (not POSIX, or unreadable): liveness alone decides, so a live socket is still used.
        assertEquals(":3", X11Display.firstLiveDisplay(tmp, null, path -> true));
    }

    @Test
    void missingSocketDirsAndOddNamesProduceNoDisplay(@TempDir Path tmp) throws Exception {
        assertNull(X11Display.firstLiveDisplay(tmp.resolve("does-not-exist"), 1L, path -> true));
        assertNull(X11Display.firstLiveDisplay(null, 1L, path -> true));
        assertTrue(X11Display.candidateSockets(tmp).isEmpty());
        writeStale(tmp.resolve("X0"));
        writeStale(tmp.resolve("not-a-display"));
        assertEquals(1, X11Display.candidateSockets(tmp).size(), "only X<n> sockets are candidates");
        assertNull(X11Display.effectiveDisplay(null, tmp.resolve("does-not-exist"), 1L, path -> true));
    }

    @Test
    void theChildsEnvironmentIsTheOnlyThingTouched(@TempDir Path tmp) throws Exception {
        assumeUnix();
        try (ServerSocketChannel ignored = bind(tmp.resolve("X0"))) {
            // No DISPLAY on the child -> the discovered one is set, and reported so the launch log can say so.
            Map<String, String> childEnv = new HashMap<>();
            assertEquals(":0", X11Display.applyTo("Linux", childEnv, tmp, null, X11Display::isLive));
            assertEquals(":0", childEnv.get("DISPLAY"));

            // A DISPLAY of its own is left exactly as it is (nothing is guessed, nothing overwritten).
            Map<String, String> ownDisplay = new HashMap<>();
            ownDisplay.put("DISPLAY", ":5");
            assertNull(X11Display.applyTo("Linux", ownDisplay, tmp, null, X11Display::isLive));
            assertEquals(":5", ownDisplay.get("DISPLAY"));

            // Windows is a no-op even with a live socket directory in hand (the JDK rule being fixed here
            // is Unix-only, and AWT never reads DISPLAY there).
            Map<String, String> windows = new HashMap<>();
            assertNull(X11Display.applyTo("Windows 11", windows, tmp, null, X11Display::isLive));
            assertFalse(windows.containsKey("DISPLAY"));

            assertNull(X11Display.applyTo("Linux", null, tmp, null, X11Display::isLive));
        }
    }

    @Test
    void thisSessionsOwnUidIsReadableOnUnix(@TempDir Path tmp) {
        assumeUnix();
        assertEquals(X11Display.currentUid(), X11Display.ownerUid(tmp),
                "the session's uid is what makes another user's X socket distinguishable");
    }

    @Test
    void theXwaylandAuthFileIsFoundWhenTheSessionHasNoXauthority(@TempDir Path tmp) throws Exception {
        assumeUnix();
        Path runtime = Files.createDirectories(tmp.resolve("run"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        // Exactly this setup's shape: KWin starts Xwayland with -auth /run/user/1000/xauth_XXXXXX.
        Path xauth = Files.writeString(runtime.resolve("xauth_htccmJ"), "cookie");
        assertEquals(xauth.toString(), X11Display.xauthorityCandidate(runtime, home));

        // ... and the most recent one wins when an earlier session left another file behind.
        Path newer = Files.writeString(runtime.resolve("xauth_zzzzzz"), "newer");
        Files.setLastModifiedTime(newer, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        assertEquals(newer.toString(), X11Display.xauthorityCandidate(runtime, home));

        // A per-user ~/.Xauthority is libX11's own fallback: leaving XAUTHORITY unset is correct then.
        Files.writeString(home.resolve(".Xauthority"), "cookie");
        assertNull(X11Display.xauthorityCandidate(runtime, home));
        assertNull(X11Display.xauthorityCandidate(tmp.resolve("nope"), tmp.resolve("nope")));
    }

    @Test
    void muttersAuthFileNameInTheHomeDirectoryIsRecognisedToo(@TempDir Path tmp) throws Exception {
        assumeUnix();
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path auth = Files.writeString(home.resolve(".mutter-Xwaylandauth.ABC123"), "cookie");
        assertEquals(auth.toString(), X11Display.xauthorityCandidate(null, home));
    }

    /**
     * A DISPLAY without its cookie is useless (measured: "Authorization required, but no authorization
     * protocol specified" and then AWTError "Can't connect to X11 window server"). A launcher started from
     * a desktop menu is exactly that case: KDE exports DISPLAY in the session environment but keeps
     * Xwayland's auth file private to XDG_RUNTIME_DIR.
     */
    @Test
    void aDisplayWithoutACookieGetsTheServersAuthFile(@TempDir Path tmp) throws Exception {
        assumeUnix();
        Path runtime = Files.createDirectories(tmp.resolve("run"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path xauth = Files.writeString(runtime.resolve("xauth_htccmJ"), "cookie");

        Map<String, String> childEnv = new HashMap<>();
        childEnv.put("DISPLAY", ":0");
        assertEquals(xauth.toString(), X11Display.applyXAuthority("Linux", childEnv, runtime, home));
        assertEquals(xauth.toString(), childEnv.get("XAUTHORITY"));

        // An auth file the child already has is never replaced.
        Map<String, String> own = new HashMap<>();
        own.put("DISPLAY", ":0");
        own.put("XAUTHORITY", "/home/u/my.xauth");
        assertNull(X11Display.applyXAuthority("Linux", own, runtime, home));
        assertEquals("/home/u/my.xauth", own.get("XAUTHORITY"));

        // Without a display there is nothing to authenticate against, and Windows is a no-op.
        Map<String, String> noDisplay = new HashMap<>();
        assertNull(X11Display.applyXAuthority("Linux", noDisplay, runtime, home));
        assertFalse(noDisplay.containsKey("XAUTHORITY"));
        Map<String, String> windows = new HashMap<>();
        windows.put("DISPLAY", ":0");
        assertNull(X11Display.applyXAuthority("Windows 11", windows, runtime, home));
        assertNull(X11Display.applyXAuthority("Linux", null, runtime, home));
    }

    @Test
    void prepareForChildFillsBothGapsAndReportsThem(@TempDir Path tmp) throws Exception {
        assumeUnix();
        Path sockets = Files.createDirectories(tmp.resolve("X11-unix"));
        Path runtime = Files.createDirectories(tmp.resolve("run"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path xauth = Files.writeString(runtime.resolve("xauth_htccmJ"), "cookie");
        try (ServerSocketChannel ignored = bind(sockets.resolve("X0"))) {
            Map<String, String> childEnv = new HashMap<>();
            X11Display.Prepared p = X11Display.prepareForChild("Linux", childEnv, sockets, null,
                    X11Display::isLive, runtime, home);
            assertNotNull(p);
            assertEquals(":0", p.display());
            assertEquals(xauth.toString(), p.xAuthority());
            assertEquals(":0", childEnv.get("DISPLAY"));
            assertEquals(xauth.toString(), childEnv.get("XAUTHORITY"));

            // Nothing to fill in -> nothing reported, and the child's own values stay untouched.
            Map<String, String> ready = new HashMap<>();
            ready.put("DISPLAY", ":0");
            ready.put("XAUTHORITY", "/home/u/my.xauth");
            assertNull(X11Display.prepareForChild("Linux", ready, sockets, null, X11Display::isLive,
                    runtime, home));

            // Windows has none of these concepts.
            Map<String, String> windows = new HashMap<>();
            assertNull(X11Display.prepareForChild("Windows 11", windows, sockets, null, X11Display::isLive,
                    runtime, home));
            assertFalse(windows.containsKey("DISPLAY"));
        }
    }
}
