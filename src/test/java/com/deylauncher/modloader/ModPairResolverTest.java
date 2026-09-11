package com.deylauncher.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the coordinated Sodium↔Iris resolution that fixes the DEY 26.2 crash.
 *
 * <p>The data here mirrors what Modrinth's API ACTUALLY returns (verified against live data): a
 * Modrinth version's {@code dependencies} never carries the "breaks"/range strings from
 * {@code fabric.mod.json} -- those are empty or absent. Instead, an Iris build pins its Sodium
 * dependency as {@code {project_id, version_id, dependency_type: "required"}}, where {@code
 * version_id} is the hashed id of the exact Sodium version it was built against. For MC 26.2:
 *
 * <pre>
 *   iris   1.11.2+26.2-fabric  -> required sodium version 2Yom1N68 -> mc26.2-0.9.1-fabric
 *   newest sodium for 26.2 is  mc26.2-0.9.2-fabric (which breaks iris &lt;=1.11.2)
 * </pre>
 *
 * So the resolver must step Sodium DOWN to the pinned 0.9.1 (never the breaking newest 0.9.2), and
 * only disable Iris when no compatible Sodium exists.
 */
class ModPairResolverTest {

    private static final String SODIUM_PROJECT = "sodium"; // all Sodium version entries share this project_id
    private static final String OTHER_PROJECT = "some-other-project";

    private final ModPairResolver resolver = new ModPairResolver();

    @Test
    void stepsSodiumDownToIrisPinnedVersion_whenNewestSodiumWouldBreakIt() {
        // The exact 26.2 scenario: newest Sodium (0.9.2) breaks newest Iris (1.11.2); Iris 1.11.2 pins 0.9.1.
        JsonArray irises = array(
                version("iris", "1.11.2+mc26.2", "26.2",
                        pinnedDep(SODIUM_PROJECT, "sodium-mc26.2-0.9.1-fabric")));
        JsonArray sodiums = array(
                version("sodium", "mc26.2-0.9.2-fabric", "26.2"), // id sodium-mc26.2-0.9.2-fabric
                version("sodium", "mc26.2-0.9.1-fabric", "26.2")); // the pinned, safe build

        var plan = resolver.resolveWith("26.2", irises, sodiums);

        assertEquals("mc26.2-0.9.1-fabric", plan.sodiumVersion());
        assertEquals("1.11.2+mc26.2", plan.irisVersion());
        assertFalse(plan.disableIris());
    }

    @Test
    void picksNewestSodiumWhenNoPinIsPresent() {
        JsonArray irises = array(
                version("iris", "1.11.2+mc26.2", "26.2")); // no sodium dependency declared at all
        JsonArray sodiums = array(
                version("sodium", "mc26.2-0.9.8-fabric", "26.2"),
                version("sodium", "mc26.2-0.9.7-fabric", "26.2"));

        var plan = resolver.resolveWith("26.2", irises, sodiums);

        assertEquals("mc26.2-0.9.8-fabric", plan.sodiumVersion());
        assertEquals("1.11.2+mc26.2", plan.irisVersion());
        assertFalse(plan.disableIris());
    }

    @Test
    void honorsDeclaredRangeFallback_whenIrisCarriesRangeInsteadOfPin() {
        // Some Iris builds declare a version RANGE instead of a version_id pin; scan falls back to it.
        JsonArray irises = array(
                version("iris", "1.11.3+mc26.2", "26.2",
                        rangeDep("[0.9.x]")));
        JsonArray sodiums = array(
                version("sodium", "mc26.2-0.8.9-fabric", "26.2")); // below the [0.9.x] floor -> not eligible

        var plan = resolver.resolveWith("26.2", irises, sodiums);

        // No new enough Sodium for the range -> disable Iris, newest Sodium still installed.
        assertTrue(plan.disableIris());
        assertEquals("mc26.2-0.8.9-fabric", plan.sodiumVersion());
    }
    @Test
    void ignoresPinThatNamesTheWrongProject() {
        // Iris pins a dependency on a DIFFERENT project; the resolver must not treat it as Sodium.
        JsonArray irises = array(
                version("iris", "1.11.2+mc26.2", "26.2",
                        pinnedDep(OTHER_PROJECT, "whatever-9.9")));
        JsonArray sodiums = array(
                version("sodium", "mc26.2-0.9.8-fabric", "26.2"));

        var plan = resolver.resolveWith("26.2", irises, sodiums);

        assertEquals("mc26.2-0.9.8-fabric", plan.sodiumVersion());
        assertFalse(plan.disableIris());
    }

