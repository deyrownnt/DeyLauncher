package com.deylauncher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Decides whether a launch should explicitly ask GLFW for its <b>native Wayland</b> backend instead of
 * going through XWayland.
 *
 * <p>Why this exists (measured, not guessed):
 * <ul>
 *   <li>1.20.x runs on LWJGL 3.3.1, whose bundled {@code libglfw.so} is <b>GLFW 3.4.0 built with both the
 *       Wayland and the X11 backends</b> (its own version string is
 *       {@code "3.4.0 Wayland X11 GLX Null EGL OSMesa monotonic shared"}). GLFW still resolves to
 *       <b>X11</b> whenever {@code DISPLAY} is set -- i.e. through XWayland on a Wayland session.</li>
 *   <li>On such a session the game can die in native code inside {@code glfwCreateWindow}
 *       ({@code # C [libglfw.so+0x2257b]}, exit 134) -- a fault in the window layer itself,
 *       <em>before</em> any GPU work. Forcing Mesa's software renderer does not avoid it: that crash was
 *       reproduced with {@code __GLX_VENDOR_LIBRARY_NAME=mesa + LIBGL_ALWAYS_SOFTWARE=1} (the crash dump
 *       shows {@code libGLX_mesa}/{@code libgallium} loaded and no NVIDIA GLX library at all), at the very
 *       same instruction. The driver is not the variable here; XWayland is.</li>
 *   <li>Starting the very same {@code libglfw.so} with the Wayland backend works: a window is created and
 *       an NVIDIA OpenGL 3.2 context comes up. GLFW only picks Wayland when {@code DISPLAY} is absent, since
 *       it has no environment variable of its own for choosing a platform -- which is why hiding
 *       {@code DISPLAY} is the lever this class pulls.</li>
 * </ul>
 *
 * <p>Safety: only applied to a GLFW build that actually contains the Wayland backend (checked by looking for
 * the {@code glfwGetWaylandDisplay} symbol in the version's own native), only on Linux, only when the
 * launcher itself is running inside a Wayland session with a reachable Wayland socket, and only when the
 * user left the option on. Crucially, {@code DISPLAY} is never removed: current Minecraft versions and
 * Java-based mods may still initialise X11/AWT even while GLFW itself uses Wayland.
 *
 * <p>Minecraft itself does not need X11-only calls to survive the switch: 1.20.1's window code uses
 * {@code glfwCreateWindow}, {@code glfwGetWindowPos}, {@code glfwSetWindowMonitor} and callbacks -- it never
 * calls {@code glfwShowWindow}/{@code glfwFocusWindow}/{@code glfwSetWindowPos}, which are the Wayland
 * backend's unsupported operations.
 */
public final class WaylandSupport {

    private WaylandSupport() {}

    /** The Wayland socket's file name (or absolute path) is taken from here; set by every compositor. */
    private static final String WAYLAND_DISPLAY = "WAYLAND_DISPLAY";

    /** Where the compositor puts its sockets unless {@code WAYLAND_DISPLAY} is an absolute path. */
    private static final String XDG_RUNTIME_DIR = "XDG_RUNTIME_DIR";

    /**
     * Symbol that only exists in a GLFW built with the Wayland backend. GLFW 3.4 publishes it (the game's
     * bundled native has it, as does its printed version string's {@code Wayland} token); a Wayland-less
     * build such as the GLFW 3.3 that older Minecraft versions ship does not.
     */
    static final String WAYLAND_BACKEND_SYMBOL = "glfwGetWaylandDisplay";
    static final String GLFW_PLATFORM = "GLFW_PLATFORM";
    static final String GLFW_WAYLAND_PLATFORM = "wayland";

    /** True when this launcher process is itself running inside a Wayland session. */
    public static boolean inWaylandSession(Map<String, String> env) {
        return env != null && isSet(env.get(WAYLAND_DISPLAY));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Whether the GLFW native in {@code nativesDir} was built with the Wayland backend. Reads the file and
     * looks for {@link #WAYLAND_BACKEND_SYMBOL} (a plain byte search -- no dependency on the platform's
     * {@code strings}/{@code nm} tools). Missing file, or a build without Wayland, both mean "no".
     */
    static boolean glfwHasWaylandBackend(Path nativesDir) {
        if (nativesDir == null) return false;
        // Linux only: the other platforms' GLFW files are named glfw.dll / libglfw.dylib and this whole
        // mechanism is meaningless there anyway (see shouldUseNativeWayland).
        Path glfw = nativesDir.resolve("libglfw.so");
        try {
            if (!Files.isRegularFile(glfw)) return false;
            byte[] bytes = Files.readAllBytes(glfw);
            return new String(bytes, StandardCharsets.ISO_8859_1).contains(WAYLAND_BACKEND_SYMBOL);
        } catch (Exception e) {
            // Unreadable native = nothing we can prove = leave the launch alone.
            return false;
        }
    }

    /**
     * The Wayland socket the compositor is listening on for this session, or null when we cannot tell.
     * {@code WAYLAND_DISPLAY} is normally a bare socket name ({@code wayland-0}) living in
     * {@code XDG_RUNTIME_DIR}; it may also be an absolute path.
     */
    static Path waylandSocket(Map<String, String> env) {
        if (env == null) return null;
        String display = env.get(WAYLAND_DISPLAY);
        if (!isSet(display)) return null;
        if (display.startsWith("/")) return Path.of(display);
        String runtimeDir = env.get(XDG_RUNTIME_DIR);
        if (!isSet(runtimeDir)) return null;
        return Path.of(runtimeDir).resolve(display);
    }

    /**
     * The decision, kept free of any real process so it can be tested: should this launch request native
     * Wayland from GLFW?
     *
     * <p>Requires all of: the option is on, we're on Linux, the launcher is in a Wayland session, that
     * session's socket exists right now, and the version's GLFW was built with the Wayland backend. The
     * last two are what keep this from ever stranding a game with no way to open a window.
     *
     * <p>Public because the UI needs it too: it is what tells the one-shot retry whether switching to
     * Wayland is a move this machine can even make.
     */
    public static boolean shouldUseNativeWayland(boolean enabled, String osName, Map<String, String> env, Path nativesDir) {
        if (!enabled) return false;
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return false;
        if (!inWaylandSession(env)) return false;
        Path socket = waylandSocket(env);
        if (socket == null || !Files.exists(socket)) return false;
        return glfwHasWaylandBackend(nativesDir);
    }

    /**
     * The Maven artifact coordinate every LWJGL release publishes its own version under
     * ({@code org.lwjgl:lwjgl:<version>}, the core module every LWJGL-based Minecraft version JSON lists).
     * Deliberately NOT {@code org.lwjgl:lwjgl-glfw:...} or any other module -- those are only present when
     * the platform's rule matches, while the core artifact is always there and always carries the release
     * version every module in that LWJGL build shares.
     */
    private static final String LWJGL_CORE_PREFIX = "org.lwjgl:lwjgl:";

    /**
     * The oldest LWJGL release whose Wayland GLFW backend is safe to request. LWJGL 3.3.1 -- what
     * Minecraft 1.20.1 bundles -- passes the byte-search in {@link #glfwHasWaylandBackend} (its
     * {@code libglfw.so} does contain {@code glfwGetWaylandDisplay}). However, GLFW 3.4.0 (bundled in
     * LWJGL 3.3.1) has a bug that crashes on window creation on BOTH XWayland and native Wayland
     * ({@code SIGSEGV} in {@code libglfw.so} at {@code glfwCreateWindow}, {@code # C [libglfw.so+0x2257b]}).
     * The first release that fixes this is LWJGL 3.3.2 (bundles GLFW 3.4.1), so we gate native Wayland on
     * 3.3.2+. The version gate uses major.minor.patch to avoid snapshots/EA builds confusing the check.
     */
    private static final int[] MIN_SAFE_LWJGL_FOR_WAYLAND = {3, 3, 2};

    /** The known-buggy LWJGL version that Minecraft 1.20.1 bundles (and modpacks inherit). */
    static final String BUGGY_LWJGL_VERSION = "3.3.1";

    /** The fixed LWJGL version that resolves the GLFW Wayland crash (GLFW 3.4.1). */
    static final String FIXED_LWJGL_VERSION = "3.3.2";

    /**
     * Checks whether the given version JSON uses the buggy LWJGL 3.3.1 release.
     *
     * @param versionJson the merged version JSON (after inheritsFrom resolution)
     * @return true if org.lwjgl:lwjgl:3.3.1 is present in libraries
     */
    public static boolean usesBuggyLwjgl(JsonObject versionJson) {
        if (versionJson == null || !versionJson.has("libraries")) return false;
        String lwjglVersion = lwjglVersion(versionJson);
        return BUGGY_LWJGL_VERSION.equals(lwjglVersion);
    }

    /**
     * Patches a version JSON to upgrade LWJGL from the buggy 3.3.1 to the fixed 3.3.2.
     * This modifies all org.lwjgl:* library entries that declare version 3.3.1.
     *
     * <p>Why this works: Mojang's library mirror (libraries.minecraft.net) hosts LWJGL 3.3.2.
     * By removing the {@code downloads} object, we let {@link GameFiles} fall back to the
     * standard Maven resolver which will fetch the patched version. This is a patch-level
     * upgrade (binary compatible) so it won't break Minecraft/Fabric/mod compatibility.</p>
     *
     * @param versionJson the merged version JSON to patch (modified in place)
     * @return true if any libraries were patched
     */
    public static boolean patchLwjglVersion(JsonObject versionJson) {
        if (!usesBuggyLwjgl(versionJson)) return false;

        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        boolean patched = false;

        for (JsonElement el : libraries) {
            if (!el.isJsonObject()) continue;
            JsonObject lib = el.getAsJsonObject();
            if (!lib.has("name")) continue;

            String name = lib.get("name").getAsString();
            // Match org.lwjgl:* artifacts with version 3.3.1
            if (name.startsWith("org.lwjgl:") && name.contains(":3.3.1")) {
                // Replace version in the Maven coordinate
                String newName = name.replace(":3.3.1", ":" + FIXED_LWJGL_VERSION);
                lib.addProperty("name", newName);
                // Remove downloads so GameFiles re-resolves from Maven with the new version
                if (lib.has("downloads")) {
                    lib.remove("downloads");
                }
                patched = true;
            }
        }

        return patched;
    }

    /**
     * The LWJGL release a version JSON is built on, read from its {@code org.lwjgl:lwjgl:<version>}
     * library entry (present in every LWJGL-based version JSON, vanilla or mod-loader), or {@code null}
     * when the version doesn't declare one (pre-LWJGL3 versions, or a JSON shaped in a way we don't
     * recognize) -- treated as "unknown", never as "safe".
     */
    static String lwjglVersion(JsonObject versionJson) {
        if (versionJson == null || !versionJson.has("libraries")) return null;
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        for (JsonElement el : libraries) {
            if (!el.isJsonObject()) continue;
            JsonObject lib = el.getAsJsonObject();
            if (!lib.has("name")) continue;
            String name = lib.get("name").getAsString();
            if (name.startsWith(LWJGL_CORE_PREFIX)) {
                String[] parts = name.split(":");
                if (parts.length >= 3) return parts[2];
            }
        }
        return null;
    }

    /**
     * Parses a dotted version's first three numeric components, ignoring any trailing qualifier such as
     * {@code -SNAPSHOT} or {@code -beta1} (LWJGL nightly builds add these). Returns {@code null} for
     * anything that doesn't parse cleanly -- callers must treat that the same as "unknown version".
     */
    private static int[] parseVersion(String version) {
        if (version == null) return null;
        String core = version.split("-", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[3];
        try {
            for (int i = 0; i < 3; i++) {
                out[i] = i < parts.length ? Integer.parseInt(parts[i].trim()) : 0;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    /**
     * Whether the LWJGL release named in {@code versionJson} is known-safe for the native Wayland
     * backend (see {@link #MIN_SAFE_LWJGL_FOR_WAYLAND}). LWJGL 3.3.1 (what Minecraft 1.20.1 bundles)
     * is NOT safe for native Wayland -- GLFW 3.4.0 crashes on window creation. LWJGL 3.3.2+ (GLFW 3.4.1+)
     * fixes this. An unparseable or missing version counts as "not safe".
     */
    static boolean isLwjglWaylandSafe(JsonObject versionJson) {
        int[] parsed = parseVersion(lwjglVersion(versionJson));
        return parsed != null && compare(parsed, MIN_SAFE_LWJGL_FOR_WAYLAND) >= 0;
    }

    /**
     * Same decision as {@link #shouldUseNativeWayland(boolean, String, Map, Path)}, plus the LWJGL-version
     * gate above. This is the one launches should actually call. LWJGL 3.3.1 (Minecraft 1.20.1) is NOT
     * safe for native Wayland -- GLFW 3.4.0 crashes. LWJGL 3.3.2+ (GLFW 3.4.1+) fixes this.
     * {@code versionJson} may be null (treated as "not safe").
     */
    public static boolean shouldUseNativeWayland(boolean enabled, String osName, Map<String, String> env,
                                                 Path nativesDir, JsonObject versionJson) {
        return shouldUseNativeWayland(enabled, osName, env, nativesDir) && isLwjglWaylandSafe(versionJson);
    }

    /**
     * Applies the decision to a child process's environment. GLFW 3.4+ honours {@code GLFW_PLATFORM=wayland};
     * unlike deleting DISPLAY, this chooses its Wayland backend without making X11 unavailable to libraries
     * which still need it. Returns true when it changed anything, so the caller can say so in the log.
     */
    static boolean enableNativeWayland(boolean enabled, String osName, Map<String, String> childEnv, Path nativesDir) {
        if (childEnv == null || !shouldUseNativeWayland(enabled, osName, childEnv, nativesDir)) return false;
        childEnv.put(GLFW_PLATFORM, GLFW_WAYLAND_PLATFORM);
        return true;
    }

    /**
     * Version-gated counterpart of {@link #enableNativeWayland(boolean, String, Map, Path)} -- what an
     * actual game launch should call (see {@link #shouldUseNativeWayland(boolean, String, Map, Path,
     * JsonObject)}).
     */
    static boolean enableNativeWayland(boolean enabled, String osName, Map<String, String> childEnv,
                                       Path nativesDir, JsonObject versionJson) {
        if (childEnv == null || !shouldUseNativeWayland(enabled, osName, childEnv, nativesDir, versionJson)) return false;
        childEnv.put(GLFW_PLATFORM, GLFW_WAYLAND_PLATFORM);
        return true;
    }

    /**
     * Replaces the buggy libglfw.so (from LWJGL 3.3.1 / GLFW 3.4.0) with the fixed version
     * from LWJGL 3.3.2 (GLFW 3.4.1) in the given natives directory.
     *
     * <p>This is necessary because Sodium requires LWJGL 3.3.1 at runtime (it checks the version),
     * but GLFW 3.4.0 (bundled in LWJGL 3.3.1) has a bug that crashes in {@code glfwCreateWindow}
     * on both XWayland and native Wayland ({@code SIGSEGV} at {@code libglfw.so+0x2257b}).
     * The fix is to keep the LWJGL version at 3.3.1 but replace the native library with the one
     * from LWJGL 3.3.2+ which bundles GLFW 3.4.1.</p>
     *
     * @param nativesDir the version's natives directory where libglfw.so was extracted
     * @param log optional logger for progress messages
     * @throws IOException if the download or extraction fails
     */
    public static void patchNativeGlfw(Path nativesDir, java.util.function.Consumer<String> log) throws IOException {
        Path targetLib = nativesDir.resolve("libglfw.so");
        if (!Files.exists(targetLib)) {
            if (log != null) log.accept("No libglfw.so found in " + nativesDir + ", skipping patch");
            return; // nothing to patch
        }

        if (log != null) log.accept("Patching libglfw.so in " + nativesDir);

        // Download the fixed native jar from Mojang's library mirror
        // Using LWJGL 3.3.2 which has GLFW 3.4.1 (the fix)
        String fixedVersion = "3.3.2";
        String nativeJarUrl = "https://libraries.minecraft.net/org/lwjgl/lwjgl-glfw/"
                + fixedVersion + "/lwjgl-glfw-" + fixedVersion + "-natives-linux.jar";

        Path tempJar = Files.createTempFile("lwjgl-glfw-", "-natives-linux.jar");
        tempJar.toFile().deleteOnExit();

        try {
            if (log != null) log.accept("Downloading fixed libglfw.so from " + nativeJarUrl);
            var http = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(nativeJarUrl)).GET().build();
            java.net.http.HttpResponse<Path> response;
            try {
                response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(tempJar));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            }
            if (response.statusCode() >= 400) {
                throw new IOException("Failed to download fixed libglfw.so: HTTP " + response.statusCode());
            }

            // Extract libglfw.so from the native jar
            try (var zip = new java.util.zip.ZipFile(tempJar.toFile())) {
                var entry = zip.getEntry("linux/x64/org/lwjgl/glfw/libglfw.so");
                if (entry == null) {
                    // List all entries for debugging
                    StringBuilder entries = new StringBuilder();
                    zip.stream().forEach(e -> entries.append(e.getName()).append(", "));
                    if (log != null) log.accept("Available entries in native jar: " + entries);
                    throw new IOException("libglfw.so not found in fixed native jar. Entries: " + entries);
                }
                if (log != null) log.accept("Extracting libglfw.so (size: " + entry.getSize() + " bytes)");
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, targetLib, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            // Verify the patch worked
            long newSize = Files.size(targetLib);
            if (log != null) log.accept("Patched libglfw.so, new size: " + newSize + " bytes");

            // Make sure it's executable
            targetLib.toFile().setExecutable(true);
        } catch (IOException e) {
            if (log != null) log.accept("Failed to patch libglfw.so: " + e.getMessage());
            throw e;
        }
    }

    // Overload without logger for backward compatibility
    public static void patchNativeGlfw(Path nativesDir) throws IOException {
        patchNativeGlfw(nativesDir, null);
    }
}
