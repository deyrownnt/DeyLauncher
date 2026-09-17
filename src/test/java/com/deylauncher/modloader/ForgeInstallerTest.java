package com.deylauncher.modloader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two things that made Forge's own installer fail with a bare exit code 1 on a
 * third-party launcher's root folder: the profile id it actually writes, and the launcher profile
 * file it insists on.
 */
class ForgeInstallerTest {

    /**
     * Forge names its client profile after its OWN version string (install_profile.json's
     * "version"), e.g. 1.20.1-forge-47.4.20 -- not the maven artifact order (1.20.1-47.4.20). Getting
     * this wrong means the "already installed" check never matches, so the installer re-runs on every
     * launch and then its "what's new under versions/" scan finds nothing and fails the launch.
     */
    @Test
    void profileIdUsesForgeOwnNamingOrder() {
        assertEquals("1.20.1-forge-47.4.20", ForgeInstaller.profileId("1.20.1", "47.4.20"));
        assertEquals("1.16.5-forge-36.2.34", ForgeInstaller.profileId("1.16.5", "36.2.34"));
    }

    /** The profile file has to sit exactly where the launcher looks for a version, alongside the JSON. */
    @Test
    void profileFileLivesUnderVersionsNamedAfterItself() {
        Path root = Path.of("/tmp/deyroot");
        assertEquals(Path.of("/tmp/deyroot/versions/1.20.1-forge-47.4.20/1.20.1-forge-47.4.20.json"),
                ForgeInstaller.profileFile(root, "1.20.1-forge-47.4.20"));
    }

    /**
     * Without this file Forge's ClientInstall refuses to run at all ("There is no Minecraft launcher
     * profile in ... you need to run the launcher first!") -- which is the failure this launcher hit.
     * Forge then merges its own entry into the "profiles" object, so that key must exist.
     */
    @Test
    void ensureLauncherProfilesCreatesAFileForgeCanInjectInto(@TempDir Path root) throws Exception {
        ForgeInstaller.ensureLauncherProfiles(root);

        Path standard = root.resolve("launcher_profiles.json");
        assertTrue(Files.exists(standard), "Forge's installer refuses to run without a launcher profile file");
        JsonObject parsed = JsonParser.parseString(Files.readString(standard)).getAsJsonObject();
        assertTrue(parsed.has("profiles") && parsed.get("profiles").isJsonObject(),
                "Forge merges its entry into the \"profiles\" object, so it must exist and be an object");
    }

    /** A real profile (or one Forge already wrote an entry into) must never be overwritten. */
    @Test
    void ensureLauncherProfilesLeavesAnExistingProfileAlone(@TempDir Path root) throws Exception {
        Path standard = root.resolve("launcher_profiles.json");
        String existing = "{\"profiles\":{\"forge\":{\"name\":\"forge\",\"lastVersionId\":\"1.20.1-forge-47.4.20\"}}}";
        Files.writeString(standard, existing);

        ForgeInstaller.ensureLauncherProfiles(root);

        assertEquals(existing, Files.readString(standard));
    }

    /** Forge accepts the Microsoft Store variant too, so that alone means there is nothing to do. */
    @Test
    void ensureLauncherProfilesAcceptsTheMicrosoftStoreVariant(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("launcher_profiles_microsoft_store.json"), "{\"profiles\":{}}");

        ForgeInstaller.ensureLauncherProfiles(root);

        assertFalse(Files.exists(root.resolve("launcher_profiles.json")),
                "either file satisfies Forge, so a second one must not be created");
    }
}