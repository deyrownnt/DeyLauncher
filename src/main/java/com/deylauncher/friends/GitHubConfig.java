package com.deylauncher.friends;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Credentials are loaded only from the current user's local
 * {@code ~/.deylauncher/github.properties}. They are never committed, embedded in a build, or copied into
 * a game instance. If it is absent, features requiring GitHub authentication report "not set up".
 */
public class GitHubConfig {

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

    public static GitHubConfig load() {
        GitHubConfig local = loadFrom(localOverrideFile());
        if (local != null && local.isConfigured()) return local;

        return new GitHubConfig(null, null, null, "friends.json", "capes.json", "capes-owned.json",
                "capes", "options-kits.json");
    }

    private static GitHubConfig loadFrom(Path file) {
        if (!Files.exists(file)) return null;
        try (var in = Files.newInputStream(file)) {
            return fromProperties(load(in));
        } catch (IOException e) {
            return null;
        }
    }

    private static GitHubConfig loadFromClasspath(String resourcePath) {
        try (InputStream in = GitHubConfig.class.getResourceAsStream(resourcePath)) {
            if (in == null) return null;
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
