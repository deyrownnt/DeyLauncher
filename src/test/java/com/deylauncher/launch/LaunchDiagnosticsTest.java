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