package com.deylauncher.launch;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the X server a launched game can actually talk to, for the <b>AWT/Swing</b> half of the process.
 *
 * <p>Why this exists (measured on a Wayland Linux session, not guessed):
 * <ul>
 *   <li>Minecraft's own window is GLFW/LWJGL, which talks to the compositor directly -- on this launcher
 *       that path is {@link WaylandSupport}'s job, and it works.</li>
 *   <li>Java's AWT/Swing has <b>no native Wayland backend at all</b>: a Swing window can only be drawn
 *       through X11 -- a real X server, or XWayland under a Wayland session. So a mod that opens its own
 *       window during startup (the modpack case that started this: Early Loading Bar's
 *       {@code PreLaunchWindow}) needs an X display even though the game itself does not.</li>
 *   <li>On Linux/Unix the JDK decides "is this process headless?" from {@code DISPLAY} alone
 *       ({@code GraphicsEnvironment#getHeadlessProperty} consults it only on Unix); with {@code DISPLAY}
 *       unset the whole game process silently flips to headless mode and that mod's window throws
 *       {@code HeadlessException} the moment it is constructed. A Wayland session very often does not
 *       export {@code DISPLAY} at all (a launcher started from a {@code .desktop} entry or a systemd
 *       user unit, as opposed to a terminal inside the session), which is exactly why this only ever hit
 *       the Wayland Linux builds while the same modpack was fine on Windows.</li>
 * </ul>
 *
 * <p>Handing the child an <em>explicit</em> {@code -Djava.awt.headless=false} (see
 * {@link GameLauncher#awtHeadlessMode}) stops the auto-headless decision, but on its own it is not
 * enough: AWT then tries to connect and dies with {@code AWTError: Can't connect to X11 window server}
 * because there is still no display to connect to. So this class supplies the missing half -- the X
 * socket -- and sets {@code DISPLAY} for the child process <em>only</em>.
 *
 * <p>Safety rules, all of which exist to make sure a machine that works today cannot be broken by this:
 * <ul>
 *   <li>Nothing is ever guessed: a display is only returned when a socket in {@code /tmp/.X11-unix}
 *       actually accepts a connection right now.</li>
 *   <li>Only a socket owned by the same user as this session is considered ({@code /tmp/.X11-unix} is
 *       world-readable, so another user's X server is visible there too -- and pointing GLFW at a
 *       foreign {@code DISPLAY} would hijack a game that currently runs fine on Wayland onto X11).</li>
 *   <li>The launcher's own environment is never touched: the caller passes the child's environment
 *       map, which is what {@link ProcessBuilder#environment()} is.</li>
 *   <li>An existing {@code DISPLAY} is never overwritten, so X11 logins and {@code ssh -X}
 *       ({@code localhost:10.0}) behave exactly as before.</li>
 *   <li>Linux-only, like the JDK rule it compensates for: on Windows/macOS this class is a no-op.</li>
 * </ul>
 */
public final class X11Display {

    private X11Display() {}

    /** The variable AWT itself reads on Unix to decide whether a display exists. */
    static final String DISPLAY = "DISPLAY";

    /**
     * The X server's authentication file. Without it a display that exists is still unusable: measured on
     * this setup (KWin/Xwayland started with {@code -auth /run/user/1000/xauth_XXXXXX}), a process with
     * {@code DISPLAY=:0} but no {@code XAUTHORITY} gets {@code AWTError: Can't connect to X11 window
     * server} plus "Authorization required, but no authorization protocol specified" on stderr.
     */
    static final String XAUTHORITY = "XAUTHORITY";

    /** The session/runtime directory the compositor puts Xwayland's auth file in. */
    static final String XDG_RUNTIME_DIR = "XDG_RUNTIME_DIR";

    /**
     * File-name prefixes of the auth files Xwayland's session helpers create: KWin uses
     * {@code xauth_XXXXXX} inside {@code XDG_RUNTIME_DIR}, mutter uses {@code .mutter-Xwaylandauth.XXXXXX}.
     */
    private static final List<String> XAUTH_PREFIXES = List.of("xauth_", ".mutter-Xwaylandauth.");

    /** Where the X server (and XWayland) puts its listening sockets on Linux. */
    static final Path DEFAULT_SOCKET_DIR = Path.of("/tmp/.X11-unix");

    /** One X server's socket file name: {@code X} followed by its display number ({@code X0}, {@code X1}...). */
    private static final Pattern SOCKET_NAME = Pattern.compile("X(\\d+)");

    /**
     * How long a liveness probe may take before the socket counts as dead. A listening X socket accepts
     * immediately, so this only ever elapses for a socket file with nothing behind it -- and it bounds
     * the worst-case cost of a launch, which must never hang on a display probe.
     */
    private static final int CONNECT_TIMEOUT_MS = 150;

    /** True when a value is a display AWT could use: it must name a display ({@code :0}), not a bare host. */
    public static boolean hasUsableDisplay(String display) {
        return display != null && !display.isBlank() && display.contains(":");
    }

    /**
     * The X display this machine can actually reach, for deciding AWT's headless mode and for the child's
     * environment: the launcher's own {@code DISPLAY} when it has one, otherwise the first live X socket
     * belonging to this user. {@code null} means "no X display here" -- a genuine headless situation.
     */
    public static String resolvableDisplay() {
        return effectiveDisplay(System.getenv(DISPLAY), DEFAULT_SOCKET_DIR, currentUid(), X11Display::isLive);
    }

    /**
     * Everything the child process needs so Java's AWT/Swing can actually draw: a {@code DISPLAY} (when
     * it has none and an X server is really listening) and that X server's {@code XAUTHORITY} (when it
     * has none and the auth file can be found) -- see {@link #applyTo} and {@link #applyXAuthority} for
     * why either is useless on its own. The launcher's own environment is never touched.
     *
     * @return what was set (for the launch log), or {@code null} when nothing needed setting
     */
    public static Prepared prepareForChild(String osName, Map<String, String> childEnv) {
        return prepareForChild(osName, childEnv, DEFAULT_SOCKET_DIR, currentUid(), X11Display::isLive,
                runtimeDir(), homeDir());
    }

    /** Testable core of {@link #prepareForChild(String, Map)}: socket dir, owner, probe and dirs supplied. */
    static Prepared prepareForChild(String osName, Map<String, String> childEnv, Path socketDir,
                                    Long expectedUid, Predicate<Path> isLive, Path runtimeDir, Path home) {
        String display = applyTo(osName, childEnv, socketDir, expectedUid, isLive);
        String xAuthority = applyXAuthority(osName, childEnv, runtimeDir, home);
        if (display == null && xAuthority == null) return null;
        return new Prepared(display, xAuthority);
    }

    /** What this class handed to the child: either field may be {@code null} (nothing was set). */
    public record Prepared(String display, String xAuthority) {}

    /**
     * Gives the child process a {@code DISPLAY} when it doesn't have one and a live X socket exists,
     * leaving the launcher's own environment alone. Returns the display that was set, or {@code null}
     * when nothing was changed -- either because the child already has a display (nothing to fix), or
     * because there genuinely is no X server to point it at.
     */
    public static String applyTo(String osName, Map<String, String> childEnv) {
        return applyTo(osName, childEnv, DEFAULT_SOCKET_DIR, currentUid(), X11Display::isLive);
    }

    /**
     * Gives the child process an {@code XAUTHORITY} when it has a display but no auth file of its own.
     * {@code DISPLAY} alone is not enough: the connection is refused without the cookie the X server was
     * started with ("Authorization required, but no authorization protocol specified"), which is exactly
     * what a launcher started from a desktop menu hits -- KWin (and mutter) export {@code DISPLAY} in the
     * session environment but keep the Xwayland auth file private to {@code XDG_RUNTIME_DIR}.
     *
     * <p>Never overwrites an auth file the child already has, and does nothing when {@code ~/.Xauthority}
     * exists, because libX11 falls back to that path on its own.
     */
    static String applyXAuthority(String osName, Map<String, String> childEnv, Path runtimeDir, Path home) {
        if (childEnv == null) return null;
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return null;
        if (!hasUsableDisplay(childEnv.get(DISPLAY))) return null; // nothing to authenticate against
        String existing = childEnv.get(XAUTHORITY);
        if (existing != null && !existing.isBlank()) return null;
        String candidate = xauthorityCandidate(runtimeDir, home);
        if (candidate == null) return null;
        childEnv.put(XAUTHORITY, candidate);
        return candidate;
    }

    /** The auth file Xwayland was started with, or {@code null} when there is none to point at. */
    static String xauthorityCandidate(Path runtimeDir, Path home) {
        // libX11's own fallback already finds a per-user auth file there, so leaving XAUTHORITY unset is
        // the correct (and least surprising) choice in that case.
        if (home != null && Files.isRegularFile(home.resolve(".Xauthority"))) return null;
        for (String prefix : XAUTH_PREFIXES) {
            Path found = newestMatching(runtimeDir, prefix);
            if (found == null && home != null && !home.equals(runtimeDir)) found = newestMatching(home, prefix);
            if (found != null) return found.toString();
        }
        return null;
    }

    /** The most recently written regular file in {@code dir} whose name starts with {@code prefix}. */
    static Path newestMatching(Path dir, String prefix) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        Path best = null;
        FileTime bestTime = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "*")) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) continue;
                FileTime time = Files.getLastModifiedTime(path);
                if (best == null || time.compareTo(bestTime) > 0) {
                    best = path;
                    bestTime = time;
                }
            }
        } catch (IOException ignored) {
            return null; // unreadable directory = nothing we can point at
        }
        return best;
    }

    /** Testable core of {@link #applyTo(String, Map)}: socket directory, expected owner and probe supplied. */
    static String applyTo(String osName, Map<String, String> childEnv, Path socketDir, Long expectedUid,
                          Predicate<Path> isLive) {
        if (childEnv == null) return null;
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        // The "no DISPLAY means headless" rule this compensates for is Unix-only, so this is too.
        if (!os.contains("linux")) return null;
        if (hasUsableDisplay(childEnv.get(DISPLAY))) return null;
        String display = firstLiveDisplay(socketDir, expectedUid, isLive);
        if (display == null) return null;
        childEnv.put(DISPLAY, display);
        return display;
    }

    /** Testable core of {@link #resolvableDisplay()}. */
    static String effectiveDisplay(String currentDisplay, Path socketDir, Long expectedUid,
                                   Predicate<Path> isLive) {
        if (hasUsableDisplay(currentDisplay)) return currentDisplay;
        return firstLiveDisplay(socketDir, expectedUid, isLive);
    }

    /**
     * The lowest-numbered {@code X<n>} socket in {@code socketDir} that belongs to {@code expectedUid} and
     * accepts a connection right now, as {@code ":n"} -- or {@code null} when there is none. Lowest first,
     * because that is the session's own server on every normal setup; a stale socket left behind by a
     * crashed server is skipped by the liveness probe instead of being handed to the game.
     */
    static String firstLiveDisplay(Path socketDir, Long expectedUid, Predicate<Path> isLive) {
        List<Path> candidates;
        try {
            candidates = candidateSockets(socketDir);
        } catch (IOException e) {
            return null;
        }
        for (Path socket : candidates) {
            if (expectedUid != null) {
                Long owner = ownerUid(socket);
                // Only the same user's server can be this session's display. An owner we cannot read is
                // not evidence of anything, so that case still goes through to the liveness probe.
                if (owner != null && !owner.equals(expectedUid)) continue;
            }
            if (isLive != null && !isLive.test(socket)) continue;
            return ":" + displayNumberOf(socket);
        }
        return null;
    }

    /** Every {@code X<n>} socket in {@code socketDir}, lowest display number first (digits, not text). */
    static List<Path> candidateSockets(Path socketDir) throws IOException {
        List<Path> found = new ArrayList<>();
        if (socketDir == null || !Files.isDirectory(socketDir)) return found;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(socketDir)) {
            for (Path path : stream) {
                if (SOCKET_NAME.matcher(path.getFileName().toString()).matches()) found.add(path);
            }
        }
        found.sort(Comparator.comparingInt(X11Display::displayNumberOf));
        return found;
    }

    /** The display number of an {@code X<n>} socket file, or {@code MAX_VALUE} when it isn't one. */
    static int displayNumberOf(Path socket) {
        if (socket == null || socket.getFileName() == null) return Integer.MAX_VALUE;
        Matcher m = SOCKET_NAME.matcher(socket.getFileName().toString());
        if (!m.matches()) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Whether an X socket is really being served: a bounded, non-blocking {@code connect} to it. A socket
     * file whose server is gone refuses the connection, and one whose server is alive accepts at once --
     * which is the difference between "XWayland is running" and "XWayland died and left a file behind".
     */
    static boolean isLive(Path socket) {
        if (socket == null) return false;
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.configureBlocking(false);
            if (channel.connect(UnixDomainSocketAddress.of(socket))) return true;
            try (Selector selector = Selector.open()) {
                channel.register(selector, SelectionKey.OP_CONNECT);
                if (selector.select(CONNECT_TIMEOUT_MS) == 0) return false;
                return channel.finishConnect();
            }
        } catch (Exception e) {
            // Stale/unreachable/permission-denied sockets are simply not displays we can claim exist.
            return false;
        }
    }

    /** The numeric owner of a file, or {@code null} when it can't be read (non-POSIX, missing, denied). */
    static Long ownerUid(Path path) {
        if (path == null) return null;
        try {
            Object uid = Files.getAttribute(path, "unix:uid");
            if (uid instanceof Integer i) return i.longValue();
        } catch (Exception ignored) {
            // Unreadable owner = unknown, and the caller falls back to the liveness probe alone.
        }
        return null;
    }

    /**
     * This user's numeric uid, read from the owner of {@code XDG_RUNTIME_DIR} (the Wayland/session dir,
     * always private to the logged-in user) and falling back to {@code /proc/self}.
     */
    static Long currentUid() {
        Path runtimeDir = runtimeDir();
        Long fromRuntimeDir = runtimeDir == null ? null : ownerUid(runtimeDir);
        return fromRuntimeDir != null ? fromRuntimeDir : ownerUid(Path.of("/proc/self"));
    }

    /** {@code XDG_RUNTIME_DIR} as a path, or {@code null} when this process has none. */
    static Path runtimeDir() {
        String value = System.getenv(XDG_RUNTIME_DIR);
        return (value == null || value.isBlank()) ? null : Path.of(value);
    }

    /** The user's home directory, or {@code null} when the JVM doesn't report one. */
    static Path homeDir() {
        String value = System.getProperty("user.home", "");
        return value.isBlank() ? null : Path.of(value);
    }
}