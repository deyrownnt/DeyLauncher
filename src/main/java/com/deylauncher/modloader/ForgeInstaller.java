package com.deylauncher.modloader;

import com.deylauncher.version.VersionResolver;
import com.deylauncher.version.VersionManifest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Forge doesn't publish a simple JSON profile like Fabric does -- its
 * "installer" is a real Java program that patches/generates files via an
 * internal processor pipeline. Reimplementing that pipeline ourselves would
 * be a lot of fragile, Forge-version-specific code. Instead we do what most
 * third-party tooling does: download Forge's own official installer jar and
 * run it in its documented headless mode (`--installClient <dir>`), then
 * read the version profile it wrote -- same result, far less to maintain
 * or get wrong when Forge changes its internals.
 */
public class ForgeInstaller {

    private static final String PROMOTIONS_URL =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

    private final HttpClient http = HttpClient.newHttpClient();
    private final VersionResolver resolver;
    private final Path root;

    public ForgeInstaller(VersionManifest manifest, Path launcherRoot) {
        this.resolver = new VersionResolver(manifest);
        this.root = launcherRoot;
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
        String longVersion = mcVersion + "-" + forgeVersion;
        String expectedProfileId = longVersion + "-forge"; // Forge's actual naming can vary slightly by build; see fallback scan below

        Path existingGuess = root.resolve("versions").resolve(expectedProfileId).resolve(expectedProfileId + ".json");
        JsonObject profile = Files.exists(existingGuess)
                ? JsonParser.parseString(Files.readString(existingGuess)).getAsJsonObject()
                : runInstallerAndFindProfile(mcVersion, longVersion, javaBinary);

        return resolver.resolve(profile);
    }

    private JsonObject runInstallerAndFindProfile(String mcVersion, String longVersion, String javaBinary) throws Exception {
        Path installerJar = root.resolve("forge-installers").resolve("forge-" + longVersion + "-installer.jar");
        if (!Files.exists(installerJar)) {
            String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + longVersion
                    + "/forge-" + longVersion + "-installer.jar";
            Files.createDirectories(installerJar.getParent());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(installerJar));
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("No Forge installer found for " + longVersion
                        + " (" + resp.statusCode() + ") -- this Forge build may not exist.");
            }
        }

        Path versionsDirBefore = root.resolve("versions");
        Files.createDirectories(versionsDirBefore);
        var before = Files.exists(versionsDirBefore) ? listDirNames(versionsDirBefore) : java.util.Set.<String>of();

        ProcessBuilder pb = new ProcessBuilder(javaBinary, "-jar", installerJar.toString(),
                "--installClient", root.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Drain output so the installer never blocks on a full pipe buffer; we don't need to
        // show this in the launcher UI, just let it run to completion.
        try (var in = process.getInputStream()) {
            in.readAllBytes();
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Forge installer exited with code " + exit
                    + " -- it may need a different Java version, or this build may be broken.");
        }

        var after = listDirNames(versionsDirBefore);
        after.removeAll(before);
        String newVersionId = after.stream().filter(id -> id.contains("forge")).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Forge installer finished but no new version profile was found under "
                                + versionsDirBefore + " -- check the installer's own output/log for what went wrong."));

        Path profilePath = versionsDirBefore.resolve(newVersionId).resolve(newVersionId + ".json");
        return JsonParser.parseString(Files.readString(profilePath)).getAsJsonObject();
    }

    private java.util.Set<String> listDirNames(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
}
