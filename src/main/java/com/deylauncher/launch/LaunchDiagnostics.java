package com.deylauncher.launch;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the cryptic tail of a Minecraft launch into a short, human-readable root-cause note that the
 * launcher logs after the game exits. The game (via LWJGL/GLFW and a GPU driver) can die in ways that
 * have nothing to do with mods or the launcher -- most commonly on Linux the graphics stack can't open
 * an OpenGL 3.3 context (e.g. {@code GLXBadFBConfig} or {@code Driver does not support OpenGL 3.3}),
 * which ends in a JVM native abort (exit 134). Rather than leaving the user staring at that, we surface
 * what actually went wrong and what to try.
 *
 * <p>Two families of failure are recognized:
 * <ul>
 *   <li>the game's own messages about failing to create a window/context/backend, and</li>
 *   <li>the JVM's ENTIRE native-crash banner ({@code # A fatal error has been detected by the Java
 *       Runtime Environment: ... # SIGSEGV ... # Problematic frame: # C [libglfw.so+0x2257b]}). That
 *       banner is what a real hardware/driver-level fault looks like, and it is available to us because
 *       the launcher merges the child's stderr into stdout
 *       ({@code ProcessBuilder.redirectErrorStream(true)}). Recognizing it matters: a fault like
 *       {@code mov 0x21850(%rbx),%rdi} against an unmapped address inside {@code libglfw.so}, reached
 *       through a mod loader's own driver workaround, is NOT something a mod-fix can ever solve, and
 *       reporting it as one wastes the user's time.</li>
 * </ul>
 *
 * <p>Deliberately does not claim "the mods are fine": the log tail that proves a native crash says
 * nothing about whether some mod contributed to the state the driver choked on. What it does say is
 * that the FAULT is below Java -- in the window/GL/driver layer -- and that is what the advice targets.
 *
 * This is diagnostic-only: it never blocks or changes a launch.
 */
public final class LaunchDiagnostics {

    private LaunchDiagnostics() {}

    /** Native libraries whose presence in a crash frame names the layer that faulted. */
    private static final String[][] NATIVE_LAYERS = {
            {"libglfw.so", "GLFW (the window-creation library the game uses)"},
            {"libglfw.dylib", "GLFW (the window-creation library the game uses)"},
            {"glfw.dll", "GLFW (the window-creation library the game uses)"},
            {"libglx_nvidia", "the NVIDIA GLX driver"},
            {"libglx.so", "the GLX layer"},
            {"libgl.so", "the OpenGL (libGL) layer"},
            {"nvoglv64.dll", "the NVIDIA OpenGL driver (nvoglv64.dll)"},
            {"nvoglv32.dll", "the NVIDIA OpenGL driver (nvoglv32.dll)"},
            {"libnvidia-glcore", "the NVIDIA OpenGL driver"},
            {"nvcuda.dll", "the NVIDIA driver"},
            {"amdvlk", "the AMD Vulkan driver"},
            {"libvulkan", "the Vulkan loader"},
    };

    private static final Pattern DUMP_FILE = Pattern.compile("(hs_err_pid\\d+\\.log|replay_pid\\d+\\.log)");

    /**
     * The exact GLFW message a Wayland-native window backend gives when something in the game (a mod's
     * window-creation redirect, in the observed case Sodium's {@code wrapGlfwCreateWindow}) calls
     * {@code glfwFocusWindow}/{@code glfwRequestWindowAttention}-adjacent focus behavior that the Wayland
     * protocol simply does not allow a client to do to itself. This is GLFW's own
     * {@code GLFW_FEATURE_UNAVAILABLE} (65548), not a driver fault and not something a GPU/software-render
     * toggle can influence -- it fires before any GL context work happens. The only real remedy is NOT
     * running on the native Wayland backend for this launch, i.e. going back through XWayland.
     */
    private static final String WAYLAND_FOCUS_UNSUPPORTED = "does not support setting the input focus";

    /**
     * The message GLFW gives when, despite DeyLauncher hiding {@code DISPLAY} to steer it onto the native
     * Wayland backend, it falls through to the X11 platform anyway and then has nothing to connect to.
     * Observed on a newer LWJGL build (3.4.1-snapshot, a 26.x Minecraft build) where the byte-search in
     * {@link WaylandSupport#glfwHasWaylandBackend} finding the Wayland symbol in {@code libglfw.so} does
     * NOT guarantee GLFW actually starts on that backend at runtime -- this build picks X11 regardless.
     * Different underlying GLFW build, same practical outcome as the focus-unsupported case: hiding
     * DISPLAY made this launch WORSE, not better, so the fix is identical -- stop hiding it.
     */
    private static final String WAYLAND_DISPLAY_MISSING_FOR_X11 = "the display environment variable is missing";

