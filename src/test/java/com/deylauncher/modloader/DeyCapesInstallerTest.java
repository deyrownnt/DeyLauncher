package com.deylauncher.modloader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which bundled DeyCapes jar a Minecraft version gets.
 *
 * <p>This matters more than it looks: {@code DeyCapesInstaller.ensureInstalled} reads the chosen
 * jar's own {@code depends.minecraft} and refuses to install it when the selected version doesn't
 * satisfy it (Fabric aborts at startup with a HARD_DEP error otherwise). Pointing 26.3 at the 26.2
 * jar would therefore silently mean "no capes on 26.3" -- so each 26.x build needs its own arm, and
 * the newer ones must be matched before the broader "26" arm.
 */
class DeyCapesInstallerTest {

    @Test
    void eachTwentySixBuildGetsItsOwnJar() {
        assertEquals("DeyCapes-26.3.jar", DeyCapesInstaller.resolveJarName("26.3"));
        assertEquals("DeyCapes-26.3.jar", DeyCapesInstaller.resolveJarName("26.3.1"));
        assertEquals("DeyCapes-26.2.jar", DeyCapesInstaller.resolveJarName("26.2"));
    }

    @Test
    void theNewerArmIsMatchedBeforeTheGenericOne() {
        // A regression guard: if "26" were checked first, 26.3 would be handed the 26.2 jar.
        assertEquals("DeyCapes-26.3.jar", DeyCapesInstaller.resolveJarName("26.3"));
    }

    @Test
    void olderVersionsKeepTheirExistingJars() {
        assertEquals("DeyCapes-1.16.jar", DeyCapesInstaller.resolveJarName("1.16.5"));
        assertEquals("DeyCapes-1.20.jar", DeyCapesInstaller.resolveJarName("1.20.1"));
        assertEquals("DeyCapes-1.21.1.jar", DeyCapesInstaller.resolveJarName("1.21.1"));
        assertEquals("DeyCapes-1.21.2+.jar", DeyCapesInstaller.resolveJarName("1.21.11"));
    }

    @Test
    void unknownVersionsGetNoJarRatherThanTheWrongOne() {
        assertNull(DeyCapesInstaller.resolveJarName("1.15.2"));
        assertNull(DeyCapesInstaller.resolveJarName(""));
        assertNull(DeyCapesInstaller.resolveJarName(null));
    }

    /**
     * The gate that made the 26.3 build necessary: the jar's own declared range must accept the
     * version it is installed for, and reject the neighbouring one.
     */
    @Test
    void declaredMinecraftRangesAcceptTheVersionTheyWereBuiltFor() {
        assertEquals(true, MinecraftVersionRange.matches("~26.3", "26.3"));
        assertEquals(true, MinecraftVersionRange.matches("~26.2", "26.2"));
        assertEquals(false, MinecraftVersionRange.matches("~26.2", "26.3"),
                "the 26.2 jar must NOT be installed on 26.3");
    }
}
