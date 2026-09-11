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
 * Iris (the shaders mod) is a Fabric/Quilt-only mod that sits on top of Sodium to
 * provide shader support. Every DEY Fabric build ships Sodium, so it also ships Iris
 * so players get shaders out of the box -- exactly the same pattern SodiumInstaller
 * uses (fetch the build matching mcVersion+loader from Modrinth's public API).
 *
 * Iris has no Forge port under a usable name that we auto-bundle (Oculus exists for
 * Forge, but DEY client builds are Fabric-only), so like Fabric API this is only ever
 * installed for the "Fabric" loader.
 */
public class IrisInstaller {

    private static final String PROJECT_SLUG = "iris";

    private final HttpClient http = HttpClient.newHttpClient();

    /** True if a matching Iris jar is already present in this instance's mods folder. */
    public boolean isInstalled(Path modsDir) throws Exception {
        if (!Files.isDirectory(modsDir)) return false;
        String prefix = familyPrefix();
        try (var stream = Files.list(modsDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().toLowerCase().startsWith(prefix));
        }
    }

    private String familyPrefix() {
        return PROJECT_SLUG.toLowerCase() + "-";
    }

    /**
     * Sodium↔Iris conflict disarming: moves every active Iris jar into mods-disabled (never deletes)
     * so Iris cannot load. Used when the performance mod (Sodium) can't run for the selected Minecraft
     * version -- Iris needs Sodium as its runtime backend, so leaving Iris active without it just
     * compounds the crash. Returns how many jars were disabled.
     */
    public int disableActive(Path modsDir) {
        return ModsUtil.disableActiveFamily(modsDir, familyPrefix()).size();
    }

    /**
     * Ensures the Iris build matching mcVersion (Fabric) is installed and is the ONE that runs. Only
     * EXACTLY matching Minecraft versions are considered (a build for "1.19" is NOT reused for
     * "1.19.3"), preferring stable releases. A stale/wrong-version Iris jar is replaced
     * (updated/downgraded) -- never just deleted. If Modrinth has no build for this EXACT version, any
     * existing active Iris jar is moved to mods-disabled (disabled, NOT deleted) so the game still
     * loads. Returns the installed file's name, a "DISABLED:<names>" marker, or null.
     */
    public String ensureInstalled(String mcVersion, Path modsDir) throws Exception {
        String listUrl = "https://api.modrinth.com/v2/project/" + PROJECT_SLUG + "/version";
        HttpRequest req = HttpRequest.newBuilder(URI.create(listUrl))
                .header("User-Agent", "DeyLauncher/0.1 (+" + PROJECT_SLUG + "-auto-install)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Modrinth request failed: HTTP " + resp.statusCode());
        }
        JsonArray versions = JsonParser.parseString(resp.body()).getAsJsonArray();

        JsonObject best = findBestCompatible(versions, mcVersion);
        if (best == null) {
            // No build supports this EXACT Minecraft version. Never delete Iris to "fix" it -- disable
            // (move to mods-disabled) any active Iris jar so the game loads without shaders.
            java.util.List<String> disabled = ModsUtil.disableActiveFamily(modsDir, familyPrefix());
            if (!disabled.isEmpty()) return "DISABLED:" + String.join(",", disabled);
            return null;
        }
        return installBest(best, modsDir);
    }

    /**
     * Like {@link #ensureInstalled}, but installs a SPECIFIC resolved build (by its Modrinth version
     * number) instead of picking "the newest" itself. Used by the coordinated {@link ModPairResolver}
     * so the installed Iris always matches the resolved Sodium version chosen for the same target --
     * never a pair the loader would reject with a HARD_DEP/NEG_HARD_DEP. Falls back to the newest
     * compatible build if the exact version isn't found; disables any active Iris if none exists.
     */
    public String ensureVersion(String mcVersion, Path modsDir, String targetVersion) throws Exception {
        String listUrl = "https://api.modrinth.com/v2/project/" + PROJECT_SLUG + "/version";
        HttpRequest req = HttpRequest.newBuilder(URI.create(listUrl))
                .header("User-Agent", "DeyLauncher/0.1 (+" + PROJECT_SLUG + "-auto-install)")
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

    /** Finds the version whose {@code version_number} equals {@code targetVersion}, for this MC version (Fabric). */
    private JsonObject findSpecific(JsonArray versions, String mcVersion, String targetVersion) {
        if (targetVersion == null || targetVersion.isBlank()) return null;
        for (var el : versions) {
            JsonObject v = el.getAsJsonObject();
            String num = v.has("version_number") ? v.get("version_number").getAsString() : "";
            if (!num.equals(targetVersion)) continue;
            if (!supportsLoader(v, "fabric") || !supportsVersion(v, mcVersion)) continue;
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
            throw new IllegalStateException(PROJECT_SLUG + " download failed: HTTP " + fileResp.statusCode());
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);

        // Replace: drop any other (stale / wrong-version) Iris jar -- an update/downgrade, not a bare delete.
        ModsUtil.removeFamilyJarsExcept(modsDir, prefix, fileName);
        return fileName;
    }

    /** Newest EXACT-matching build, preferring stable releases over pre-releases. */
    private JsonObject findBestCompatible(JsonArray versions, String mcVersion) {
        JsonObject newest = null, newestStable = null;
        for (var el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (!supportsLoader(v, "fabric") || !supportsVersion(v, mcVersion)) continue;
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

    private boolean supportsLoader(JsonObject version, String loaderName) {
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