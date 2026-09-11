package com.deylauncher.launch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLauncherTest {

    @Test
    void softwareGlOnLinuxPrependsMesaEnvViaEnvBinary() {
        List<String> command = List.of("java", "-Xmx2048M", "net.minecraft.client.Main");
        List<String> out = GameLauncher.applySoftwareGl(command, true, "Linux");
        assertEquals(8, out.size());
        assertEquals("/usr/bin/env", out.get(0));
        assertEquals("LIBGL_ALWAYS_SOFTWARE=1", out.get(1));
        // GLVND dispatcher must be forced onto Mesa, otherwise NVIDIA libGL silently ignores
        // LIBGL_ALWAYS_SOFTWARE and the "software rendering" fallback does nothing (crash, exit 134).
        assertEquals("__GLX_VENDOR_LIBRARY_NAME=mesa", out.get(2));
        assertEquals("GALLIUM_DRIVER=llvmpipe", out.get(3));
        assertEquals("MESA_LOADER_DRIVER_OVERRIDE=llvmpipe", out.get(4));
        assertEquals("java", out.get(5));
        assertEquals("net.minecraft.client.Main", out.get(7));
    }

    @Test
    void softwareGlOffLeavesCommandUntouched() {
        List<String> command = List.of("java", "-Xmx2048M");
        assertEquals(command, GameLauncher.applySoftwareGl(command, false, "Linux"));
    }

    @Test
    void softwareGlOnWindowsIsNoop() {
        List<String> command = List.of("java", "-Xmx2048M");
        assertEquals(command, GameLauncher.applySoftwareGl(command, true, "Windows 11"));
    }

    @Test
    void envPrefixedCommandStillExecutableShape() {
        // Sanity: the whole original command is preserved verbatim after the env assignments.
        List<String> command = List.of("/home/u/java/bin/java", "-XX:+UseG1GC", "com.deylauncher.x");
        List<String> out = GameLauncher.applySoftwareGl(command, true, "Linux");
        // Previous env prefix (5 entries: /usr/bin/env + 4 Mesa vars) then the original command verbatim.
        assertTrue(out.subList(5, out.size()).equals(command));
    }
}