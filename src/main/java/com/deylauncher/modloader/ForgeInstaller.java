package com.deylauncher.modloader;

import com.deylauncher.version.VersionResolver;
import com.deylauncher.version.VersionManifest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Forge doesn't publish a simple JSON profile like Fabric does -- its
 * "installer" is a real Java program that patches/generates files via an
 * internal processor pipeline. Reimplementing that pipeline ourselves would
 * be a lot of fragile, Forge-version-specific code. Instead we do what most
 * third-party tooling does: download Forge's own official installer jar and
 * run it in its documented headless mode (`--installClient <dir>`), then
 * read the version profile it wrote -- same result, far less to maintain
 * or get wrong when Forge changes its internals.
 *
 * Two preconditions of Forge's own installer are easy to trip over, and both used to make
 * `--installClient` fail with a bare exit code 1 and no explanation:
 *
 * <ol>
 *   <li>The target directory must contain a <b>launcher profile file</b> -- Forge's ClientInstall
 *       literally refuses to run without one ("There is no Minecraft launcher profile in
 *       "...", you need to run the launcher first!"). A third-party launcher's root folder never
 *       has one, so {@link #ensureLauncherProfiles} writes a minimal valid one first. Forge then
 *       injects its own profile entry into it, which is exactly how the official launcher ends up
 *       listing the installed Forge version.</li>
 *   <li>The profile it writes is named after <b>its own</b> version string (e.g.
 *       {@code 1.20.1-forge-47.4.20}), NOT {@code <mc>-<forge>-forge}. Getting that id wrong means
 *       the "already installed" check never matches, so the (slow) installer is re-run on every
 *       single launch -- and, worse, its "what's new in versions/" scan then finds nothing and
 *       throws even though the install succeeded. See {@link #profileId}.</li>
 * </ol>
 */
public class ForgeInstaller {

    private static final String PROMOTIONS_URL =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

    /** How many trailing lines of the installer's own output we keep to explain a failure. */
    private static final int OUTPUT_TAIL_LINES = 40;

    /**
     * The minimum a launcher profile file needs to be for Forge's installer: it parses the file and
     * merges its new entry into the existing "profiles" object, so that key has to be present (and
     * be an object) or the installer NPEs while writing the profile.
     */
    static final String LAUNCHER_PROFILES_TEMPLATE =
            "{\n  \"profiles\": {},\n  \"settings\": {},\n  \"version\": 3\n}\n";

    private final HttpClient http = HttpClient.newHttpClient();
    private final VersionResolver resolver;
    private final Path root;

    /** Where the installer's own output goes line by line, so a failure is never silent. */
    private Consumer<String> outputSink = line -> { };

    public ForgeInstaller(VersionManifest manifest, Path launcherRoot) {
        this.resolver = new VersionResolver(manifest);
        this.root = launcherRoot;
    }

    /**
     * Streams each line the Forge installer prints to {@code sink} as it happens (the caller is
     * responsible for hopping to the UI thread if it touches UI). Optional: without it the output is
     * still captured and attached to any failure message, it just isn't streamed live.
     */
    public ForgeInstaller withOutputSink(Consumer<String> sink) {
        if (sink != null) this.outputSink = sink;
        return this;
    }

    /** Recommended Forge build for this MC version, falling back to latest, or null if none exists. */
    public String recommendedOrLatestVersion(String mcVersion) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(PROMOTIONS_URL)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject promos = JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonObject("promos");

        if (promos.has(mcVersion + "-recommended")) return promos.get(mcVersion + "-recommended").getAsString();
        if (promos.has(mcVersion + "-latest")) return promos.get(mcVersion + "-latest").getAsString();
        return null;
    }

    /**
     * Downloads (if needed) and runs the Forge installer for mcVersion+forgeVersion, then
     * returns the resulting version profile merged with vanilla.
     *
     * javaBinary should be a runtime known to work for this MC version (e.g. from
     * JavaRuntimeManager) -- the installer itself is a Java program and needs one to run.
     */
    public JsonObject install(String mcVersion, String forgeVersion, String javaBinary) throws Exception {
        String profileId = profileId(mcVersion, forgeVersion);
        Path installed = profileFile(root, profileId);
        if (Files.exists(installed)) {
            // Already installed by a previous run: reuse the profile Forge wrote and skip the
            // installer entirely. Re-running it would work, but it is slow (a minute of patching)
            // and -- before the id above was corrected -- its "what's new under versions/" scan
            // found nothing to report and failed the launch even though the install was fine.
            return resolver.resolve(readJson(installed));
        }
        return resolver.resolve(runInstallerAndFindProfile(mcVersion, forgeVersion, profileId, javaBinary));
    }

    /** Forge writes its client profile under its OWN version string, e.g. "1.20.1-forge-47.4.20". */
    static String profileId(String mcVersion, String forgeVersion) {
        return mcVersion + "-forge-" + forgeVersion;
    }

    /** The version profile file Forge writes for {@code profileId} under the launcher root. */
    static Path profileFile(Path root, String profileId) {
        return root.resolve("versions").resolve(profileId).resolve(profileId + ".json");
    }

    /**
     * Forge's installer flatly refuses to run unless the target directory looks like a Minecraft
     * installation with a launch profile in it -- it needs somewhere to register the Forge version
     * it installs. A launcher's own root never has such a file, so we provide a minimal valid one
     * first; Forge then adds its own entry to the "profiles" object (this is exactly how the
     * official Minecraft launcher ends up listing the installed Forge build).
     *
     * Only created when neither recognised file exists, so a real launcher profile (or a previous
     * one of ours, already carrying Forge's entry) is never overwritten.
     */
    static void ensureLauncherProfiles(Path root) throws IOException {
        Files.createDirectories(root);
        Path standard = root.resolve("launcher_profiles.json");
        Path microsoftStore = root.resolve("launcher_profiles_microsoft_store.json");
        if (Files.exists(standard) || Files.exists(microsoftStore)) return; // either satisfies the installer
        Files.writeString(standard, LAUNCHER_PROFILES_TEMPLATE);
    }

    private JsonObject runInstallerAndFindProfile(String mcVersion, String forgeVersion,
                                                   String profileId, String javaBinary) throws Exception {
        String longVersion = mcVersion + "-" + forgeVersion; // the maven artifact version, e.g. 1.20.1-47.4.20
        Path installerJar = downloadInstaller(longVersion);
        ensureLauncherProfiles(root);

        Path versionsDir = root.resolve("versions");
        Files.createDirectories(versionsDir);
        var before = listDirNames(versionsDir);

        Deque<String> tail = new ArrayDeque<>();
        ProcessBuilder pb = new ProcessBuilder(javaBinary, "-jar", installerJar.toString(),
                "--installClient", root.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Read the installer's output line by line instead of draining it into nothing: this is the
        // ONLY place Forge explains itself, and a bare "exit code 1" is impossible to act on. The
        // last lines are kept so a failure can quote them.
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == OUTPUT_TAIL_LINES) tail.removeFirst();
                tail.addLast(line);
                outputSink.accept(line);
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Forge installer exited with code " + exit + ". "
                    + explainInstallFailure(tail) + "\n" + tailText(tail));
        }

        // Forge normally reports a brand-new version id. Trust the exact id first; then fall back to
        // "a forge profile that wasn't there before", and finally to "the forge profile we expected,
        // already present" -- the case where the installer had nothing new to add because an earlier
        // run already installed it.
        if (Files.exists(profileFile(root, profileId))) return readJson(profileFile(root, profileId));

        var after = listDirNames(versionsDir);
        after.removeAll(before);
        String versionId = after.stream().filter(id -> id.contains("forge")).findFirst().orElse(null);
        if (versionId == null) {
            // Nothing NEW appeared but a forge profile is already present: an earlier run installed
            // it and this run simply had nothing to add. Reuse it rather than failing the launch.
            versionId = listDirNames(versionsDir).stream()
                    .filter(id -> id.contains("forge")).findFirst().orElse(null);
        }
        if (versionId == null) {
            throw new IllegalStateException("Forge installer finished but no version profile was found under "
                    + versionsDir + ".\n" + tailText(tail));
        }
        return readJson(versionsDir.resolve(versionId).resolve(versionId + ".json"));
    }

    /** Downloads Forge's official installer jar for {@code longVersion}, reusing a cached copy. */
    private Path downloadInstaller(String longVersion) throws Exception {
        Path installerJar = root.resolve("forge-installers").resolve("forge-" + longVersion + "-installer.jar");
        if (Files.exists(installerJar) && Files.size(installerJar) > 0) return installerJar;

        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + longVersion
                + "/forge-" + longVersion + "-installer.jar";
        Files.createDirectories(installerJar.getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(installerJar));
        if (resp.statusCode() >= 400) {
            // ofFile() has already created the target; leaving an empty jar behind would make every
            // later run reuse a broken installer, so clean it up before reporting.
            Files.deleteIfExists(installerJar);
            throw new IllegalStateException("No Forge installer found for " + longVersion
                    + " (" + resp.statusCode() + ") -- this Forge build may not exist.");
        }
        return installerJar;
    }

    /** Turns the installer's own last words into a plain-language hint, when we recognise them. */
    private static String explainInstallFailure(Deque<String> tail) {
        String joined = String.join("\n", tail);
        if (joined.contains("no Minecraft launcher profile")) {
            return "Forge needs a launcher_profiles.json in the target folder (DeyLauncher creates one "
                    + "automatically, so if you see this it was deleted or the folder isn't writable).";
        }
        if (joined.contains("no Minecraft installation at")) {
            return "Forge could not find the target folder -- it must exist before the installer runs.";
        }
        if (joined.contains("Unsupported class file major version") || joined.contains("class file version")) {
            return "This Forge build wants a different Java version than the one used to install it.";
        }
        return "Forge's own output is included below.";
    }

    /** The captured output, or a note that it produced none (some failures die before printing). */
    private static String tailText(Deque<String> tail) {
        if (tail.isEmpty()) return "(the Forge installer printed no output)";
        StringBuilder sb = new StringBuilder("Forge installer said:\n");
        for (String line : tail) sb.append("  ").append(line).append('\n');
        return sb.toString();
    }

    private static JsonObject readJson(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private java.util.Set<String> listDirNames(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }
}
