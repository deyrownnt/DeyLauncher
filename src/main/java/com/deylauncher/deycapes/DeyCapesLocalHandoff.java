package com.deylauncher.deycapes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Hands the shared cape data to the in-game DeyCapes mod as LOCAL FILES, so a PRIVATE cape repo
 * still renders for every player -- while the token stays in the launcher.
 *
 * <p><b>Why this exists.</b> The mod runs inside Minecraft and has no GitHub credential, by design:
 * a token copied into a game instance would sit in plain text on disk and carries write access to
 * the shared backend. Without a token the mod can only read a repository ANONYMOUSLY, i.e. only if
 * that repository is public. When the cape data lives in the same <i>private</i> repo as the
 * friends graph, that anonymous read 404s (GitHub answers 404, not 403, for a private repo) and Dey
 * capes silently never render: the launcher happily writes ownership with its embedded token, and
 * nothing ever shows up in-game. The two ways out are "make the repo public" or "the launcher hands
 * the data over itself". This class is the second one -- nothing gets published, the repo stays
 * private, and every install still works with no setup.
 *
 * <p><b>How.</b> The launcher, which DOES hold the embedded token, fetches the catalog and its
 * textures at launch and writes them into the instance's DeyCapes config dir:
 *
 * <pre>
 *   &lt;instance&gt;/config/deycapes/capes.json       dashed uuid -&gt; "capes/&lt;cape-id&gt;.png"
 *   &lt;instance&gt;/config/deycapes/capes/&lt;id&gt;.png  the PNG bytes, fetched with the token
 * </pre>
 *
 * <p>That is exactly what the mod's {@code LocalCapeProvider} already reads (its {@code capes.json}
 * is a plain uuid -&gt; PNG map, relative paths resolve against {@code config/deycapes/}, and the file
 * is re-read whenever its mtime changes), and {@code GithubCapeProvider#getCape} falls through to
 * that local provider whenever its own remote map has no entry for the uuid -- including when no
 * repository is configured at all. So the mod needs NO change and no network access at all: no
 * token in the instance, no anonymous reads of a private repo, no 60-requests-per-hour anonymous
 * quota, and the very same PNG pipeline as before.
 *
 * <p>An existing {@code github.properties} the launcher itself wrote earlier (recognised by its
 * comment header, so it is caught whether it points at the private backend or at the old public
 * cape mirror) is removed, because any remote hit OUTRANKS this local handoff: leaving it would send
 * the mod back to GitHub, and break capes outright the day that mirror is deleted. A file written by
 * hand, pointing at the user's own public repo, is left untouched.
 *
 * <p>Keys are written DASHED because {@code LocalCapeProvider} parses them with
 * {@code UUID.fromString}. Both the raw online uuid Mojang returns (which is dashless!) and the
 * derived offline uuid recorded on that entry are mapped, so the same person resolves to the same
 * cape whether they are signed in or not.
 */
public final class DeyCapesLocalHandoff {

    /** Folder inside the instance's DeyCapes config dir that holds the PNGs written here. */
    public static final String LOCAL_TEXTURE_DIR = "capes";

    /** The launcher-managed mod config file that points the mod at a repository. */
    private static final String MOD_CONFIG_FILE = "github.properties";

    /**
     * The comment the launcher passes to {@code Properties.store} for the config file it manages, so
     * the file can be recognised later as launcher-written (and cleaned up) no matter which repo it
     * names -- including a leftover pointer at the old public cape mirror.
     */
    public static final String LAUNCHER_CONFIG_COMMENT = "DeyCapes public repository settings";

    /** Matches the mod's own JSON writing, so a handoff file looks like every other capes file. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DeyCapesLocalHandoff() {}

    /** What one handoff did, for the launcher log. */
    public record Result(int texturesWritten, int playersMapped, List<String> problems) {

        /** One log line: what was handed over, and anything that could not be. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(texturesWritten).append(" cape texture(s) and ")
              .append(playersMapped).append(" player mapping(s) written to config/deycapes -- the "
                      + "in-game mod reads these locally, so the private cape repo is never read "
                      + "anonymously.");
            if (!problems.isEmpty()) {
                sb.append(" Not handed over: ").append(String.join("; ", problems)).append('.');
            }
            return sb.toString();
        }
    }
    /**
     * Pure mapping step: every equipped player in the catalog becomes a dashed-uuid -&gt;
     * relative-PNG entry, but only for cape ids whose texture actually made it into the instance
     * ({@code textureBackedCapeIds}) -- pointing the mod at a PNG that was never written would only
     * hand it a broken texture to fail on.
     *
     * @param data                 the catalog read from the private repo (may be null)
     * @param textureBackedCapeIds cape ids whose PNGs were written into the instance
     * @return a stable (sorted) copy of the local uuid -&gt; PNG map
     */
    public static Map<String, String> buildLocalMap(CapesData data, Set<String> textureBackedCapeIds) {
        Map<String, String> out = new TreeMap<>();
        if (data == null || data.players == null) return out;
        for (Map.Entry<String, CapesData.PlayerCape> entry : data.players.entrySet()) {
            CapesData.PlayerCape player = entry.getValue();
            if (player == null || player.cape == null || player.cape.isBlank()) continue;
            String capeId = player.cape.trim();
            if (textureBackedCapeIds == null || !textureBackedCapeIds.contains(capeId)) continue;
            String relative = LOCAL_TEXTURE_DIR + "/" + safeFileName(capeId) + ".png";
            String onlineKey = dashedUuid(entry.getKey());
            if (onlineKey != null) out.put(onlineKey, relative);
            String offlineKey = dashedUuid(player.offlineUuid);
            if (offlineKey != null) out.put(offlineKey, relative);
        }
        return out;
    }

    /** The canonical dashed uuid for a value, or null when it is not a uuid at all. */
    public static String dashedUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.length() == 32 && s.indexOf('-') < 0) {
            s = s.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
        }
        try {
            return UUID.fromString(s).toString();
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Filename for a cape id, restricted to characters every filesystem accepts. */
    public static String safeFileName(String capeId) {
        return capeId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
    /**
     * Fetches the catalog and every catalog texture with the launcher's token and writes the local
     * handoff into {@code instanceDir}. Best-effort by contract: a cape whose PNG cannot be fetched
     * is named in {@link Result#problems()} and skipped, never thrown -- a launch must not fail over
     * cape art.
     *
     * @param instanceDir the game instance directory (the one holding {@code config/} and {@code mods/})
     * @param service     the token-backed cape service (reads the private catalog)
     */
    public static Result writeInto(Path instanceDir, DeyCapesService service) {
        List<String> problems = new ArrayList<>();
        Path configDir = instanceDir.resolve("config").resolve("deycapes");
        Path textureDir = configDir.resolve(LOCAL_TEXTURE_DIR);
        Set<String> texturedCapeIds = new LinkedHashSet<>();
        Map<String, String> localMap = new TreeMap<>();
        try {
            Files.createDirectories(textureDir);
            CapesData data = service.ensureCatalog();
            if (data.capes != null) {
                for (Map.Entry<String, CapesData.CapeDef> entry : data.capes.entrySet()) {
                    String capeId = entry.getKey();
                    CapesData.CapeDef def = entry.getValue();
                    if (capeId == null || capeId.isBlank()) continue;
                    if (def == null || def.texture == null || def.texture.isBlank()) {
                        problems.add(capeId + " (catalog entry has no texture path)");
                        continue;
                    }
                    try (InputStream in = service.readCapeTextureWithFallback(capeId, def.texture)) {
                        byte[] png = in.readAllBytes();
                        if (png.length == 0) {
                            problems.add(capeId + " (empty texture body)");
                            continue;
                        }
                        Files.write(textureDir.resolve(safeFileName(capeId) + ".png"), png);
                        texturedCapeIds.add(capeId);
                    } catch (Exception textureEx) {
                        problems.add(capeId + " (" + message(textureEx) + ")");
                    }
                }
            }
            localMap = buildLocalMap(data, texturedCapeIds);
            // A JsonObject, not the Map directly: the mod parses this as a JSON object of
            // uuid -> path, and an empty map must still serialize as {} rather than as nothing.
            JsonObject json = new JsonObject();
            for (Map.Entry<String, String> e : localMap.entrySet()) {
                json.addProperty(e.getKey(), e.getValue());
            }
            Files.writeString(configDir.resolve("capes.json"),
                    GSON.toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8);
            makeRemoteReadInert(configDir, service.gitConfig().repo(), problems);
        } catch (Exception ex) {
            // A dead repo/token must not stop a launch: the mod simply renders no Dey capes, and the
            // launcher log says exactly why instead of leaving it to be guessed at in-game.
            problems.add("handoff failed: " + message(ex));
        }
        return new Result(texturedCapeIds.size(), localMap.size(), problems);
    }

    /**
     * Stops the mod from trying a repository at all. See the class javadoc: a pointer the launcher
     * wrote earlier (at the private backend, or at the public cape mirror used before the handoff
     * existed) would make the mod poll GitHub anonymously and would OUTRANK this local handoff
     * whenever that read succeeds -- so capes would silently come from the wrong place, and would
     * break entirely the day that mirror is deleted. A file the USER wrote by hand, pointing at
     * their own public repo, is left alone.
     */
    static void makeRemoteReadInert(Path configDir, String privateRepo, List<String> problems) {
        Path file = configDir.resolve(MOD_CONFIG_FILE);
        try {
            if (!Files.exists(file)) return;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            String configuredRepo = props.getProperty("repo", "").trim();
            boolean pointsAtThePrivateBackend =
                    privateRepo != null && !privateRepo.isBlank() && configuredRepo.equals(privateRepo);
            // The header is what Properties.store writes from the comment the launcher passed in, so
            // it identifies the file as launcher-managed regardless of which repo it names (that is
            // how a leftover pointer at the old PUBLIC mirror is recognised and removed).
            boolean launcherWritten = text.contains(LAUNCHER_CONFIG_COMMENT);
            if (!pointsAtThePrivateBackend && !launcherWritten) {
                return; // not ours -- leave the user's own choice alone
            }
            Files.delete(file);
        } catch (Exception ex) {
            problems.add(MOD_CONFIG_FILE + " (" + message(ex) + ")");
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
