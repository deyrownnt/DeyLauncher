package com.deylauncher;

import com.deylauncher.auth.AuthSession;
import com.deylauncher.auth.MicrosoftAuth;
import com.deylauncher.launch.GameFiles;
import com.deylauncher.launch.GameLauncher;
import com.deylauncher.launch.JavaRuntimeManager;
import com.deylauncher.version.VersionManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * Phase 2 entry point: auth (offline test mode, or real Microsoft once
 * approved) -> pick a version -> download its files -> launch it.
 *
 * Set USE_MICROSOFT_AUTH = true once https://aka.ms/mce-reviewappid comes
 * back approved. Until then, offline mode lets us build/test everything
 * else (downloading, launching, settings) right now.
 */
public class Main {

    private static final boolean USE_MICROSOFT_AUTH = false; // flip to true once app is approved

    public static void main(String[] args) throws Exception {
        System.out.println("=== DeyLauncher (phase 2: download + launch) ===\n");

        AuthSession session;
        if (USE_MICROSOFT_AUTH) {
            MicrosoftAuth auth = new MicrosoftAuth();
            var msSession = auth.login((verificationUri, userCode, expiresInSeconds) -> {
                System.out.println("To sign in:");
                System.out.println("  1. Open: " + verificationUri);
                System.out.println("  2. Enter this code: " + userCode);
                System.out.println("  (code expires in " + (expiresInSeconds / 60) + " minutes)\n");
                System.out.println("Waiting for you to finish signing in...");
            });
            session = AuthSession.fromMicrosoft(msSession);
        } else {
            System.out.println("[offline test mode -- can't join real online servers, see README]");
            System.out.print("Pick a username for this test session: ");
            String name = new Scanner(System.in).nextLine().trim();
            if (name.isEmpty()) name = "Player" + (int) (Math.random() * 10000);
            session = AuthSession.offline(name);
        }
        System.out.println("Signed in as: " + session.username() + " (" + session.uuid() + ")\n");

        // --- pick a version ---
        VersionManifest manifest = new VersionManifest();
        List<VersionManifest.VersionEntry> all = manifest.fetchAll();

        System.out.println("Default versions available: 1.21.1, 26.2");
        System.out.print("Which version do you want to launch? ");
        String versionId = new Scanner(System.in).nextLine().trim();
        if (versionId.isEmpty()) versionId = "1.21.1";

        var entry = manifest.findById(all, versionId);
        if (entry == null) {
            System.out.println("Version \"" + versionId + "\" not found in Mojang's manifest. Aborting.");
            return;
        }

        System.out.println("Fetching version details for " + entry.id() + "...");
        var versionJson = manifest.fetchVersionDetail(entry);

        // --- download files ---
        System.out.println("Downloading client + libraries + assets (first run only, cached after)...");
        GameFiles files = new GameFiles();
        var prepared = files.prepare(versionJson);
        System.out.println("Done. Files cached under " + files.root);

        // --- matching Java runtime (auto-downloaded, no PATH dependency) ---
        System.out.println("Making sure the right Java runtime is available for " + entry.id() + "...");
        JavaRuntimeManager runtimeManager = new JavaRuntimeManager(files.root);
        Path javaBinary = runtimeManager.ensureRuntimeFor(versionJson);
        System.out.println("Using Java runtime at " + javaBinary);

        // --- launch ---
        Path gameDir = files.root.resolve("instances").resolve(entry.id());
        Files.createDirectories(gameDir);

        var settings = GameLauncher.LaunchSettings.defaults(); // RAM 1-4GB, 854x480 windowed
        GameLauncher launcher = new GameLauncher();
        System.out.println("Launching " + entry.id() + " ...");
        Process process = launcher.launch(prepared, session, gameDir, settings, javaBinary.toString());

        // Stream the game's output to our own console (no longer inherited automatically,
        // since the GUI needs to capture this same stream into its log panel).
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
        int exitCode = process.waitFor();
        System.out.println("Game exited with code " + exitCode);
    }
}
