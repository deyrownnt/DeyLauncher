package com.deylauncher.modloader;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The exact hole this suite exists for: 26.3 was mapped to {@code DeyCapes-26.3.jar} while only
     * {@code DeyCapes-26.2.jar} was bundled, so on a clean install the file was simply missing and 26.3
     * silently had no capes. Every jar the mapper can name must therefore really be in this build.
     */
    @Test
    void everyMappedJarIsActuallyBundled() {
        for (String mc : SUPPORTED) {
            String jar = DeyCapesInstaller.resolveJarName(mc);
            assertNotNull(jar, mc + " must map to a bundled DeyCapes jar");
            assertNotNull(DeyCapesInstallerTest.class.getResourceAsStream("/deycapes-jars/" + jar),
                    jar + " is mapped for " + mc + " but is NOT bundled in this build's resources");
        }
    }

    /**
     * The file existing is not enough -- it has to be the RIGHT file. Each mapped jar must declare that
     * it supports the Minecraft version it would be installed for, because Fabric aborts at startup
     * (HARD_DEP) when a jar's own {@code depends.minecraft} rejects the running version.
     */
    @Test
    void theMappedJarDeclaresSupportForTheVersionItIsMappedTo() throws Exception {
        for (String mc : SUPPORTED) {
            String declared = declaredMinecraftRange(DeyCapesInstaller.resolveJarName(mc));
            assertNotNull(declared, mc + "'s jar must declare depends.minecraft");
            assertTrue(MinecraftVersionRange.matches(declared, mc),
                    "jar mapped for " + mc + " declares minecraft " + declared + ", which rejects it");
        }
    }

    /**
     * Versions below a jar's own floor (1.18.1 &lt; 1.18.2, 1.19.3 &lt; 1.19.4, 1.20.1 &lt; 1.20.6) still
     * name that jar, but the install gate MUST refuse it. That refusal is the difference between "this
     * version gets no Dey capes" and Fabric hard-failing on launch.
     */
    @Test
    void versionsBelowAJarFloorAreRefusedByTheGateInsteadOfInstalling() throws Exception {
        assertFalse(MinecraftVersionRange.matches(declaredMinecraftRange("DeyCapes-1.18.jar"), "1.18.1"));
        assertFalse(MinecraftVersionRange.matches(declaredMinecraftRange("DeyCapes-1.19.jar"), "1.19.3"));
        assertFalse(MinecraftVersionRange.matches(declaredMinecraftRange("DeyCapes-1.20.jar"), "1.20.1"));
    }

    /**
     * 26.0/26.1.x (including the 26.1.2 this machine last launched) have NO DeyCapes build, so the
     * mapper must name no jar at all rather than falling back to the nearest 26.x jar -- an incompatible
     * jar is not a fallback, it is a startup crash.
     */
    @Test
    void versionsWithoutADeyCapesBuildGetNoJar() {
        assertNull(DeyCapesInstaller.resolveJarName("26.1.2"));
        assertNull(DeyCapesInstaller.resolveJarName("26.1"));
        assertNull(DeyCapesInstaller.resolveJarName("26.0"));
    }

    /** Every Minecraft version DeyCapes actually ships a build for. */
    private static final List<String> SUPPORTED = List.of(
            "1.16.5", "1.17.1", "1.18.2", "1.19.4", "1.20.6", "1.21.1", "1.21.11", "26.2", "26.3");

    /** Reads a bundled jar's declared {@code depends.minecraft}, or null when it can't be read. */
    private static String declaredMinecraftRange(String jar) throws Exception {
        try (var in = DeyCapesInstallerTest.class.getResourceAsStream("/deycapes-jars/" + jar)) {
            if (in == null) return null;
            try (var zin = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (!"fabric.mod.json".equals(entry.getName())) continue;
                    var root = com.google.gson.JsonParser.parseString(
                            new String(zin.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    return root.getAsJsonObject("depends").get("minecraft").getAsString();
                }
            }
        }
        return null;
    }
}
