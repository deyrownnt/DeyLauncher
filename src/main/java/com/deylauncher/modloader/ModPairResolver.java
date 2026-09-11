package com.deylauncher.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the bundled performance-mod pair (Sodium on Fabric / Embeddium on Forge, plus Iris for
 * shaders) to a MUTUALLY COMPATIBLE set of versions instead of blindly installing "the newest of
 * each". This is what fixes the DEY 26.2 crash from the user's logs:
 *
 * <pre>
 *   HARD_DEP     iris 1.11.2+mc26.2 { depends sodium @ [0.9.x] }
 *   NEG_HARD_DEP sodium 0.9.2+mc26.2 { breaks iris @ [&lt;=1.11.2] }   &lt;- newest Sodium breaks newest Iris
 * </pre>
 *
 * Taking the newest of each project independently is provably wrong: Sodium 0.9.2 deliberately
 * declares it breaks Iris {@code <=1.11.2}, but Iris 1.11.2 is the newest Iris that exists for 26.2.
 * The correct, "auto-fix to the right version" behaviour is to follow the chosen Iris build's own
 * pinned {@code required} Sodium dependency (Modrinth records it as a {@code version_id} under the
 * Sodium project -- Iris 1.11.2 pins Sodium 0.9.1), so we install exactly the Sodium build that Iris
 * was built against instead of the newest one that breaks it. Only if no compatible Sodium exists at
 * all do we disable Iris (never delete it).
 *
 * Fabric API is version-independent of both (it only depends on Fabric + the MC version), so it is
 * still just "install the newest for this version". DeyCapes is bundled locally, never fetched from
 * the internet, so it is untouched here (see DeyCapesInstaller).
 */
public class ModPairResolver {

    private static final String API = "https://api.modrinth.com/v2/project";

    /** The coordinated plan for one Minecraft version. {@code irisVersion} null + {@code disableIris} = no Iris. */
    public record ResolvedPlan(String mcVersion, String sodiumVersion, String irisVersion, boolean disableIris) {}

    private final HttpClient http = HttpClient.newHttpClient();

    public String ensureDeyMods(String loader, String mcVersion, Path modsDir) throws Exception {
        boolean fabric = "Fabric".equalsIgnoreCase(loader);
        List<String> logFlags = new ArrayList<>();

        if (fabric) {
            ResolvedPlan plan = resolve(mcVersion);
            if (plan.disableIris()) {
                List<String> disabled = ModsUtil.disableActiveFamily(modsDir, "iris-");
                logFlags.add(disabled.isEmpty() ? "DISABLED" : "DISABLED:" + String.join(",", disabled));
                new SodiumInstaller(loader).ensureVersion(mcVersion, modsDir, plan.sodiumVersion());
            } else {
                String sodium = new SodiumInstaller(loader).ensureVersion(mcVersion, modsDir, plan.sodiumVersion());
                if (sodium != null) logFlags.add(sodium);
                String iris = new IrisInstaller().ensureVersion(mcVersion, modsDir, plan.irisVersion());
                if (iris != null) logFlags.add(iris);
            }
            String api = new FabricApiInstaller().ensureInstalled(mcVersion, modsDir);
            if (api != null) logFlags.add(api);
        } else {
            String sodium = new SodiumInstaller(loader).ensureInstalled(mcVersion, modsDir);
            if (sodium != null) logFlags.add(sodium);
        }

        String capes = new DeyCapesInstaller().ensureInstalled(mcVersion, modsDir);
        if (capes != null) logFlags.add(capes);

        return logFlags.isEmpty() ? null : String.join(", ", logFlags);
    }

    /**
     * Computes the newest mutually-compatible (Sodium, Iris) pair for {@code mcVersion} on Fabric.
     * Picks the newest Iris for the version, then follows that Iris build's own pinned {@code
     * required} Sodium dependency (Modrinth records it as a {@code version_id} under the Sodium
     * project) -- so we install exactly the Sodium build Iris was built against, never the newest
     * Sodium that may break it. This is what fixes the DEY 26.2 crash, where the newest Sodium
     * (0.9.2) declares it breaks the newest Iris (1.11.2): Iris 1.11.2 pins Sodium 0.9.1, so the
     * resolver steps Sodium down to 0.9.1. Falls back to {@code disableIris=true} only when no
     * compatible Sodium exists at all. Only Fabric is coordinated this way (Iris has no Forge build).
     */
    public ResolvedPlan resolve(String mcVersion) throws Exception {
        return resolveWith(mcVersion, fetchVersions("iris"), fetchVersions("sodium"));
    }

