package com.deylauncher.modpack;

import java.nio.file.Path;

/**
 * Which installed modpack, if any, is currently attached to the launcher's Minecraft-version + loader
 * selection -- and the ONE place that turns "what is selected" into "which instance folder runs".
 *
 * <h2>What this is not</h2>
 * It is not a profile. The Version and Loader selectors stay the single source of truth for what the
 * user wants to play, and they are never locked. A pack does not own them; it can only be
 * <i>attached</i> to them, and only while they read exactly what the pack was installed for.
 *
 * <h2>Lifecycle (the invariant)</h2>
 * A pack is attached only while ALL of these hold:
 * <ul>
 *   <li>the selected version and loader equal the pack's own ({@link ModpackMeta#targets}), and</li>
 *   <li>the launcher is in VANILLA mode (DEY injects its own mods, which a pack's author never
 *       intended), and</li>
 *   <li>the pack's record still exists on disk.</li>
 * </ul>
 * {@link #attach} refuses to attach unless that holds right now, and {@link #reconcile} -- called
 * whenever the version, loader or mode changes -- detaches the moment it stops holding. Because of
 * that, an attached pack can never disagree with what the selectors show, so the UI and the launched
 * instance cannot drift apart. Explicit navigation (clicking a version tile, switching mode) calls
 * {@link #detach} outright.
 *
 * <h2>Storage vs. selection</h2>
 * {@link #resolve} is the only mapping from selection to folder:
 * <ul>
 *   <li>pack attached -> that pack's own folder ({@code instances/<pack folder>});</li>
 *   <li>otherwise -> the plain {@code instances/<mc>-<loader>} folder, exactly as before modpacks
 *       existed. An isolated pack is never picked up by version + loader alone -- selecting
 *       1.20.1 + Fabric by hand (Vanilla or DEY) never launches one, and never sees its mods. A pack an
 *       older build installed into that plain folder is moved out into its own folder at startup
 *       ({@link ModpackMeta#separateLegacyPacks}); only if that move was impossible (folder in use) is it
 *       still reported here as the target's pack, because it is then what will really run.</li>
 * </ul>
 * Not thread-safe: use from the JavaFX thread, and hand the immutable {@link Target} to workers.
 */
public final class ModpackSelection {

    /**
     * A snapshot of what a launch (or the Mods window, or the icon) should act on.
     *
     * @param pack the record living in {@code instanceDir}, whether attached or installed there by an
     *             older build; null when that folder holds no modpack
     */
    public record Target(String mcVersion, String loader, ModpackMeta pack, Path instanceDir) {

        /** The loader build the pack in this folder pinned for this launch, or null (use the newest stable). */
        public String pinnedLoaderVersion() {
            return pack == null ? null : pack.pinnedLoaderVersion(mcVersion, loader);
        }
    }

    private ModpackMeta attached;

    /** The pack currently attached to the selectors, or null in plain Vanilla/DEY use. */
    public ModpackMeta attached() {
        return attached;
    }

    /**
     * Attaches {@code pack} if -- and only if -- the selectors read exactly what it targets right now.
     *
     * @return false (and attaches nothing, leaving any previous pack detached) when they don't
     */
    public boolean attach(ModpackMeta pack, String selectedVersion, String selectedLoader, boolean deyMode) {
        attached = null;
        if (pack == null || deyMode || !pack.targets(selectedVersion, selectedLoader)) return false;
        attached = pack;
        return true;
    }

    /** Drops the attached pack, returning to plain version/loader selection. @return true if there was one */
    public boolean detach() {
        boolean had = attached != null;
        attached = null;
        return had;
    }

    /**
     * Keeps the attachment honest after ANY change to the version, loader or mode: the pack stays only
     * while the selectors still read exactly what it targets. Cheap, idempotent, safe to call from every
     * listener.
     *
     * @return true if that change detached the pack
     */
    public boolean reconcile(String selectedVersion, String selectedLoader, boolean deyMode) {
        if (attached == null) return false;
        if (deyMode || !attached.targets(selectedVersion, selectedLoader)) {
            attached = null;
            return true;
        }
        return false;
    }

    /** Detaches the pack living in this instance folder (it was deleted), leaving any other alone. */
    public void detachIfInstance(String instanceId) {
        if (attached != null && attached.instanceId != null && attached.instanceId.equals(instanceId)) {
            attached = null;
        }
    }

    /**
     * Resolves the current selection to the instance that will actually run. The folder is the attached
     * pack's own, or the plain version+loader folder; the returned pack is always read fresh from that
     * folder, so a repair or reinstall is never shadowed by a stale in-memory copy. An attached pack
     * whose record has vanished (deleted by hand) is detached here rather than launched as an empty folder.
     *
     * @param selectedVersion must be non-blank; callers apply their own default when nothing is selected yet
     */
    public Target resolve(Path launcherRoot, String selectedVersion, String selectedLoader) {
        if (attached != null) {
            Path packDir = attached.instanceDir(launcherRoot);
            ModpackMeta fresh = ModpackMeta.read(packDir);
            if (fresh != null && fresh.targets(selectedVersion, selectedLoader)) {
                attached = fresh;
                return new Target(selectedVersion, selectedLoader, fresh, packDir);
            }
            attached = null; // record gone or no longer matching: never launch a phantom pack folder
        }
        Path plain = ModpackMeta.instanceDirFor(launcherRoot, selectedVersion, selectedLoader);
        return new Target(selectedVersion, selectedLoader, ModpackMeta.read(plain), plain);
    }
}
