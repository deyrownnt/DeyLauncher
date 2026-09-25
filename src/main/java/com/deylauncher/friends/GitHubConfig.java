package com.deylauncher.friends;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;

/**
 * Where the shared GitHub backend credentials come from, in priority order:
 *
 * <ol>
 *   <li>the current user's local {@code ~/.deylauncher/github.properties} -- an optional override for
 *       the person running the "backend" account, or for pointing a build at a different repo;</li>
 *   <li>the credentials embedded in the app itself, so a freshly downloaded launcher has Friends,
 *       servers, capes and option kits working with NO key and NO file from the user;</li>
 *   <li>nothing at all, in which case features needing GitHub report "not set up".</li>
 * </ol>
 *
 * <p>The embedded copy is written into the build as an obfuscated blob by {@code embedGithubCredentials}
 * (see {@code build.gradle.kts}) from the CI secret or the developer's {@code secrets/} folder. The
 * obfuscation is deliberately NOT presented as security: anything inside a distributed desktop app can
 * be extracted by whoever has the app, and this only keeps the token from sitting in the jar as an
 * obvious {@code github_pat_...} string. The real containment is that the token is a fine-grained
 * credential scoped to the single friends repo with Contents read/write and nothing else.
 *
 * <p>The token is never logged, never written into a game instance, and never copied out of the app.
 */
public class GitHubConfig {

    /** Resource name the build drops the (obfuscated) embedded credentials into. */
    private static final String EMBEDDED_RESOURCE = "/deylauncher-backend.dat";

    /**
     * Fixed key used to obfuscate the embedded blob. It is compiled into the app by design -- this is a
     * speed bump against casually reading the token out of the jar, not encryption.
     */
    private static final String EMBEDDED_KEY = "DeyLauncher-backend-v1";

    public final String token;
    public final String owner;
    public final String repo;
    public final String friendsPath;
    /** Live equipped-cape map for DeyCapes (default "capes.json"). */
    public final String capesPath;
    /** Who-owns-what audit file for Dey capes (default "capes-owned.json"). */
    public final String capesOwnedPath;
    /** Folder in the repo holding the cape PNG textures (default "capes"). */
    public final String capesDir;
    /** Shared "who owns which saved options kits" table (default "options-kits.json"). */
    public final String optionsKitsPath;

    private GitHubConfig(String token, String owner, String repo, String friendsPath,
                         String capesPath, String capesOwnedPath, String capesDir, String optionsKitsPath) {
        this.token = token;
        this.owner = owner;
        this.repo = repo;
        this.friendsPath = friendsPath;
        this.capesPath = capesPath;
        this.capesOwnedPath = capesOwnedPath;
        this.capesDir = capesDir;
        this.optionsKitsPath = optionsKitsPath;
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank() && owner != null && !owner.isBlank()
                && repo != null && !repo.isBlank();
    }

    public static Path localOverrideFile() {
        return Path.of(System.getProperty("user.home"), ".deylauncher", "github.properties");
    }

    /**
     * Local override first, then the credentials baked into this build, then "not configured".
     *
     * <p>The order matters: a user (or the backend owner) who wants a different repo just drops their own
     * {@code github.properties} in place and it wins, while everyone else silently gets the shared
     * backend -- no setup step, no key, no file.
     */
    public static GitHubConfig load() {
        GitHubConfig local = loadFrom(localOverrideFile());
        if (local != null && local.isConfigured()) return local;

        GitHubConfig embedded = loadEmbedded();
        if (embedded != null && embedded.isConfigured()) return embedded;

        return unconfigured();
    }

    /** The "no backend at all" config, keeping the default repo paths so features degrade predictably. */
    private static GitHubConfig unconfigured() {
        return new GitHubConfig(null, null, null, "friends.json", "capes.json", "capes-owned.json",
                "capes", "options-kits.json");
    }

    /** The build-embedded credentials, de-obfuscated; null when this build carries none. */
    private static GitHubConfig loadEmbedded() {
        try (InputStream in = GitHubConfig.class.getResourceAsStream(EMBEDDED_RESOURCE)) {
            if (in == null) return null;
            String packed = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (packed.isEmpty()) return null;
            String plain = deobfuscate(packed);
            if (plain == null || plain.isBlank()) return null;
            try (var reader = new StringReader(plain)) {
                return fromProperties(load(reader));
            }
        } catch (IOException | RuntimeException e) {
            // A damaged/foreign blob means "this build has no backend", never a crash on startup.
            return null;
        }
    }

    /** Reverses {@code embedGithubCredentials}' XOR+Base64. Package-private so a test can round-trip it. */
    static String deobfuscate(String packed) {
        try {
            byte[] bytes = Base64.getDecoder().decode(packed);
            byte[] key = EMBEDDED_KEY.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                out[i] = (byte) (bytes[i] ^ key[i % key.length]);
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null; // not our format
        }
    }

    private static GitHubConfig loadFrom(Path file) {
        if (!Files.exists(file)) return null;
        try (var in = Files.newInputStream(file)) {
            return fromProperties(load(in));
        } catch (IOException e) {
            return null;
        }
    }

    private static Properties load(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        return props;
    }

    private static Properties load(java.io.Reader reader) throws IOException {
        Properties props = new Properties();
        props.load(reader);
        return props;
    }

    private static GitHubConfig fromProperties(Properties props) {
        return new GitHubConfig(
                props.getProperty("token"),
                props.getProperty("owner"),
                props.getProperty("repo"),
                props.getProperty("friendsPath", "friends.json"),
                props.getProperty("capesPath", "capes.json"),
                props.getProperty("capesOwnedPath", "capes-owned.json"),
                props.getProperty("capesDir", "capes"),
                props.getProperty("optionsKitsPath", "options-kits.json")
        );
    }
}
