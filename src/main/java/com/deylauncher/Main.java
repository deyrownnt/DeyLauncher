package com.deylauncher;

import com.deylauncher.auth.MicrosoftAuth;
import com.deylauncher.version.VersionManifest;

import java.util.List;

/**
 * Phase 1 entry point: proves the two hardest foundations work end to end --
 * Microsoft/Xbox/Minecraft login, and pulling the live version list.
 * The JavaFX UI (login button, version picker, settings) gets built on top
 * of exactly these two classes in Phase 2 -- nothing here changes shape.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("=== DeyLauncher (phase 1: auth + versions) ===\n");

        // --- Step 1: sign in with Microsoft ---
        MicrosoftAuth auth = new MicrosoftAuth();
        MicrosoftAuth.MinecraftSession session = auth.login((verificationUri, userCode, expiresInSeconds) -> {
            System.out.println("To sign in:");
            System.out.println("  1. Open: " + verificationUri);
            System.out.println("  2. Enter this code: " + userCode);
            System.out.println("  (code expires in " + (expiresInSeconds / 60) + " minutes)\n");
            System.out.println("Waiting for you to finish signing in...");
        });

        System.out.println("\nSigned in as: " + session.username() + " (" + session.uuid() + ")");

        // --- Step 2: pull the live version list ---
        VersionManifest manifest = new VersionManifest();
        List<VersionManifest.VersionEntry> all = manifest.fetchAll();
        System.out.println("\nFetched " + all.size() + " versions from Mojang.");

        String[] defaultVersions = { "1.21.1", "26.2" };
        for (String id : defaultVersions) {
            var v = manifest.findById(all, id);
            if (v != null) {
                System.out.println("Found default version " + id + " (released " + v.releaseTime() + ")");
            } else {
                System.out.println("Could not find " + id + " in the manifest -- Mojang may have renamed/removed it.");
            }
        }
        System.out.println("\nNote: 26.2 requires Java 25+ to actually run the game (not just build the");
        System.out.println("launcher). Phase 2's launch code will read each version's required Java");
        System.out.println("version from its manifest entry and use the right runtime automatically --");
        System.out.println("you won't need two different JDKs installed yourself.");

        // Next: download the version's jars/libraries and build the launch command
        // (Phase 2 -- see the "launch" package will add next).
    }
}