    /**
     * True when the captured output shows this specific, well-understood Wayland-native failure: a
     * feature-unavailable GLFW error about input focus. Distinct from every other window/GL failure this
     * class recognizes because the fix for it is the OPPOSITE of the general Linux graphics advice --
     * switch away from native Wayland, not toward it, and software rendering does nothing for it at all.
     */
    public static boolean isWaylandFocusUnsupported(List<String> tail) {
        if (tail == null || tail.isEmpty()) return false;
        String joined = String.join("\n", tail).toLowerCase(Locale.ROOT);
        return joined.contains(WAYLAND_FOCUS_UNSUPPORTED);
    }

    /**
     * True when GLFW ended up on X11 (and had nothing to connect to) on a launch where DeyLauncher itself
     * hid {@code DISPLAY} to force the native Wayland backend -- i.e. our own Wayland-hiding logic is what
     * broke this launch, on a GLFW build where the byte-search safety check in
     * {@link WaylandSupport#glfwHasWaylandBackend} was not a reliable predictor of runtime behavior.
     */
    public static boolean isWaylandDisplayMissingForX11(List<String> tail) {
        if (tail == null || tail.isEmpty()) return false;
        String joined = String.join("\n", tail).toLowerCase(Locale.ROOT);
        return joined.contains(WAYLAND_DISPLAY_MISSING_FOR_X11);
    }

    /**
     * True for either known way that hiding {@code DISPLAY} to force native Wayland has been observed to
     * backfire (see {@link #isWaylandFocusUnsupported} and {@link #isWaylandDisplayMissingForX11}) --
     * different GLFW/LWJGL builds fail differently, but the fix is the same in both cases: turn native
     * Wayland back OFF for this launch. Public so the launcher's self-heal retry (see
     * LauncherApp#retrySettings) can special-case this instead of falling through to the generic
     * "try software rendering" retry, which cannot fix either failure and was observed to just reproduce
     * the identical crash.
     */
    public static boolean isNativeWaylandBackfire(List<String> tail) {
        return isWaylandFocusUnsupported(tail) || isWaylandDisplayMissingForX11(tail);
    }

    /**
     * True when the JVM's own native-crash banner names GLFW ({@code libglfw.so}/{@code .dylib}/
     * {@code glfw.dll}) as the problematic frame -- a hard fault in the window-creation layer itself,
     * before any GPU work (the exact shape of {@code # C [libglfw.so+0x2257b]}, exit 134).
     *
     * <p>On its own this does NOT say which backend is to blame: the very same signature has been
     * observed both through XWayland (fixed by switching a launch onto native Wayland) and, on an older
     * LWJGL build's Wayland backend itself (fixed by the opposite -- see {@link WaylandSupport}'s
     * LWJGL-version gate), so callers must combine this with whether
     * native Wayland was actually in use for the run that produced {@code tail} before deciding which
     * way to flip the switch.
     */
    public static boolean isGlfwWindowLayerCrash(List<String> tail) {
        if (tail == null || tail.isEmpty()) return false;
        String joined = String.join("\n", tail).toLowerCase(Locale.ROOT);
        String layer = nativeLayer(tail, joined);
        return layer != null && layer.contains("libglfw");
    }

    /**
     * Returns a human-readable explanation for {@code exitCode} given the last {@code tail} lines of
     * the game's captured output, or {@code null} when nothing recognizable happened (a normal exit).
     * The advice is tailored to the current OS -- the Linux graphics fallback suggestions
     * (GLX/LIBGL) make no sense on the Windows build.
     */
    public static String analyze(List<String> tail, int exitCode) {
        return analyze(tail, exitCode, System.getProperty("os.name", ""));
    }

    /**
     * Same as {@link #analyze(List, int)}, additionally told whether native Wayland was switched on for
     * the run that produced {@code tail} -- see {@link #analyze(List, int, String, boolean)}. Callers
     * that know this (every real launch does: it's a {@code LaunchSettings} field) should prefer this
     * overload over the plain one.
     */
    public static String analyze(List<String> tail, int exitCode, boolean nativeWaylandEnabled) {
        return analyze(tail, exitCode, System.getProperty("os.name", ""), nativeWaylandEnabled);
    }

    /** Testable core: {@code osName} is e.g. "Linux", "Windows 11" or "Mac OS X". */
    static String analyze(List<String> tail, int exitCode, String osName) {
        return analyze(tail, exitCode, osName, false);
    }

