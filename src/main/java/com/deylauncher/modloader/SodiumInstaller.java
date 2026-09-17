package com.deylauncher.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Every DEY build ships a Sodium-family performance mod, fetched from Modrinth's public,
 * documented API (the same kind of official integration point as Fabric's meta API, see
 * FabricInstaller) -- not reverse-engineered or scraped.
 *
 * Real Sodium is Fabric/Quilt-only; it doesn't run on older Forge at all. For a DEY Forge instance
 * this installs Embeddium instead -- the actual Forge-compatible continuation of Sodium (same
 * renderer, same author lineage), rather than silently doing nothing or claiming to install
 * something that can't work under Forge.
 *
 * NeoForge is its own case again: Sodium itself publishes NeoForge builds (Modrinth lists them under
 * the "neoforge" loader, e.g. {@code mc26.3-0.9.2-neoforge}), so NeoForge gets Sodium -- not
 * Embeddium -- which is what NeoForge players actually run.
 */
public class SodiumInstaller {

    private final String projectSlug;
    private final String loaderName;
    private final HttpClient http = HttpClient.newHttpClient();

    public SodiumInstaller(String modLoader) {
        if ("Forge".equalsIgnoreCase(modLoader)) {
            this.projectSlug = "embeddium";
            this.loaderName = "forge";
        } else if ("NeoForge".equalsIgnoreCase(modLoader)) {
            this.projectSlug = "sodium";
            this.loaderName = "neoforge";
        } else {
            this.projectSlug = "sodium";
            this.loaderName = "fabric";
        }
    }

    /** True if a matching performance-mod jar is already present in this instance's mods folder. */
    public boolean isInstalled(Path modsDir) throws Exception {
        if (!Files.isDirectory(modsDir)) return false;
        String prefix = familyPrefix();
        try (var stream = Files.list(modsDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().toLowerCase().startsWith(prefix));
        }
    }

    private String familyPrefix() {
        return projectSlug.toLowerCase() + "-";
    }

    /**
     * Ensures the build matching mcVersion+loader is installed into modsDir and is the ONE that runs.
     * Only EXACTLY matching Minecraft versions are considered (a build for "1.19" is NOT reused for
     * "1.19.3"), and stable releases are preferred over pre-releases. A stale/wrong-version jar of this
     * family is replaced (updated or downgraded) with the compatible build -- never simply deleted. If
     * Modrinth has no build for this EXACT version, any existing active jar of this family is moved to
     * mods-disabled (disabled, NOT deleted) so the game still loads. Returns the installed file name,
     * a "DISABLED:<names>" marker, or null if nothing changed.
     */
    public String ensureInstalled(String mcVersion, Path modsDir) throws Exception {
        String listUrl = "https://api.modrinth.com/v2/project/" + projectSlug + "/version";
        HttpRequest req = HttpRequest.newBuilder(URI.create(listUrl))
                .header("User-Agent", "DeyLauncher/0.1 (+" + projectSlug + "-auto-install)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Modrinth request failed: HTTP " + resp.statusCode());
        }
        JsonArray versions = JsonParser.parseString(resp.body()).getAsJsonArray();