    /**
     * Package-visible for tests (no network): same coordinated selection as {@link #resolve}, but
     * over already-fetched Modrinth version lists.
     */
    ResolvedPlan resolveWith(String mcVersion, JsonArray irises, JsonArray sodiums) {
        JsonObject iris = best(bestThatMatch(irises, mcVersion, "fabric"));
        if (iris == null) {
            // No Iris for this version at all -- nothing to coordinate; just newest Sodium.
            JsonObject sodium = best(bestThatMatch(sodiums, mcVersion, "fabric"));
            String sv = sodium != null ? versionNumber(sodium) : null;
            return new ResolvedPlan(mcVersion, sv, null, false);
        }
        String irisVersion = versionNumber(iris);

        // The authoritative pairing: Iris pins the exact Sodium version_id it was built against
        // (e.g. 1.11.2+26.2-fabric -> sodium version 2Yom1N68 = 0.9.1). Resolve that to a version_number.
        String pinned = pinnedSodiumVersion(iris, sodiumProjectId(sodiums), sodiums, mcVersion);
        if (pinned != null) {
            return new ResolvedPlan(mcVersion, pinned, irisVersion, false);
        }

        // No usable pin. Newest-first candidate that still satisfies Iris's declared Sodium range
        // (some Modrinth builds carry a version range instead of a pin). Range check uses the BASE
        // version ("+mcX.Y.Z" build tag stripped, since ranges refer to the plain version).
        String sodiumReq = requiredSodiumRange(iris);
        for (JsonObject s : bestThatMatch(sodiums, mcVersion, "fabric")) {
            String sv = versionNumber(s);
            if (sodiumReq != null && !MinecraftVersionRange.matches(sodiumReq, stripped(sv))) continue;
            return new ResolvedPlan(mcVersion, sv, irisVersion, false);
        }
        // No safe Sodium -> disable Iris (the old code's fallback); newest Sodium still goes in.
        JsonObject sodium = best(bestThatMatch(sodiums, mcVersion, "fabric"));
        String sv = sodium != null ? versionNumber(sodium) : null;
        return new ResolvedPlan(mcVersion, sv, irisVersion, true);
    }

    /**
     * The Sodium {@code version_id} that {@code iris} pins as its required dependency, resolved to
     * that Sodium version's {@code version_number} (matching on the already-fetched Sodium list's
     * {@code id} fields). Returns null when Iris has no usable pin, when the pin names a project
     * that isn't Sodium, or when the pinned build isn't downloadable for this MC version + loader.
     */
    private String pinnedSodiumVersion(JsonObject iris, String sodiumProjectId, JsonArray sodiums, String mcVersion) {
        if (sodiumProjectId == null || !iris.has("dependencies")) return null;
        String pinnedVersionId = null;
        for (var el : iris.getAsJsonArray("dependencies")) {
            JsonObject d = el.getAsJsonObject();
            String pid = d.has("project_id") ? d.get("project_id").getAsString() : null;
            if (pid == null || !pid.equals(sodiumProjectId)) continue;
            if (!"required".equals(depType(d))) continue;
            if (d.has("version_id") && !d.get("version_id").isJsonNull()) {
                pinnedVersionId = d.get("version_id").getAsString();
                break;
            }
        }
        if (pinnedVersionId == null) return null;
        for (var el : sodiums) {
            JsonObject s = el.getAsJsonObject();
            String id = s.has("id") ? s.get("id").getAsString() : null;
            if (id == null || !id.equals(pinnedVersionId)) continue;
            if (!supportsLoader(s, "fabric") || !supportsVersion(s, mcVersion)) continue;
            if (!s.has("files") || s.getAsJsonArray("files").isEmpty()) continue;
            return versionNumber(s);
        }
        return null;
    }

    /** Any Sodium project id (all entries of the Sodium version list share the same project_id). */
    private String sodiumProjectId(JsonArray sodiums) {
        for (var el : sodiums) {
            if (el.getAsJsonObject().has("project_id")) {
                return el.getAsJsonObject().get("project_id").getAsString();
            }
        }
        return null;
    }

