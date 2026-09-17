package com.deylauncher.modloader;

import com.deylauncher.version.VersionManifest;
import com.deylauncher.version.VersionResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * NeoForge ("NeoForged") support, alongside {@link FabricInstaller} and {@link ForgeInstaller}.
 *
 * <p>NeoForge began life as a fork of Forge and still ships Forge's installer code
 * ({@code net.minecraftforge.installer.*}), so the integration is deliberately the same shape as
 * {@link ForgeInstaller}: download the project's own official installer jar and run it headless
 * ({@code --installClient <dir>}), then read the version profile it writes. Reimplementing the
 * patch/processor pipeline would be fragile and version-specific for no benefit.
 *
 * <p>The naming rules that make this work were both verified against the real artifacts rather than
 * guessed:
 * <ul>
 *   <li><b>Artifact + version scheme.</b> From Minecraft 1.20.2 onwards NeoForge publishes under
 *       {@code net/neoforged/neoforge} with its own numbering that drops the leading {@code 1.} of
 *       the Minecraft version -- MC {@code 1.21.1} is NeoForge {@code 21.1.x}, MC {@code 26.2} is
 *       {@code 26.2.x}. The one exception is MC {@code 1.20.1}, which predates that scheme and lives
 *       under {@code net/neoforged/forge} as {@code 1.20.1-47.1.x} (see {@link Target}).</li>
 *   <li><b>Profile id.</b> The installer names the client profile after its own install_profile.json
 *       "version": {@code neoforge-<version>} for the modern artifact ({@code neoforge-26.2.0.88}),
 *       and simply the Maven version for the legacy one ({@code 1.20.1-forge-47.1.106}). Getting this
 *       wrong means the "already installed" check never matches, so the installer re-runs on every
 *       single launch. See {@link #profileId}.</li>
 * </ul>
 *
 * <p>Like Forge's, NeoForge's {@code ClientInstall} refuses to run unless the target folder contains
 * a launcher profile file, so {@link ForgeInstaller#ensureLauncherProfiles} is reused here.
 *
 * <p>This class is shared by the client launch path and {@code ServerDownloader} (which runs the
 * same installer with {@code --installServer}).
 */
public class NeoForgeInstaller {

    /** NeoForged's own Maven -- where their website's download buttons resolve to. */
    private static final String MAVEN_ROOT = "https://maven.neoforged.net/releases/net/neoforged/";

    /** How many trailing lines of the installer's own output we keep to explain a failure. */
    private static final int OUTPUT_TAIL_LINES = 40;

    private static final Pattern VERSION_ELEMENT = Pattern.compile("<version>([^<]+)</version>");

    /**
     * Which Maven artifact holds NeoForge for a Minecraft version, and how its version strings start.
     *
     * @param artifactId    Maven path segment under {@code net/neoforged/}
     * @param versionPrefix every usable version string starts with this (which is also what makes
     *                      "newest build for THIS Minecraft version" cheap and exact)
     * @param legacyForge   true for the 1.20.1-era {@code net/neoforged/forge} artifact, whose version
     *                      string doubles as the installed client profile id
     */
    public record Target(String artifactId, String versionPrefix, boolean legacyForge) {}

    private final HttpClient http = HttpClient.newHttpClient();
    private final VersionResolver resolver;
    private final Path root;

    /** Where the installer's own output goes line by line, so a failure is never silent. */
    private Consumer<String> outputSink = line -> { };

    public NeoForgeInstaller(VersionManifest manifest, Path launcherRoot) {
        this.resolver = new VersionResolver(manifest);
        this.root = launcherRoot;
    }

    /**
     * Streams each line the NeoForge installer prints to {@code sink} as it happens. Optional:
     * without it the output is still captured and attached to any failure message, it just isn't
     * streamed live.
     */
    public NeoForgeInstaller withOutputSink(Consumer<String> sink) {
        if (sink != null) this.outputSink = sink;
        return this;
    }

    /**
     * The Maven artifact + version prefix NeoForge uses for {@code mcVersion}, or null when NeoForge
     * has no build for it at all (the 1.16-1.19 era, 1.20.0, or a snapshot id).
     *
     * <p>This only maps the Minecraft version onto NeoForge's naming scheme; whether a build actually
     * exists is decided against the live Maven metadata in {@link #latestVersion}, so an unsupported
     * version reports honestly instead of being guessed at.
     */
    public static Target targetFor(String mcVersion) {
        if (mcVersion == null) return null;
        String v = mcVersion.trim();
        if (v.isEmpty()) return null;

        // 1.20.1 predates NeoForge's own version scheme and is published under the legacy "forge"
        // artifact as "<mc>-47.1.x" (verified: its install_profile.json "version" is
        // "1.20.1-forge-47.1.106", i.e. that same string is the client profile id it writes).
        if (v.equals("1.20.1")) return new Target("forge", "1.20.1-", true);

        // Real Minecraft versions start with "1." (e.g. "1.21.1", "1.20.4")
        if (v.startsWith("1.")) {
            // Modern scheme: drop the leading "1." and keep the first two numeric segments.
            //   1.21.1 -> 21.1.   1.21.11 -> 21.11.   1.20.4 -> 20.4.
            String trimmed = v.substring(2);
            String[] parts = trimmed.split("\\.");
            if (parts.length < 2) return null; // e.g. "1.20" (there is no NeoForge 1.20.0) or a snapshot id
            for (int i = 0; i < 2; i++) {
                if (parts[i].isEmpty() || !parts[i].chars().allMatch(Character::isDigit)) return null;
            }

            // 1.20.x (except 1.20.1 which is handled above) has no NeoForge builds.
            int major = Integer.parseInt(parts[0]);
            if (major < 20) return null; // 1.16-1.19 predate NeoForge entirely

            return new Target("neoforge", parts[0] + "." + parts[1] + ".", false);
        }

        // Synthetic DeyLauncher versions like "26.2", "26.3" (for 1.21+)
        // Must have exactly two numeric segments, no extra parts (so "26.3-snapshot-2" is rejected).
        String[] parts = v.split("\\.");
        if (parts.length != 2) return null;
        for (int i = 0; i < 2; i++) {
            if (parts[i].isEmpty() || !parts[i].chars().allMatch(Character::isDigit)) return null;
        }
        // Synthetic versions 20+ map to 1.21+ NeoForge
        int major = Integer.parseInt(parts[0]);
        if (major < 20) return null;
        return new Target("neoforge", parts[0] + "." + parts[1] + ".", false);
    }

    /**
     * The client version-profile id the installer writes for {@code neoVersion}, e.g.
     * {@code neoforge-26.2.0.88} (modern) or {@code 1.20.1-forge-47.1.106} (legacy 1.20.1).
     */
    public static String profileId(Target target, String neoVersion) {
        if (target != null && target.legacyForge()) {
            // Legacy profile id uses "forge" in the name: 1.20.1-forge-47.1.106
            String prefix = target.versionPrefix(); // "1.20.1-"
            if (neoVersion.startsWith(prefix)) {
                return prefix + "forge-" + neoVersion.substring(prefix.length());
            }
            return prefix + "forge-" + neoVersion;
        }
        return "neoforge-" + neoVersion;
    }

    /** The version profile file for {@code profileId} under the launcher root. */
    public static Path profileFile(Path root, String profileId) {
        return root.resolve("versions").resolve(profileId).resolve(profileId + ".json");
    }

    /** Public download URL of the official installer jar for {@code target}+{@code neoVersion}. */
    public static String installerUrl(Target target, String neoVersion) {
        return MAVEN_ROOT + target.artifactId() + "/" + neoVersion + "/"
                + target.artifactId() + "-" + neoVersion + "-installer.jar";
    }

    /**
     * Every NeoForge version published for this Minecraft version, in Maven's own (ascending) order.
     * Empty when NeoForge has nothing for it -- which is the honest answer for 1.16-1.19, 1.20.0,
     * and for a Minecraft release so new that NeoForge hasn't published for it yet.
     */
    public List<String> availableVersions(String mcVersion) throws Exception {
        Target target = targetFor(mcVersion);
        if (target == null) return List.of();

        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(MAVEN_ROOT + target.artifactId() + "/maven-metadata.xml"))
                .header("User-Agent", "DeyLauncher/0.1 (+neoforge-auto-install)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("NeoForge version list request failed: HTTP " + resp.statusCode());
        }

        List<String> out = new ArrayList<>();
        Matcher m = VERSION_ELEMENT.matcher(resp.body());
        while (m.find()) {
            String version = m.group(1).trim();
            if (version.startsWith(target.versionPrefix())) out.add(version);
        }
        return out;
    }

    /**
     * The NeoForge build to install for this Minecraft version: the newest STABLE one when the
     * project has any for that version, otherwise the newest published build. A brand-new Minecraft
     * release typically has only betas for a while (26.3's first builds are {@code 26.3.0.x-beta}),
     * and refusing those would just mean "NeoForge doesn't work" on the newest version.
     *
     * <p>Returns null when NeoForge has no build for this Minecraft version at all.
     */
    public String latestVersion(String mcVersion) throws Exception {
        return pickLatest(availableVersions(mcVersion));
    }

    /**
     * Newest STABLE build when the list has one, otherwise the newest build at all; null for an empty
     * list. Package-private so the choice (e.g. "26.3 only has betas -- take 26.3.0.1-beta rather than
     * refusing to install") is unit-testable without a network round-trip.
     */
    static String pickLatest(List<String> versions) {
        String newest = null;
        String newestStable = null;
        for (String v : versions) {
            if (newest == null || compareVersions(v, newest) > 0) newest = v;
            if (!isPreRelease(v) && (newestStable == null || compareVersions(v, newestStable) > 0)) {
                newestStable = v;
            }
        }
        return newestStable != null ? newestStable : newest;
    }

    /**
     * Downloads (if needed) and runs NeoForge's installer for {@code mcVersion}+{@code neoVersion},
     * then returns the resulting version profile merged with vanilla.
     *
     * <p>javaBinary must be a runtime that works for this Minecraft version (from
     * {@code JavaRuntimeManager}) -- the installer is itself a Java program.
     */
    public JsonObject install(String mcVersion, String neoVersion, String javaBinary) throws Exception {
        Target target = targetFor(mcVersion);
        if (target == null) {
            throw new IllegalStateException("NeoForge has no build for Minecraft " + mcVersion + " yet.");
        }
        String profileId = profileId(target, neoVersion);
        Path installed = profileFile(root, profileId);
        if (Files.exists(installed)) {
            // Already installed by a previous run: reuse the profile NeoForge wrote and skip the
            // (slow) installer entirely -- the same fast path ForgeInstaller uses.
            return resolver.resolve(readJson(installed));
        }
        return resolver.resolve(runInstallerAndFindProfile(target, neoVersion, profileId, javaBinary));
    }

    private JsonObject runInstallerAndFindProfile(Target target, String neoVersion, String profileId,
                                                  String javaBinary) throws Exception {
        Path installerJar = downloadInstaller(target, neoVersion);
        // NeoForge's ClientInstall requires a launcher profile in the target folder, exactly like
        // Forge's does -- it merges its own entry into that file and NPEs without it.
        ForgeInstaller.ensureLauncherProfiles(root);

        Path versionsDir = root.resolve("versions");
        Files.createDirectories(versionsDir);
        Set<String> before = listDirNames(versionsDir);

        Deque<String> tail = new ArrayDeque<>();
        ProcessBuilder pb = new ProcessBuilder(javaBinary, "-jar", installerJar.toString(),
                "--installClient", root.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
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
            throw new IllegalStateException("NeoForge installer exited with code " + exit + ". "
                    + explainInstallFailure(tail) + "\n" + tailText(tail));
        }

        // Trust the exact id first, then "a profile that wasn't there before", then the expected id
        // already present (an earlier run installed it and this run had nothing new to add).
        if (Files.exists(profileFile(root, profileId))) return readJson(profileFile(root, profileId));

        Set<String> after = listDirNames(versionsDir);
        after.removeAll(before);
        String versionId = after.stream()
                .filter(id -> id.contains("neoforge") || id.contains("forge"))
                .findFirst().orElse(null);
        if (versionId == null) {
            versionId = listDirNames(versionsDir).stream()
                    .filter(id -> id.contains("neoforge") || id.contains("forge"))
                    .findFirst().orElse(null);
        }
        if (versionId == null) {
            throw new IllegalStateException("NeoForge installer finished but no version profile was found under "
                    + versionsDir + ".\n" + tailText(tail));
        }
        return readJson(versionsDir.resolve(versionId).resolve(versionId + ".json"));
    }

    /** Downloads NeoForge's official installer jar for {@code neoVersion}, reusing a cached copy. */
    public Path downloadInstaller(Target target, String neoVersion) throws Exception {
        Path installerJar = root.resolve("forge-installers")
                .resolve(target.artifactId() + "-" + neoVersion + "-installer.jar");
        if (Files.exists(installerJar) && Files.size(installerJar) > 0) return installerJar;

        Files.createDirectories(installerJar.getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(installerUrl(target, neoVersion))).GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(installerJar));
        if (resp.statusCode() >= 400) {
            // ofFile() has already created the target; an empty jar left behind would be reused forever.
            Files.deleteIfExists(installerJar);
            throw new IllegalStateException("No NeoForge installer found for " + neoVersion
                    + " (" + resp.statusCode() + ") -- this NeoForge build may not exist.");
        }
        return installerJar;
    }

    /** Turns the installer's own last words into a plain-language hint, when we recognise them. */
    private static String explainInstallFailure(Deque<String> tail) {
        String joined = String.join("\n", tail);
        if (joined.contains("no Minecraft launcher profile")) {
            return "NeoForge needs a launcher_profiles.json in the target folder (DeyLauncher creates one "
                    + "automatically, so if you see this it was deleted or the folder isn't writable).";
        }
        if (joined.contains("no Minecraft installation at")) {
            return "NeoForge could not find the target folder -- it must exist before the installer runs.";
        }
        if (joined.contains("Unsupported class file major version") || joined.contains("class file version")) {
            return "This NeoForge build wants a different Java version than the one used to install it.";
        }
        return "NeoForge's own output is included below.";
    }

    /** The captured output, or a note that it produced none (some failures die before printing). */
    private static String tailText(Deque<String> tail) {
        if (tail.isEmpty()) return "(the NeoForge installer printed no output)";
        StringBuilder sb = new StringBuilder("NeoForge installer said:\n");
        for (String line : tail) sb.append("  ").append(line).append('\n');
        return sb.toString();
    }

    /** True when a version string looks like an alpha/beta/rc/pre build (NeoForge uses "-beta"). */
    static boolean isPreRelease(String version) {
        String v = version == null ? "" : version.toLowerCase();
        return v.contains("alpha") || v.contains("beta") || v.contains("rc") || v.contains("pre");
    }

    /** Compares dotted version strings numerically ("26.3.0.1-beta" vs "26.2.0.88"); 0 when equal. */
    static int compareVersions(String a, String b) {
        int[] av = numericParts(a);
        int[] bv = numericParts(b);
        int n = Math.max(av.length, bv.length);
        for (int i = 0; i < n; i++) {
            int left = i < av.length ? av[i] : 0;
            int right = i < bv.length ? bv[i] : 0;
            if (left != right) return Integer.compare(left, right);
        }
        return 0;
    }

    private static int[] numericParts(String s) {
        List<Integer> parts = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(s == null ? "" : s);
        while (m.find() && parts.size() < 8) parts.add(Integer.parseInt(m.group()));
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = parts.get(i);
        return out;
    }

    private static JsonObject readJson(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private Set<String> listDirNames(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }
}