    @Test
    void disablesIrisWhenNoSodiumExistsForTheVersion() {
        JsonArray irises = array(
                version("iris", "1.11.2+mc26.2", "26.2",
                        pinnedDep(SODIUM_PROJECT, "sodium-missing")));
        JsonArray sodiums = array(); // no sodium build for 26.2 at all

        var plan = resolver.resolveWith("26.2", irises, sodiums);

        assertTrue(plan.disableIris());
        assertEquals(null, plan.sodiumVersion());
    }

    @Test
    void noIrisMeansJustNewestSodium() {
        JsonArray irises = array();
        JsonArray sodiums = array(
                version("sodium", "mc26.2-0.9.8-fabric", "26.2"));

        var plan = resolver.resolveWith("26.2", irises, sodiums);

        assertEquals("mc26.2-0.9.8-fabric", plan.sodiumVersion());
        assertEquals(null, plan.irisVersion());
        assertFalse(plan.disableIris());
    }

    @Test
    void ignoresIrisForOtherMinecraftVersions() {
        JsonArray irises = array(
                version("iris", "1.7.0+mc1.21.4", "1.21.4",
                        pinnedDep(SODIUM_PROJECT, "sodium-mc1.21.4-0.7.4-fabric")),
                version("iris", "1.11.2+mc26.2", "26.2",
                        pinnedDep(SODIUM_PROJECT, "sodium-mc26.2-0.9.1-fabric")));
        JsonArray sodiums = array(
                version("sodium", "mc26.2-0.9.2-fabric", "26.2"),
                version("sodium", "mc26.2-0.9.1-fabric", "26.2"),
                version("sodium", "mc1.21.4-0.7.4-fabric", "1.21.4"));

        var plan = resolver.resolveWith("26.2", irises, sodiums);

        assertEquals("26.2", plan.mcVersion());
        assertEquals("mc26.2-0.9.1-fabric", plan.sodiumVersion());
        assertEquals("1.11.2+mc26.2", plan.irisVersion());
        assertFalse(plan.disableIris());
    }

    // ---- helpers ----

    private JsonArray array(JsonObject... objs) {
        JsonArray a = new JsonArray();
        for (JsonObject o : objs) a.add(o);
        return a;
    }

    /**
     * Builds a Modrinth version object in the REAL shape: hashed {@code id} and {@code project_id}
     * (project ids for a family are all identical -- like sodium's "AANobbMI"), with optional deps.
     */
    private JsonObject version(String projectId, String versionNumber, String gameVersion, JsonObject... deps) {
        JsonObject v = new JsonObject();
        v.addProperty("version_number", versionNumber);
        v.addProperty("version_type", "release");
        v.addProperty("id", projectId + "-" + versionNumber); // synthetic hashed version id
        v.addProperty("project_id", projectId);               // shared per-family project id
        JsonArray gv = new JsonArray();
        gv.add(gameVersion);
        v.add("game_versions", gv);
        JsonArray loaders = new JsonArray();
        loaders.add("fabric");
        v.add("loaders", loaders);
        JsonArray files = new JsonArray();
        JsonObject file = new JsonObject();
        file.addProperty("filename", projectId + "-" + versionNumber + ".jar");
        file.addProperty("url", "https://example.com/" + projectId + "-" + versionNumber + ".jar");
        files.add(file);
        v.add("files", files);
        if (deps.length > 0) {
            JsonArray depArr = new JsonArray();
            for (JsonObject d : deps) depArr.add(d);
            v.add("dependencies", depArr);
        }
        return v;
    }

    /** A {@code required} dependency that PINS {@code versionId} under {@code projectId}. */
    private JsonObject pinnedDep(String projectId, String versionId) {
        JsonObject d = new JsonObject();
        d.addProperty("project_id", projectId);
        d.addProperty("version_id", versionId);
        d.addProperty("dependency_type", "required");
        d.add("version", JsonNull.INSTANCE);
        return d;
    }

    /** A {@code required} dependency with a version RANGE but no pin (the older scan fallback). */
    private JsonObject rangeDep(String versionRange) {
        JsonObject d = new JsonObject();
        d.addProperty("project_id", SODIUM_PROJECT);
        d.addProperty("project_name", "sodium");
        d.addProperty("dependency_type", "required");
        d.addProperty("version", versionRange);
        return d;
    }
}