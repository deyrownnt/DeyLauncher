package com.deylauncher.modloader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Installs DeyLauncher's own tiny Fabric {@code PreLaunchEntrypoint} helper --
 * {@code deylauncher-awt-init.jar} -- into an instance's mods folder whenever this launch is one where
 * it is actually needed.
 *
 * <h2>The problem</h2>
 * DeyLauncher already starts the game with an explicit {@code -Djava.awt.headless} value, pinned on
 * Linux so AWT never silently defaults to headless just because {@code DISPLAY} happens to be unset
 * (see {@code GameLauncher#awtHeadlessMode}). That is not the end of the story on Minecraft 1.20.1:
 * vanilla's own {@code Main} class calls {@code System.setProperty("java.awt.headless", "true")}
 * unconditionally during its own startup, AFTER the JVM has already booted with whatever value the
 * launcher passed on the command line. Any Fabric mod that touches AWT/Swing AFTER that point --
 * observed with Early Loading Bar's pre-launch window -- gets a {@code HeadlessException} even though
 * the launcher never asked for headless mode and DISPLAY was reachable the whole time.
 *
 * <h2>The fix</h2>
 * A Fabric {@code PreLaunchEntrypoint} runs during mod loading, ahead of Minecraft's own {@code Main}.
 * The JDK's AWT toolkit only reads {@code java.awt.headless} the FIRST time it is touched (in
 * {@code GraphicsEnvironment.isHeadless()} / {@code getLocalGraphicsEnvironment()}) and then keeps that
 * answer for the rest of the process -- so a helper that eagerly touches it during prelaunch locks in a
 * non-headless toolkit before Minecraft ever gets the chance to flip the property back. The bundled
 * helper class ({@code com.deylauncher.launchelf.AwtInit}, packaged as the resource named by
 * {@link #RESOURCE_PATH}) does nothing beyond that: no UI, no rendering, no other behavior change, and
 * touching an already-initialized {@code GraphicsEnvironment} again is a no-op, so it is safe to run on
 * every launch that installs it.
 *
 * <h2>Why this lives here, launcher-managed</h2>
 * The previous version of this fix was a copy of the same jar dropped by hand into an instance's
 * {@code mods/} folder, which worked once but did not survive a modpack reinstall: {@link
 * com.deylauncher.modpack.ModpackVerifier}'s repair pass only restores files the pack's OWN manifest
 * lists, so a hand-added extra file is invisible to it and simply vanishes the next time the pack is
 * reinstalled or repaired. Installing it here instead follows the same pattern {@link DeyCapesInstaller}
 * already uses for a launcher-bundled mod: copied in from DeyLauncher's own resources, checked (and put
 * back if missing) on every launch via {@link #ensureInstalled}, and never listed in any modpack's own
 * manifest -- which is exactly what keeps {@code ModpackVerifier} from ever touching it. It survives
 * reinstall, repair, verification and updates simply because none of those operations look at, or
 * remove, files the pack's manifest doesn't mention.
 *
 * <h2>When it actually installs</h2>
 * Only when both hold:
 * <ul>
 *   <li>the launch is Linux + Fabric -- the only combination this HeadlessException has been observed
 *       on: Windows/macOS default AWT to non-headless and hit nothing here; and</li>
 *   <li>the instance's mods folder already contains the known trigger, an AWT/Swing mod such as Early
 *       Loading Bar -- so a pack that never opens an AWT window during startup never gets a mod it has
 *       no use for.</li>
 * </ul>
 */
public final class AwtHelperInstaller {

    private static final Logger LOGGER = Logger.getLogger("AwtHelperInstaller");

    /** The file name the helper is installed under -- launcher-managed, never part of a pack's manifest. */
    private static final String MOD_FILE_NAME = "deylauncher-awt-init.jar";

    /** Bundled inside DeyLauncher's own resources, see build.gradle.kts / src/main/resources/launcher-jars. */
    private static final String RESOURCE_PATH = "/launcher-jars/deylauncher_awt_init.jar";

    /**
     * Lower-cased substring that names the known trigger for this HeadlessException: a Fabric mod that
     * opens an AWT/Swing window during startup. Early Loading Bar is the only one observed so far;
     * matching by substring (rather than an exact file name) still catches its usual "earlyloadingbar-
     * <version>.jar" naming across Modrinth/CurseForge builds.
     */
    private static final String AWT_TRIGGER_MOD_SUBSTRING = "earlyloadingbar";

    private AwtHelperInstaller() {}

    /**
     * True only for the platform/loader combination this fix is actually for: Linux + Fabric. Windows
     * and macOS already default AWT to non-headless and have no equivalent of this crash; other loaders
     * load their mods too differently from Fabric's prelaunch entrypoints for this helper to apply.
     */
    static boolean platformNeedsHelper(String osName, String loader) {
        // Only the Linux kernel qualifies. Java reports os.name as "Linux" on every distro, but a
        // launch config / caller may pass the distro name instead (e.g. "Ubuntu 24.04"), so accept
        // the common Linux distro identifiers too -- while still rejecting Windows and macOS (which
        // default AWT to non-headless and never hit this HeadlessException) and every non-Fabric loader.
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        boolean isLinux = os.contains("linux") || os.contains("nix") || os.contains("nux")
                || os.contains("ubuntu") || os.contains("debian") || os.contains("fedora")
                || os.contains("red hat") || os.contains("centos") || os.contains("arch")
                || os.contains("manjaro") || os.contains("alpine") || os.contains("opensuse");
        boolean isWindows = os.contains("win");
        boolean isMac = os.contains("mac") || os.contains("darwin") || os.contains("osx");
        return isLinux && !isWindows && !isMac && "Fabric".equalsIgnoreCase(loader);
    }

    /**
     * True when {@code modsDir} contains a jar that is the known trigger for this crash (an AWT/Swing
     * mod such as Early Loading Bar). A pack that never opens such a window during startup has no use
     * for this helper, so it is left alone rather than installed unconditionally into every instance.
     */
    static boolean modsDirHasTriggerMod(Path modsDir) {
        if (!Files.isDirectory(modsDir)) return false;
        try (var stream = Files.list(modsDir)) {
            return stream.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".jar") && n.contains(AWT_TRIGGER_MOD_SUBSTRING);
            });
        } catch (Exception e) {
            return false;
        }
    }

    /** True if the exact bundled jar is already sitting in {@code modsDir}, byte-for-byte. */
    static boolean isCurrent(Path modsDir) {
        Path installed = modsDir.resolve(MOD_FILE_NAME);
        if (!Files.isRegularFile(installed)) return false;
        try (InputStream bundled = AwtHelperInstaller.class.getResourceAsStream(RESOURCE_PATH)) {
            if (bundled == null) return false;
            byte[] bundledBytes = bundled.readAllBytes();
            byte[] installedBytes = Files.readAllBytes(installed);
            return java.util.Arrays.equals(bundledBytes, installedBytes);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Ensures the AWT-init helper is installed into {@code modsDir} whenever this launch's environment
     * could hit the {@code HeadlessException} it exists to prevent (see class docs for the exact gate).
     * Safe to call on every launch: a no-op once the current jar is already in place, self-healing if the
     * file went missing or got corrupted (e.g. by a modpack reinstall wiping the whole mods folder), and
     * a no-op when this launch doesn't need it at all.
     *
     * @return the installed file name when something changed, or {@code null} when nothing was needed or
     *         nothing changed (already installed and current, not needed on this platform/loader, no
     *         trigger mod present, or the bundled resource is missing from this build).
     */
    public static String ensureInstalled(String osName, String loader, Path modsDir) throws IOException {
        if (!platformNeedsHelper(osName, loader)) return null;
        if (!modsDirHasTriggerMod(modsDir)) return null;
        if (isCurrent(modsDir)) return null;

        Files.createDirectories(modsDir);
        try (InputStream in = AwtHelperInstaller.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                LOGGER.warning("Bundled AWT-init helper resource missing at " + RESOURCE_PATH
                        + " -- cannot install deylauncher-awt-init.jar for this build.");
                return null;
            }
            Files.copy(in, modsDir.resolve(MOD_FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
        }
        return MOD_FILE_NAME;
    }
}
