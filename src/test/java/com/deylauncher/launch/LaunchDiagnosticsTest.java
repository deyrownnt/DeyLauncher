package com.deylauncher.launch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchDiagnosticsTest {

    @Test
    void recognizesGlxContextFailure_from19_3Log() {
        List<String> tail = List.of(
                "[Render thread/WARN]: Failed to create window: ",
                "net.minecraft.class_1041$class_4716: GLFW error 65543: GLX: Failed to create context: GLXBadFBConfig",
                "Game exited with code 0"
        );
        assertNotNull(LaunchDiagnostics.analyze(tail, 0));
    }

    @Test
    void recognizesMissingOpenGL_from26_2Log() {
        List<String> tail = List.of(
                "Failed to create backend OpenGL",
                "BackendCreationException: Driver does not support OpenGL 3.3",
                "FATAL ERROR in native method: No context is current or a function that is not available",
                "The JVM will abort execution.",
                "Game exited with code 134"
        );
        assertNotNull(LaunchDiagnostics.analyze(tail, 134));
    }

    @Test
    void recognizesDriverProbeFailure() {
        List<String> tail = List.of(
                "Failed to launch driver probe",
                "RuntimeException: Driver probe returned exit code 1"
        );
        assertNotNull(LaunchDiagnostics.analyze(tail, 1));
    }

    @Test
    void recognizesNativeGlfwCrash_fromRealHsErrDump() {
        // The exact shape of the crash tail captured by the launcher for hs_err_pid30512.log: the JVM's
        // native-crash banner (merged into stdout by redirectErrorStream), reached through Sodium's
        // NVIDIA workaround, with the window created via XWayland on a Plasma Wayland session. This is a
        // driver/window-layer fault: the old diagnostics didn't recognize it at all, so the launcher
        // reported nothing and the user was left staring at an exit code.
        List<String> tail = List.of(
                "Not enabling OpenGL compat profile",
                "Modifying process environment to apply workarounds for the NVIDIA graphics driver",
                "Sodium has applied the following workarounds: [NVIDIA_THREADED_OPTIMIZATIONS_BROKEN, NO_ERROR_CONTEXT_UNSUPPORTED]",
                "#",
                "# A fatal error has been detected by the Java Runtime Environment:",
                "#",
                "#  SIGSEGV (0xb) at pc=0x00007f28a2c2257b, pid=30512, tid=30513",
                "#",
                "# Problematic frame:",
                "# C  [libglfw.so+0x2257b]",
                "#",
                "# An error report file with more information is saved as:",
                "# /home/u/.deylauncher/instances/1.20.1-fabric/hs_err_pid30512.log",
                "Game exited with code 134"
        );

        String linux = LaunchDiagnostics.analyze(tail, 134, "Linux");
        assertNotNull(linux, "a native GLFW crash must be recognized, not silently ignored");
        assertTrue(linux.contains("NATIVE"), "it must say the fault is native, not a Java-level failure");
        assertTrue(linux.contains("GLFW"), "the diagnosis must name the layer that faulted");
        assertTrue(linux.contains("libglfw.so"), "the failing frame's own library should be quoted back");
        assertFalse(linux.contains("the mods are fine"),
                "a native crash is not evidence that the mod set is fine -- never claim that");
        assertTrue(linux.contains("before any GPU work"),
                "a fault inside GLFW happens at window creation, before the GPU is involved at all");
        assertTrue(linux.contains("Software rendering (compatibility)"),
                "the software-rendering setting must be addressed by name");
        assertTrue(linux.toLowerCase().contains("cannot avoid it"),
                "and the diagnosis must not promise software rendering fixes a window-layer fault -- measured "
                        + "false: the same crash reproduces with Mesa's llvmpipe loaded");
        assertTrue(linux.contains("Run natively on Wayland"),
                "the remedy that DOES work for this crash must be named");
        assertTrue(linux.contains("XWayland"), "and why it happens (the game goes through XWayland)");
        assertTrue(linux.contains("discrete NVIDIA GPU"), "and at the multi-GPU case");
        assertTrue(linux.contains("X11"), "a real X11 (Xorg) session is the alternative on Linux");
        assertTrue(linux.contains("hs_err_pid30512.log"), "it should name the dump file that was written");

        // Windows must get Windows advice: no LIBGL/GLX remedies, and no Wayland-only switch either.
        String windows = LaunchDiagnostics.analyze(tail, 134, "Windows 11");
        assertNotNull(windows);
        assertFalse(windows.contains("LIBGL"), "Windows advice must not mention the Linux LIBGL fallback");
        assertFalse(windows.contains("Run natively on Wayland"),
                "Windows advice must not mention the Linux Wayland switch");
        assertFalse(windows.contains("discrete NVIDIA GPU"), "Windows advice must not suggest GL switches");
        assertTrue(windows.contains("nvoglv64.dll") || windows.contains("GPU driver"),
                "Windows advice should name the driver class of crash");
    }

    @Test
    void aGpuDriverFaultStillGetsTheSoftwareRendererAdvice() {
        // The software renderer is still the right answer for a fault in the GL/driver layer that is NOT the
        // window layer (here: the NVIDIA GLX driver's own frame), so it must not have been dropped when the
        // GLFW-specific wording was added.
        List<String> tail = List.of(
                "# A fatal error has been detected by the Java Runtime Environment:",
                "#  SIGSEGV (0xb) at pc=0x00007f00, pid=7, tid=8",
                "# Problematic frame:",
                "# C  [libglx_nvidia.so.0+0x1a2b3]",
                "Game exited with code 134"
        );
        String diag = LaunchDiagnostics.analyze(tail, 134, "Linux");
        assertNotNull(diag);
        assertTrue(diag.contains("NVIDIA GLX driver"), "the failing layer should be named");
        assertTrue(diag.contains("LIBGL_ALWAYS_SOFTWARE") && diag.contains("Software rendering (compatibility)"),
                "Linux advice should point at the software-rendering escape hatch for this case");
    }

    @Test
    void nativeCrashWithoutAKnownLibraryIsStillExplained() {
        // A native fault in something we don't have a friendly name for must still be surfaced, and must
        // NOT be dressed up with graphics advice that may be irrelevant.
        List<String> tail = List.of(
                "# A fatal error has been detected by the Java Runtime Environment:",
                "#  SIGILL (0x4) at pc=0x00007f00, pid=99, tid=100",
                "# Problematic frame:",
                "# V  [libjvm.so+0x1234]",
                "Game exited with code 134"
        );
        String diag = LaunchDiagnostics.analyze(tail, 134, "Linux");
        assertNotNull(diag);
        assertTrue(diag.contains("NATIVE"));
        assertFalse(diag.contains("SIGSEGV at pc"),
                "the message is a human explanation, not a copy of the raw banner");
    }

    @Test
    void normalExitYieldsNoDiagnostic() {
        List<String> tail = List.of(
                "Setting user: Deyronn",
                "Loading world...",
                "Game exited with code 0"
        );
        assertNull(LaunchDiagnostics.analyze(tail, 0, "Linux"));
    }

    @Test
    void linuxAdviceMentionsGlxFallback_onlyOnLinux() {
        List<String> tail = List.of("GLX: Failed to create context: GLXBadFBConfig");

        String linux = LaunchDiagnostics.analyze(tail, 1, "Linux");
        assertNotNull(linux);
        assertTrue(linux.contains("LIBGL_ALWAYS_SOFTWARE"), "Linux advice should mention the software-GL fallback");
        assertTrue(linux.contains("discrete NVIDIA GPU"), "Linux advice should mention the multi-GPU case");

        // The Windows build must NOT suggest Linux-only GLX/LIBGL remedies.
        String windows = LaunchDiagnostics.analyze(tail, 1, "Windows 11");
        assertNotNull(windows);
        assertFalse(windows.contains("LIBGL"), "Windows advice must not mention the Linux LIBGL fallback");
        assertFalse(windows.contains("GLXBadFBConfig"), "Windows advice must not suggest GLX fixes");
        assertFalse(windows.contains("discrete NVIDIA GPU"), "Windows advice must not suggest switching GL contexts");
        assertTrue(windows.contains("NVIDIA GeForce Driver") || windows.contains("GPU driver"),
                "Windows advice should point to a driver update");
    }
}