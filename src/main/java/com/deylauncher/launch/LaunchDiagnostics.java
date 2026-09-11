package com.deylauncher.launch;

import java.util.List;

/**
 * Turns the cryptic tail of a Minecraft launch into a short, human-readable root-cause note that the
 * launcher logs after the game exits. The game (via LWJGL/GLFW and a GPU driver) can die in ways that
 * have nothing to do with mods or the launcher -- most commonly on Linux the graphics stack can't open
 * an OpenGL 3.3 context (e.g. {@code GLXBadFBConfig} or {@code Driver does not support OpenGL 3.3}),
 * which ends in a JVM native abort (exit 134). Rather than leaving the user staring at that, we surface
 * what actually went wrong and what to try.
 *
 * This is diagnostic-only: it never blocks or changes a launch.
 */
public final class LaunchDiagnostics {

    private LaunchDiagnostics() {}

    /**
     * Returns a human-readable explanation for {@code exitCode} given the last {@code tail} lines of
     * the game's captured output, or {@code null} when nothing recognizable happened (a normal exit).
     * The advice is tailored to the current OS -- the Linux graphics fallback suggestions
     * (GLX/LIBGL) make no sense on the Windows build.
     */
    public static String analyze(List<String> tail, int exitCode) {
        return analyze(tail, exitCode, System.getProperty("os.name", ""));
    }

    /** Testable core: {@code osName} is e.g. "Linux", "Windows 11" or "Mac OS X". */
    static String analyze(List<String> tail, int exitCode, String osName) {
        if (tail == null || tail.isEmpty()) return null;

        String joined = String.join("\n", tail).toLowerCase();

        boolean glx = containsAny(joined, "glxbadfbconfig", "glx: failed to create context");
        boolean opengl = containsAny(joined, "does not support opengl", "failed to create backend opengl", "backendcreationexception");
        boolean windowFail = containsAny(joined, "failed to create window", "glfw error");
        boolean driverProbe = containsAny(joined, "failed to launch driver probe", "driver probe returned exit code");
        boolean fatal = containsAny(joined, "fatal error in native method", "the jvm will abort execution");

        if (!(glx || opengl || windowFail || driverProbe || fatal)) return null;

        String lower = osName == null ? "" : osName.toLowerCase();
        boolean windows = lower.contains("win");
        boolean mac = lower.contains("mac");

        return "The game could not open a graphics window / OpenGL context (this is a GPU-driver or "
                + "display issue, NOT a mod or launcher problem). The mod set loaded successfully right "
                + "before this, so the mods are fine.\n"
                + (windows
                    ? "On the Windows build this is almost always a stale/mismatched GPU driver. "
                      + "Update your GPU driver from the manufacturer (e.g. NVIDIA GeForce Driver for an RTX card; "
                      + "AMD Adrenalin if using an AMD GPU). Minecraft needs OpenGL 3.3 or later."
                    : mac
                        ? "Update your macOS graphics / Java runtime. Minecraft needs OpenGL 3.3 or later."
                        : "Update your graphics driver (your setup even flagged NVIDIA workarounds). "
                          + "If you have more than one GPU (e.g. an NVIDIA RTX card + an AMD integrated one), "
                          + "force the game onto the discrete NVIDIA GPU / correct display. Minecraft needs "
                          + "OpenGL 3.3 or later.\n"
                          + "If updating the driver isn't possible, enable Settings > Game > "
                          + "\"Software rendering (compatibility)\" -- DeyLauncher then runs Minecraft "
                          + "through Mesa's CPU renderer automatically (LIBGL_ALWAYS_SOFTWARE), no terminal "
                          + "or admin needed.");
    }

    private static boolean containsAny(String joined, String... needles) {
        for (String n : needles) {
            if (joined.contains(n)) return true;
        }
        return false;
    }
}