    /**
     * Same as {@link #analyze(List, int, String)}, but told whether THIS run already had native Wayland
     * switched on -- which flips the advice for a GLFW window-layer fault (see
     * {@link #isGlfwWindowLayerCrash}) from "turn native Wayland on" to "this is native Wayland's own
     * backend crashing; DeyLauncher will retry through XWayland automatically", since telling someone to
     * enable a switch that is already on and is the thing that just crashed is actively misleading (see
     * WaylandSupport's LWJGL-version gate for why an already-on switch can still be the culprit).
     */
    static String analyze(List<String> tail, int exitCode, String osName, boolean nativeWaylandEnabled) {
        if (tail == null || tail.isEmpty()) return null;

        String joined = String.join("\n", tail).toLowerCase(Locale.ROOT);

        boolean glx = containsAny(joined, "glxbadfbconfig", "glx: failed to create context");
        boolean opengl = containsAny(joined, "does not support opengl", "failed to create backend opengl", "backendcreationexception");
        boolean windowFail = containsAny(joined, "failed to create window", "glfw error", "failed to initialize glfw");
        boolean driverProbe = containsAny(joined, "failed to launch driver probe", "driver probe returned exit code");
        boolean fatal = containsAny(joined, "fatal error in native method", "the jvm will abort execution");
        // The JVM's own hard-crash banner (it writes this into hs_err_pid<pid>.log AND repeats the first
        // lines on stderr, which we capture). Any one of these lines is proof the fault was native.
        boolean nativeCrash = containsAny(joined,
                "a fatal error has been detected by the java runtime environment",
                "problematic frame", "hs_err_pid",
                "sigsegv", "sigill", "sigbus", "sigfpe", "sigabrt");

        if (!(glx || opengl || windowFail || driverProbe || fatal || nativeCrash)) return null;

        // Recognized separately, ahead of the generic window/GL advice below: both of these are GLFW
        // being unable to start on the backend DeyLauncher itself forced it onto (native Wayland), not a
        // crash and not a driver fault -- see isNativeWaylandBackfire's doc. Checked before the
        // nativeCrash-based framing further down too, since this failure's own JVM Flags line (it quotes
        // back its own -XX:ErrorFile=...hs_err_pid%p.log argument) can otherwise look like a real native
        // crash dump reference and produce misleading advice.
        if (isWaylandFocusUnsupported(tail)) {
            return "Minecraft tried to grab window input focus in a way the Wayland protocol does not "
                    + "allow a client to do to itself (GLFW error 65548, \"" + WAYLAND_FOCUS_UNSUPPORTED
                    + "\"). This happens specifically because the launch used GLFW's native Wayland "
                    + "backend -- it is not a GPU/driver fault, and software rendering cannot change it.\n"
                    + "The fix is to run this launch through XWayland instead: turn OFF Settings > Game > "
                    + "\"Run natively on Wayland\" for this instance (DeyLauncher's automatic retry now does "
                    + "this for you the next time you press Play).";
        }
        if (isWaylandDisplayMissingForX11(tail)) {
            return "DeyLauncher hid DISPLAY to start this launch on GLFW's native Wayland backend, but "
                    + "this Minecraft build's GLFW (LWJGL) fell through to the X11 platform anyway and had "
                    + "nothing to connect to (\"" + WAYLAND_DISPLAY_MISSING_FOR_X11 + "\"). Hiding DISPLAY "
                    + "is what broke this launch, not a GPU/driver fault -- software rendering cannot fix "
                    + "it either.\n"
                    + "The fix is to run this launch through XWayland instead: turn OFF Settings > Game > "
                    + "\"Run natively on Wayland\" for this instance (DeyLauncher's automatic retry now does "
                    + "this for you the next time you press Play).";
        }

        String lower = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        boolean windows = lower.contains("win");
        boolean mac = lower.contains("mac") || lower.contains("darwin");

        String layer = nativeLayer(tail, joined);
        boolean graphicsLayer = layer != null || containsAny(joined,
                "libglfw", "libgl.so", "libglx", "glx_nvidia", "nvoglv", "libnvidia", "libvulkan", "amdvlk");
        // A fault whose own frame names GLFW is a WINDOW-layer fault: it happens while Minecraft asks the
        // display server for a window, before any GPU work -- which is why the software-renderer remedy
        // (and driver-update advice in general) does not apply to it and must not be promised.
        boolean glfwWindowLayer = layer != null && layer.contains("libglfw");
        String dumpFile = dumpFileName(tail);

        StringBuilder sb = new StringBuilder();
        if (nativeCrash) {
            sb.append("The game crashed in NATIVE code -- a hard fault inside a C library, below Java, ")
              .append("so this is a graphics/window/driver problem, not something a mod or a launcher ")
              .append("setting caused.\n");
            if (layer != null) {
                sb.append("The JVM's own crash log names the failing frame as ").append(layer).append(".\n");
            }
            if (graphicsLayer) {
                if (glfwWindowLayer) {
                    sb.append("GLFW is the window layer: that is the step where Minecraft asks the display ")
                      .append("server for a window, before any GPU work, and the fault happened there.\n");
                } else {
                    sb.append("That is the window + OpenGL layer: GLFW asks the display server for a window ")
                      .append("and the GPU driver for a context, and the fault happened there.\n");
                }
            }
            if (containsAny(joined, "nvidia_threaded_optimizations_broken", "no_error_context_unsupported",
                    "workarounds for the nvidia")) {
                sb.append("Your graphics driver was already flagged as needing launcher workarounds ")
                  .append("(a mod loader's own compatibility list is saying this driver is broken), ")
                  .append("which points at the driver/display stack rather than at any mod.\n");
            }
        } else {
            sb.append("The game could not open a graphics window / OpenGL context (this is a GPU-driver ")
              .append("or display issue, NOT a mod or launcher problem).\n");
        }

        sb.append(graphicsLayer ? graphicsAdvice(windows, mac, glfwWindowLayer, nativeWaylandEnabled) : contextAdvice(windows, mac));

        if (dumpFile != null) {
            sb.append("\nThe crash dump for this run is ").append(dumpFile)
              .append(" (next to Minecraft's own crash reports). DeyLauncher redacts your account token ")
              .append("from it and restricts it to your user account, so it is safe to keep -- but treat ")
              .append("it as private if you share it.");
        }
        return sb.toString();
    }

