package com.deylauncher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision table behind "start the game natively on Wayland instead of XWayland". Every branch that
 * could choose an unavailable backend is covered here on purpose: the launch must only request Wayland
 * when a Wayland socket really exists AND the GLFW native really has a Wayland backend.
 */
class WaylandSupportTest {

    /** A live Wayland session with XWayland available -- the exact shape of the machine that crashes. */
    private static Map<String, String> waylandSession(Path runtimeDir) {
        Map<String, String> env = new HashMap<>();
        env.put("WAYLAND_DISPLAY", "wayland-0");
        env.put("XDG_RUNTIME_DIR", runtimeDir.toString());
        env.put("DISPLAY", ":0");
        return env;
    }

    /** A fake natives dir whose libglfw.so carries the Wayland marker (like LWJGL 3.3.1's GLFW 3.4). */
    private static Path waylandCapableNatives(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve("libglfw.so"),
                ("\u007fELF3.4.0 Wayland X11 GLX Null EGL OSMesa monotonic shared "
                        + WaylandSupport.WAYLAND_BACKEND_SYMBOL).getBytes(StandardCharsets.ISO_8859_1));
        return dir;
    }

    /** A fake natives dir shaped like an X11-only GLFW (what Minecraft's older LWJGL versions ship). */
    private static Path x11OnlyNatives(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve("libglfw.so"),
                "\u007fELF3.3.6 X11 GLX EGL OSMesa clock_gettime monotonic shared"
                        .getBytes(StandardCharsets.ISO_8859_1));
        return dir;
    }

    @Test
    void waylandSessionIsRecognizedByItsWaylandDisplay() {
        assertTrue(WaylandSupport.inWaylandSession(Map.of("WAYLAND_DISPLAY", "wayland-0")));
        // A plain X11 login sets DISPLAY only -- no Wayland, nothing to switch to.
        assertFalse(WaylandSupport.inWaylandSession(Map.of("DISPLAY", ":0")));
        assertFalse(WaylandSupport.inWaylandSession(Map.of("WAYLAND_DISPLAY", "  ")));
        assertFalse(WaylandSupport.inWaylandSession(null));
    }

    @Test
    void socketIsResolvedRelativeToTheRuntimeDirUnlessItIsAbsolute() {
        assertEquals(Path.of("/run/user/1000/wayland-0"),
                WaylandSupport.waylandSocket(Map.of("WAYLAND_DISPLAY", "wayland-0",
                        "XDG_RUNTIME_DIR", "/run/user/1000")));
        assertEquals(Path.of("/tmp/sock"),
                WaylandSupport.waylandSocket(Map.of("WAYLAND_DISPLAY", "/tmp/sock")));
        assertNull(WaylandSupport.waylandSocket(Map.of("WAYLAND_DISPLAY", "wayland-0")));
        assertNull(WaylandSupport.waylandSocket(Map.of("XDG_RUNTIME_DIR", "/run/user/1000")));
    }

    @Test
    void glfwWithAwaylandBackendIsDetectedAndWithoutOneIsNot(@TempDir Path tmp) throws Exception {
        assertTrue(WaylandSupport.glfwHasWaylandBackend(waylandCapableNatives(tmp.resolve("wl"))));
        assertFalse(WaylandSupport.glfwHasWaylandBackend(x11OnlyNatives(tmp.resolve("x11"))));
        // Nothing downloaded yet (or an unreadable native) must mean "don't touch the launch".
        assertFalse(WaylandSupport.glfwHasWaylandBackend(tmp.resolve("missing")));
        assertFalse(WaylandSupport.glfwHasWaylandBackend(null));
    }

    @Test
    void nativeWaylandIsRequestedWithoutRemovingDisplay(@TempDir Path tmp) throws Exception {
        Path runtime = Files.createDirectories(tmp.resolve("run"));
        Files.createFile(runtime.resolve("wayland-0"));
        Map<String, String> env = waylandSession(runtime);
        Path natives = waylandCapableNatives(tmp.resolve("natives"));

        assertTrue(WaylandSupport.enableNativeWayland(true, "Linux", env, natives));
        assertEquals(":0", env.get("DISPLAY"), "DISPLAY must remain for AWT and X11-using mods");
        assertEquals("wayland", env.get("GLFW_PLATFORM"));
        assertTrue(env.containsKey("WAYLAND_DISPLAY"), "the Wayland socket name must survive untouched");
    }

    @Test
    void anX11SessionKeepsItsDisplay(@TempDir Path tmp) throws Exception {
        Path natives = waylandCapableNatives(tmp.resolve("natives"));
        Map<String, String> env = new HashMap<>();
        env.put("DISPLAY", ":0");

        assertFalse(WaylandSupport.enableNativeWayland(true, "Linux", env, natives));
        assertEquals(":0", env.get("DISPLAY"));
    }

    @Test
    void aVanishedWaylandSocketKeepsTheXWaylandLaunch(@TempDir Path tmp) throws Exception {
        // Wayland advertised in the environment but the compositor's socket is gone: switching the game to
        // Wayland here would leave it with no display at all, so the launch must stay on XWayland.
        Path runtime = Files.createDirectories(tmp.resolve("run"));
        Path natives = waylandCapableNatives(tmp.resolve("natives"));
        Map<String, String> env = waylandSession(runtime);

        assertFalse(WaylandSupport.shouldUseNativeWayland(true, "Linux", env, natives));
        assertFalse(WaylandSupport.enableNativeWayland(true, "Linux", env, natives));
        assertEquals(":0", env.get("DISPLAY"));
    }

    @Test
    void oldGlfwWithoutAwaylandBackendIsNeverSwitched(@TempDir Path tmp) throws Exception {
        Path runtime = Files.createDirectories(tmp.resolve("run"));
        Files.createFile(runtime.resolve("wayland-0"));
        Map<String, String> env = waylandSession(runtime);

        assertFalse(WaylandSupport.enableNativeWayland(true, "Linux", env, x11OnlyNatives(tmp.resolve("natives"))));
        assertEquals(":0", env.get("DISPLAY"));
    }

    /** A version JSON whose libraries declare a given LWJGL core version, the way every real one does. */
    private static JsonObject versionJsonWithLwjgl(String lwjglVersion) {
        JsonObject version = new JsonObject();
        JsonArray libraries = new JsonArray();
        JsonObject lwjgl = new JsonObject();
        lwjgl.addProperty("name", "org.lwjgl:lwjgl:" + lwjglVersion);
        libraries.add(lwjgl);
        JsonObject glfw = new JsonObject();
        glfw.addProperty("name", "org.lwjgl:lwjgl-glfw:" + lwjglVersion);
        libraries.add(glfw);
        version.add("libraries", libraries);
        return version;
    }

    @Test
    void lwjglVersionIsReadFromTheCoreLibraryEntry() {
        assertEquals("3.3.1", WaylandSupport.lwjglVersion(versionJsonWithLwjgl("3.3.1")));
        assertEquals("3.4.1-snapshot", WaylandSupport.lwjglVersion(versionJsonWithLwjgl("3.4.1-snapshot")));
        assertNull(WaylandSupport.lwjglVersion(null));
        assertNull(WaylandSupport.lwjglVersion(new JsonObject()));
    }

    @Test
    void lwjgl331IsNotSafeForNativeWayland_patchingRequired(@TempDir Path tmp) throws Exception {
        // LWJGL 3.3.1 (Minecraft 1.20.1) is NOT safe for native Wayland -- GLFW 3.4.0 crashes on
        // window creation on BOTH XWayland and native Wayland. The fix is to upgrade to LWJGL 3.3.2+
        // (GLFW 3.4.1+). This test verifies the version gate correctly rejects 3.3.1.
        Path runtime = Files.createDirectories(tmp.resolve("run"));
        Files.createFile(runtime.resolve("wayland-0"));
        Map<String, String> env = waylandSession(runtime);
        Path natives = waylandCapableNatives(tmp.resolve("natives"));

        // LWJGL 3.3.1 should be rejected (not safe for native Wayland)
        assertFalse(WaylandSupport.shouldUseNativeWayland(true, "Linux", env, natives, versionJsonWithLwjgl("3.3.1")),
                "LWJGL 3.3.1's native Wayland backend crashes; must upgrade to 3.3.2+");
        assertTrue(WaylandSupport.shouldUseNativeWayland(true, "Linux", env, natives, versionJsonWithLwjgl("3.4.1-snapshot")),
                "LWJGL 3.4.1 is the known-good build this class was originally built around");
        // Exactly at the safe floor
        assertTrue(WaylandSupport.shouldUseNativeWayland(true, "Linux", env, natives, versionJsonWithLwjgl("3.3.2")));
        // 3.3.1 is rejected
        assertFalse(WaylandSupport.shouldUseNativeWayland(true, "Linux", env, natives, versionJsonWithLwjgl("3.3.1")));
        // A version JSON with no LWJGL declared at all (or none passed) must never be assumed safe.
        assertFalse(WaylandSupport.shouldUseNativeWayland(true, "Linux", env, natives, new JsonObject()));
        assertFalse(WaylandSupport.shouldUseNativeWayland(true, "Linux", env, natives, null));

        // enableNativeWayland should also reject 3.3.1
        assertFalse(WaylandSupport.enableNativeWayland(true, "Linux", env, natives, versionJsonWithLwjgl("3.3.1")));
        assertEquals(":0", env.get("DISPLAY"));
        assertFalse(env.containsKey("GLFW_PLATFORM"));

        Map<String, String> env2 = waylandSession(runtime);
        assertTrue(WaylandSupport.enableNativeWayland(true, "Linux", env2, natives, versionJsonWithLwjgl("3.4.1-snapshot")));
        assertEquals("wayland", env2.get("GLFW_PLATFORM"));
    }

    @Test
    void windowsAndTheUntickedOptionAlwaysKeepTheEnvironment(@TempDir Path tmp) throws Exception {
        Path runtime = Files.createDirectories(tmp.resolve("run"));
        Files.createFile(runtime.resolve("wayland-0"));
        Path natives = waylandCapableNatives(tmp.resolve("natives"));

        Map<String, String> windows = waylandSession(runtime);
        windows.put("WAYLAND_DISPLAY", "");
        assertFalse(WaylandSupport.enableNativeWayland(true, "Windows 11", windows, natives));

        Map<String, String> optedOut = waylandSession(runtime);
        assertFalse(WaylandSupport.enableNativeWayland(false, "Linux", optedOut, natives));
        assertEquals(":0", optedOut.get("DISPLAY"));
    }

    @Test
    void lwjglVersionPrefersCoreLibraryOverModules() {
        // When both org.lwjgl:lwjgl and org.lwjgl:lwjgl-glfw are present,
        // the core library (org.lwjgl:lwjgl) should be used for version detection
        JsonObject version = new JsonObject();
        JsonArray libraries = new JsonArray();
        JsonObject core = new JsonObject();
        core.addProperty("name", "org.lwjgl:lwjgl:3.3.1");
        JsonObject glfw = new JsonObject();
        glfw.addProperty("name", "org.lwjgl:lwjgl-glfw:3.4.1");
        libraries.add(glfw);
        libraries.add(core);
        version.add("libraries", libraries);

        assertEquals("3.3.1", WaylandSupport.lwjglVersion(version));
    }

    @Test
    void patchNativeGlfwDoesNothingWhenLibglfwMissing(@TempDir Path tmp) throws Exception {
        // If libglfw.so doesn't exist in the natives directory, patchNativeGlfw should return silently
        Path nativesDir = tmp.resolve("natives");
        Files.createDirectories(nativesDir);
        // Don't create libglfw.so
        WaylandSupport.patchNativeGlfw(nativesDir); // Should not throw
    }

    @Test
    void usesBuggyLwjglReturnsFalseForNullAndEmpty() {
        assertFalse(WaylandSupport.usesBuggyLwjgl(null));
        assertFalse(WaylandSupport.usesBuggyLwjgl(new JsonObject()));
    }
}
