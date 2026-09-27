package com.deylauncher.deycapes;

import com.deylauncher.friends.GitHubConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LIVE probe: runs {@link DeyCapesLocalHandoff} against the REAL private cape repo, using the
 * backend token this build embedded at build time. It proves the whole handoff -- token read of the
 * private catalog, texture download, local {@code capes.json} -- without launching Minecraft.
 *
 * <p><b>Opt-in on purpose.</b> It needs the network and a build carrying embedded credentials, so it
 * is skipped unless {@code DEYLAUNCHER_LIVE_CAPES_TEST=true} is set. Run it with:
 *
 * <pre>
 *   DEYLAUNCHER_LIVE_CAPES_TEST=true ./gradlew test --tests "*DeyCapesLiveHandoffTest*"
 * </pre>
 *
 * <p>It writes into {@code /tmp/deycapes-handoff-probe} rather than a temp dir that disappears, so
 * the produced files can be inspected (and hash-compared against the repo's own PNG) afterwards.
 */
@EnabledIfEnvironmentVariable(named = "DEYLAUNCHER_LIVE_CAPES_TEST", matches = "true",
        disabledReason = "live GitHub probe; set DEYLAUNCHER_LIVE_CAPES_TEST=true to run it")
class DeyCapesLiveHandoffTest {

    private static final Path OUT = Path.of("/tmp/deycapes-handoff-probe");

    @Test
    void handoffWritesThePrivateCatalogsCapesLocally() throws Exception {
        deleteRecursively(OUT);

        GitHubConfig config = GitHubConfig.load();
        assertTrue(config.isConfigured(),
                "this build carries no backend credentials -- nothing to read the private repo with");

        // Seed the exact leftover this has to survive: the pointer the launcher wrote before the
        // handoff existed, naming the PUBLIC mirror. A remote hit outranks the local map, so the
        // handoff must remove it, otherwise the game would keep reading GitHub instead.
        Path legacyPointer = OUT.resolve("config").resolve("deycapes").resolve("github.properties");
        Files.createDirectories(legacyPointer.getParent());
        Files.writeString(legacyPointer, """
                #DeyCapes public repository settings
                #Sat Sep 26 11:43:32 EEST 2026
                owner=onpishi
                repo=DeyLauncher-Capes
                capesPath=capes.json
                capesDir=capes
                """);

        DeyCapesService service = new DeyCapesService(config);
        DeyCapesLocalHandoff.Result result = DeyCapesLocalHandoff.writeInto(OUT, service);

        Path configDir = OUT.resolve("config").resolve("deycapes");
        System.out.println("[live] " + result.summary());
        System.out.println("[live] capes.json exists: " + Files.exists(configDir.resolve("capes.json")));
        if (Files.exists(configDir.resolve(DeyCapesLocalHandoff.LOCAL_TEXTURE_DIR))) {
            try (Stream<Path> files = Files.list(configDir.resolve(DeyCapesLocalHandoff.LOCAL_TEXTURE_DIR))) {
                files.sorted().forEach(p -> System.out.println("[live] texture: " + p.getFileName()
                        + " (" + size(p) + " bytes)"));
            }
        }
        if (Files.exists(configDir.resolve("capes.json"))) {
            System.out.println("[live] capes.json:\n" + Files.readString(configDir.resolve("capes.json")));
        }

        // The private repo holds 6 cape defs and 3 equipped players, so a working handoff must produce
        // textures and at least one mapping -- and must never report a problem freeing them.
        assertTrue(result.texturesWritten() > 0, "no cape texture was written: " + result.summary());
        assertTrue(result.playersMapped() > 0, "no player mapping was written: " + result.summary());
        assertFalse(Files.exists(configDir.resolve("github.properties")),
                "the mod must be left with no repository pointer, so it cannot read a private repo anonymously");
        assertFalse(Files.exists(legacyPointer),
                "the leftover pointer at the old public mirror had to be removed, not left to outrank the handoff");
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort; the assertions below fail loudly if the directory is still dirty
                }
            });
        }
    }
}