        JsonObject best = findBestCompatible(versions, mcVersion);
        if (best == null) {
            // No build supports this EXACT Minecraft version. Never delete a bundled mod to "fix"
            // it -- disable (move to mods-disabled) any active jar of this family so the game still
            // loads without the incompatible mod, while the jar stays on disk.
            java.util.List<String> disabled = ModsUtil.disableActiveFamily(modsDir, familyPrefix());
            if (!disabled.isEmpty()) return "DISABLED:" + String.join(",", disabled);
            return null;
        }
        return installBest(best, modsDir);
    }

    /**
     * Like {@link #ensureInstalled}, but installs a SPECIFIC resolved build (by its Modrinth
     * version number) instead of picking "the newest" itself. This is what lets the coordinated
     * {@link ModPairResolver} install the newest Sodium that is actually compatible with the chosen
     * Iris (stepping Sodium down when the newest one breaks Iris -- see the 26.2 fix). If the exact
     * version isn't found for this MC version it falls back to the newest compatible build; if none
     * exists at all, any active jar of the family is disabled (never deleted).
     */
    public String ensureVersion(String mcVersion, Path modsDir, String targetVersion) throws Exception {
        String listUrl = "https://api.modrinth.com/v2/project/" + projectSlug + "/version";
        HttpRequest req = HttpRequest.newBuilder(URI.create(listUrl))
                .header("User-Agent", "DeyLauncher/0.1 (+" + projectSlug + "-auto-install)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Modrinth request failed: HTTP " + resp.statusCode());
        }
        JsonArray versions = JsonParser.parseString(resp.body()).getAsJsonArray();

        JsonObject best = findSpecific(versions, mcVersion, targetVersion);
        if (best == null) best = findBestCompatible(versions, mcVersion); // fall back to newest
        if (best == null) {
            java.util.List<String> disabled = ModsUtil.disableActiveFamily(modsDir, familyPrefix());
            if (!disabled.isEmpty()) return "DISABLED:" + String.join(",", disabled);
            return null;
        }
        return installBest(best, modsDir);
    }

    /** Finds the version whose {@code version_number} equals {@code targetVersion}, for this MC version + loader. */
    private JsonObject findSpecific(JsonArray versions, String mcVersion, String targetVersion) {
        if (targetVersion == null || targetVersion.isBlank()) return null;
        for (var el : versions) {
            JsonObject v = el.getAsJsonObject();
            String num = v.has("version_number") ? v.get("version_number").getAsString() : "";
            if (!num.equals(targetVersion)) continue;
            if (!supportsLoader(v) || !supportsVersion(v, mcVersion)) continue;
            if (v.has("files") && !v.getAsJsonArray("files").isEmpty()) return v;
        }
        return null;
    }

    /** Shared tail of both install paths: download (or re-enable) the chosen build and drop stale jars. */
    private String installBest(JsonObject best, Path modsDir) throws Exception {
        JsonObject file = primaryFile(best.getAsJsonArray("files"));
        String fileName = file.get("filename").getAsString();
        String prefix = familyPrefix();
        Files.createDirectories(modsDir);

        Path active = ModsUtil.firstFamilyJar(modsDir, prefix);
        if (active != null && active.getFileName().toString().equalsIgnoreCase(fileName)) {
            return null; // the exact matching build is already installed
        }

        // Prefer re-enabling an exact copy already sitting in mods-disabled to re-downloading it.
        Path disabledCopy = ModsUtil.disabledDirFor(modsDir).resolve(fileName);
        if (Files.exists(disabledCopy) && ModsUtil.reenableDisabled(modsDir, disabledCopy)) {
            ModsUtil.removeFamilyJarsExcept(modsDir, prefix, fileName);
            return fileName;
        }

        Path dest = modsDir.resolve(fileName);
        Path tmp = modsDir.resolve(fileName + ".part");
        HttpResponse<Path> fileResp = http.send(
                HttpRequest.newBuilder(URI.create(file.get("url").getAsString())).GET().build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
        if (fileResp.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException(projectSlug + " download failed: HTTP " + fileResp.statusCode());
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);

        // Replace: drop any other (stale / wrong-version) jar of the family -- an update/downgrade,
        // not a bare delete.
        ModsUtil.removeFamilyJarsExcept(modsDir, prefix, fileName);
        return fileName;
    }

    /** Newest EXACT-matching build, preferring stable releases over pre-releases. */
    private JsonObject findBestCompatible(JsonArray versions, String mcVersion) {
        JsonObject newest = null, newestStable = null;
        for (var el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (!supportsLoader(v) || !supportsVersion(v, mcVersion)) continue;
            JsonArray files = v.getAsJsonArray("files");
            if (files == null || files.isEmpty()) continue;
            if (newest == null) newest = v;
            if (!isPreRelease(v)) {
                if (newestStable == null) newestStable = v;
            }
        }
        return newestStable != null ? newestStable : newest;
    }

    /** True when a version number looks like an alpha/beta/pre/snapshot/dev build. */
    private boolean isPreRelease(JsonObject v) {
        String num = v.has("version_number") ? v.get("version_number").getAsString().toLowerCase() : "";
        return num.contains("alpha") || num.contains("beta") || num.contains("pre")
                || num.contains("snapshot") || num.contains("dev") || num.contains("nightly");
    }

    private boolean supportsLoader(JsonObject version) {
        if (!version.has("loaders")) return false;
        for (var l : version.getAsJsonArray("loaders")) {
            if (l.getAsString().equalsIgnoreCase(loaderName)) return true;
        }
        return false;
    }

    private boolean supportsVersion(JsonObject version, String mcVersion) {
        if (!version.has("game_versions")) return false;
        for (var gv : version.getAsJsonArray("game_versions")) {
            if (gv.getAsString().equals(mcVersion)) return true;
        }
        return false;
    }

    private JsonObject primaryFile(JsonArray files) {
        for (var f : files) {
            JsonObject obj = f.getAsJsonObject();
            if (obj.has("primary") && obj.get("primary").getAsBoolean()) return obj;
        }
        return files.get(0).getAsJsonObject();
    }
}
