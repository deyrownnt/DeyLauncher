package com.deylauncher.version;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Fabric and modern Forge installers don't give you a fully standalone
 * version JSON -- they give you a small "profile" that says
 * `"inheritsFrom": "1.21.1"` and only lists what's *different* (extra
 * libraries, a different mainClass, extra JVM args for the mod loader's
 * own classloader). The official launcher merges that profile with the
 * vanilla parent at launch time; this does the same merge, so GameFiles
 * and GameLauncher never need to know a version is modded at all -- they
 * just see one complete, normal-looking version JSON either way.
 */
public class VersionResolver {

    private final VersionManifest manifest;

    public VersionResolver(VersionManifest manifest) {
        this.manifest = manifest;
    }

    /** Resolves inheritsFrom (if present) against the vanilla manifest and returns a fully merged JSON. */
    public JsonObject resolve(JsonObject profile) throws Exception {
        if (!profile.has("inheritsFrom")) {
            return profile; // plain vanilla version, nothing to merge
        }
        String parentId = profile.get("inheritsFrom").getAsString();
        var all = manifest.fetchAll();
        var parentEntry = manifest.findById(all, parentId);
        if (parentEntry == null) {
            throw new IllegalStateException("Mod loader profile inherits from \"" + parentId
                    + "\" but that version isn't in Mojang's manifest -- can't resolve it.");
        }
        JsonObject parent = manifest.fetchVersionDetail(parentEntry);
        return merge(profile, parent);
    }

    private JsonObject merge(JsonObject child, JsonObject parent) {
        JsonObject result = parent.deepCopy();

        // Fields the child always overrides outright when present.
        for (String simpleField : new String[]{"mainClass", "id"}) {
            if (child.has(simpleField)) result.add(simpleField, child.get(simpleField));
        }

        // Libraries: child's mod-loader libraries go first (so they win on the classpath),
        // vanilla's libraries follow -- this is exactly how the official launcher orders it.
        // Both lists can independently depend on the same artifact at different versions
        // (e.g. Fabric loader bundling a newer ASM than the one vanilla's version JSON lists) --
        // if we kept both, they'd collide on the classpath at launch (Fabric's own classpath
        // verifier rejects duplicate classes from two jars of the same library). So we dedupe by
        // Maven groupId:artifactId, keeping only the first occurrence -- the child's, since it's
        // added first -- which is what makes the "child wins" ordering above actually take effect.
        JsonArray mergedLibs = new JsonArray();
        java.util.Set<String> seenArtifacts = new java.util.HashSet<>();
        for (JsonArray libs : new JsonArray[]{
                child.has("libraries") ? child.getAsJsonArray("libraries") : new JsonArray(),
                parent.has("libraries") ? parent.getAsJsonArray("libraries") : new JsonArray()}) {
            for (var el : libs) {
                JsonObject lib = el.getAsJsonObject();
                String key = artifactKey(lib);
                if (key != null && !seenArtifacts.add(key)) continue; // already have this artifact from a higher-priority source
                mergedLibs.add(lib);
            }
        }
        result.add("libraries", mergedLibs);

        // Arguments: modern format has separate "game" and "jvm" arrays; mod loader profiles
        // typically only add "jvm" args (their own classloader setup) and leave "game" to the
        // parent, but we merge both defensively in case a loader ever adds game args too.
        if (child.has("arguments")) {
            JsonObject childArgs = child.getAsJsonObject("arguments");
            JsonObject parentArgs = parent.has("arguments") ? parent.getAsJsonObject("arguments") : new JsonObject();
            JsonObject mergedArgs = new JsonObject();
            mergedArgs.add("game", concatArray(childArgs.get("game"), parentArgs.get("game")));
            mergedArgs.add("jvm", concatArray(childArgs.get("jvm"), parentArgs.get("jvm")));
            result.add("arguments", mergedArgs);
        }

        // downloads/assetIndex/javaVersion intentionally NOT overridden -- mod loaders don't
        // replace the base client jar or assets, they only add to the classpath, so these
        // must keep coming from vanilla (already true via deepCopy above).
        return result;
    }

    /**
     * "group:artifact[:classifier]" from a library's Maven "name" coordinate (version ignored),
     * or null if absent. The classifier MUST be part of the key: modern version JSONs represent
     * each native-library jar as its own entry with the classifier baked into "name" (e.g.
     * "org.lwjgl:lwjgl:3.3.3:natives-linux" alongside the plain "org.lwjgl:lwjgl:3.3.3" jar) --
     * those are different artifacts serving different purposes, not competing versions of the
     * same one, so collapsing them together would make dedup silently drop the natives jar (or
     * the plain jar) instead of an actual duplicate.
     */
    private String artifactKey(JsonObject lib) {
        if (!lib.has("name")) return null;
        String[] parts = lib.get("name").getAsString().split(":");
        if (parts.length < 2) return null;
        String key = parts[0] + ":" + parts[1];
        if (parts.length > 3) key += ":" + parts[3]; // classifier, e.g. "natives-linux"
        return key;
    }

    private JsonArray concatArray(com.google.gson.JsonElement a, com.google.gson.JsonElement b) {
        JsonArray out = new JsonArray();
        if (a != null && a.isJsonArray()) out.addAll(a.getAsJsonArray());
        if (b != null && b.isJsonArray()) out.addAll(b.getAsJsonArray());
        return out;
    }
}