    private JsonArray fetchVersions(String slug) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/" + slug + "/version"))
                .header("User-Agent", "DeyLauncher/0.8 (mod-pair-resolver)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Modrinth request failed: HTTP " + resp.statusCode());
        }
        return JsonParser.parseString(resp.body()).getAsJsonArray();
    }

    /** Candidates for the exact MC version + loader, newest first (stable-preferred), all downloadable. */
    private java.util.List<JsonObject> bestThatMatch(JsonArray versions, String mcVersion, String loader) {
        java.util.List<JsonObject> stable = new ArrayList<>();
        java.util.List<JsonObject> prerelease = new ArrayList<>();
        for (var el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (!v.has("files") || v.getAsJsonArray("files").isEmpty()) continue;
            if (!supportsLoader(v, loader) || !supportsVersion(v, mcVersion)) continue;
            (isPreRelease(v) ? prerelease : stable).add(v);
        }
        stable.sort((a, b) -> compareVersions(versionNumber(b), versionNumber(a)));
        prerelease.sort((a, b) -> compareVersions(versionNumber(b), versionNumber(a)));
        java.util.List<JsonObject> out = new ArrayList<>(stable);
        out.addAll(prerelease);
        return out;
    }

    /** Newest stable preferred over a pre-release (parallel to the installers' findBestCompatible). */
    private JsonObject best(java.util.List<JsonObject> candidates) {
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private String requiredSodiumRange(JsonObject iris) {
        if (iris.has("dependencies")) {
            for (var el : iris.getAsJsonArray("dependencies")) {
                JsonObject d = el.getAsJsonObject();
                if (!isSodiumDep(d) || !"required".equals(depType(d))) continue;
                if (d.has("version")) {
                    var v = d.get("version");
                    if (!v.isJsonNull() && !v.getAsString().isBlank()) return v.getAsString();
                }
            }
        }
        return null; // no usable declared range -> treat as unconstrained
    }

    private boolean isSodiumDep(JsonObject d) {
        String id = d.has("project_id") ? d.get("project_id").getAsString() : "";
        String name = d.has("project_name") ? d.get("project_name").getAsString().toLowerCase() : "";
        return "sodium".equals(name) || "sodium".equalsIgnoreCase(id);
    }

    private String depType(JsonObject d) {
        return d.has("dependency_type") ? d.get("dependency_type").getAsString().toLowerCase() : "";
    }

    private String versionNumber(JsonObject v) {
        return v.has("version_number") ? v.get("version_number").getAsString() : "";
    }

    /** Base version without the "+build"/"-prerelease" metadata tag (e.g. "0.9.2+mc26.2" -> "0.9.2"). */
    private String stripped(String v) {
        if (v == null) return null;
        int plus = v.indexOf('+');
        int dash = v.indexOf('-');
        int cut = -1;
        if (plus >= 0 && dash >= 0) cut = Math.min(plus, dash);
        else if (plus >= 0) cut = plus;
        else if (dash >= 0) cut = dash;
        return cut > 0 ? v.substring(0, cut) : v;
    }

    private boolean isPreRelease(JsonObject v) {
        String num = versionNumber(v).toLowerCase();
        return num.contains("alpha") || num.contains("beta") || num.contains("pre")
                || num.contains("snapshot") || num.contains("dev") || num.contains("nightly");
    }

    private boolean supportsLoader(JsonObject version, String loader) {
        if (!version.has("loaders")) return false;
        for (var l : version.getAsJsonArray("loaders")) {
            if (l.getAsString().equalsIgnoreCase(loader)) return true;
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

    /** Compares two dot-separated version numbers (e.g. "0.9.2" vs "0.9.1"); returns 0 if equal. */
    private int compareVersions(String a, String b) {
        int[] av = ints(a);
        int[] bv = ints(b);
        int n = Math.max(av.length, bv.length);
        for (int i = 0; i < n; i++) {
            int ai = i < av.length ? av[i] : 0;
            int bi = i < bv.length ? bv[i] : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private int[] ints(String s) {
        java.util.List<Integer> parts = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s == null ? "" : s);
        while (m.find() && parts.size() < 8) parts.add(Integer.parseInt(m.group()));
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = parts.get(i);
        return out;
    }
}