    /**
     * The advice for a fault in the window/GL/driver layer itself -- the case where "update the driver",
     * "try another GPU/display" and "use the software renderer" are the levers that exist. Worded per OS
     * because the Linux fallback (Mesa/llvmpipe through the GLVND dispatcher) means nothing on Windows.
     *
     * <p>{@code glfwWindowLayer} selects the honest Linux wording: a fault inside GLFW happens while the
     * game asks the display server for a window, before any GPU work, and Mesa's software renderer cannot
     * avoid that (measured -- the same crash, at the same instruction, with Mesa's libGLX/gallium loaded and
     * no NVIDIA GLX library at all). So for that case the advice leads with the remedy that does work:
     * starting the game on Wayland's own driver path instead of XWayland (DeyLauncher's Settings > Game >
     * "Run natively on Wayland"), or a real X11 session. The software-renderer text is still named, but as
     * something that changes how the context is created rather than how the window is -- not a promise.
     */
    private static String graphicsAdvice(boolean windows, boolean mac, boolean glfwWindowLayer,
                                         boolean nativeWaylandEnabled) {
        if (glfwWindowLayer && !windows && !mac && nativeWaylandEnabled) {
            // Telling the user to turn on a switch that is already on -- and is the thing that just
            // crashed (an older LWJGL build's Wayland backend; see WaylandSupport's LWJGL-version gate)
            // -- would be actively wrong advice, so this branch replaces the generic wording entirely
            // rather than layering on top of it.
            return "This launch already had Settings > Game > \"Run natively on Wayland\" turned on, and "
                    + "the crash happened inside GLFW's native Wayland backend itself -- most likely because "
                    + "this version is built on an LWJGL release whose Wayland support isn't crash-safe yet "
                    + "(a known issue on LWJGL 3.3.1, which most Minecraft 1.20.1 modpacks still bundle). "
                    + "DeyLauncher now retries this one launch automatically through XWayland instead, and "
                    + "will keep doing so for this version without turning native Wayland on for it again.";
        }
        if (windows) {
            return "On Windows this class of fault is almost always a stale, mismatched or partially "
                    + "installed GPU driver. Install the current driver from the manufacturer (NVIDIA "
                    + "GeForce Driver for an RTX card, AMD Adrenalin for Radeon) choosing \"clean install\", "
                    + "then reboot. If it still crashes, close GPU overclocking/overlay tools (MSI "
                    + "Afterburner, RivaTuner, Discord/GeForce overlays): they hook the same driver entry "
                    + "points and are a common cause of a fault inside nvoglv64.dll.";
        }
        if (mac) {
            return "Update macOS and the Java runtime this launcher uses. Minecraft needs OpenGL 3.3 or "
                    + "later, which newer macOS versions only provide through emulation.";
        }
        String common = "If your machine has more than one GPU (for example an NVIDIA RTX card alongside an "
                + "AMD/Intel integrated one), make sure the game runs on the discrete NVIDIA GPU and the "
                + "correct display. Minecraft needs OpenGL 3.3 or later.";
        if (glfwWindowLayer) {
            return "A fault inside GLFW happens while Minecraft asks the display server for a window, before "
                    + "any GPU work -- so Mesa's software renderer cannot avoid it: Settings > Game > "
                    + "\"Software rendering (compatibility)\" (LIBGL_ALWAYS_SOFTWARE) changes how the OpenGL "
                    + "context is created, not how the window is. On a Wayland session the usual cause is "
                    + "XWayland, which is what the game goes through whenever DISPLAY is set: turn on "
                    + "Settings > Game > \"Run natively on Wayland\" so DeyLauncher starts the game on "
                    + "Wayland's own driver path instead (on by default when the launcher itself runs in a "
                    + "Wayland session), or use a real X11 (Xorg) session if your desktop offers one.\n"
                    + common;
        }
        return "Update your graphics driver first. " + common + "\n"
                + "If updating the driver isn't possible, enable Settings > Game > \"Software rendering "
                + "(compatibility)\": DeyLauncher then runs Minecraft through Mesa's CPU renderer "
                + "(__GLX_VENDOR_LIBRARY_NAME=mesa + LIBGL_ALWAYS_SOFTWARE), which avoids a broken GL "
                + "context creation entirely -- no terminal or admin needed.\n"
                + "On Wayland, trying a real X11 (Xorg) session instead avoids the XWayland translation "
                + "layer, and if you recently changed driver version, rolling back one release is worth "
                + "trying while the current one is broken.";
    }

    /** The advice for the older case: the game reported a missing/unsupported OpenGL context in Java. */
    private static String contextAdvice(boolean windows, boolean mac) {
        if (windows) {
            return "On the Windows build this is almost always a stale/mismatched GPU driver. "
                    + "Update your GPU driver from the manufacturer (e.g. NVIDIA GeForce Driver for an RTX card; "
                    + "AMD Adrenalin if using an AMD GPU). Minecraft needs OpenGL 3.3 or later.";
        }
        if (mac) {
            return "Update your macOS graphics / Java runtime. Minecraft needs OpenGL 3.3 or later.";
        }
        return "Update your graphics driver (your setup even flagged NVIDIA workarounds). "
                + "If you have more than one GPU (e.g. an NVIDIA RTX card + an AMD integrated one), "
                + "force the game onto the discrete NVIDIA GPU / correct display. Minecraft needs "
                + "OpenGL 3.3 or later.\n"
                + "If updating the driver isn't possible, enable Settings > Game > "
                + "\"Software rendering (compatibility)\" -- DeyLauncher then runs Minecraft "
                + "through Mesa's CPU renderer automatically (LIBGL_ALWAYS_SOFTWARE), no terminal "
                + "or admin needed.";
    }

    /**
     * The native library named in the crash's own "Problematic frame", in human terms, or null when the
     * tail names none. Prefers the frame line -- the authoritative statement of where the faulting
     * instruction was -- and only falls back to "any known library name appears anywhere".
     */
    static String nativeLayer(List<String> tail, String joinedLower) {
        String frameText = frameText(tail);
        if (frameText != null) {
            String frame = frameText.toLowerCase(Locale.ROOT);
            for (String[] entry : NATIVE_LAYERS) {
                if (frame.contains(entry[0])) return entry[1] + " [" + entry[0] + "]";
            }
        }
        for (String[] entry : NATIVE_LAYERS) {
            if (joinedLower.contains(entry[0])) return entry[1] + " [" + entry[0] + "]";
        }
        return null;
    }

    /** The text right after a {@code "# Problematic frame:"} line (the next few lines), or null. */
    private static String frameText(List<String> tail) {
        for (int i = 0; i < tail.size(); i++) {
            String line = tail.get(i);
            if (line != null && line.toLowerCase(Locale.ROOT).contains("problematic frame")) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j < tail.size() && j <= i + 3; j++) {
                    if (tail.get(j) != null) sb.append(tail.get(j)).append(' ');
                }
                return sb.toString();
            }
        }
        return null;
    }

    /** The hs_err/replay dump file name mentioned in the output, so the advice can point at it. */
    static String dumpFileName(List<String> tail) {
        for (String line : tail) {
            if (line == null) continue;
            Matcher m = DUMP_FILE.matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static boolean containsAny(String joined, String... needles) {
        for (String n : needles) {
            if (joined.contains(n)) return true;
        }
        return false;
    }
}
