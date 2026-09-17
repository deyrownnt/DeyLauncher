package com.deylauncher.modpack;

/**
 * One file a CurseForge pack's {@code manifest.json} lists, by id pair only:
 * {@code {"projectID": 238222, "fileID": 8807823, "required": true}}.
 *
 * <p>The format carries no path, no download link and no hash -- just the two ids -- which is why a
 * CurseForge pack used to install only its {@code overrides/} and leave every mod to be downloaded
 * by hand. {@link CurseForgeFiles} turns these ids back into a real file name + CDN URL at install
 * time (no API key needed), and {@link ModpackResolver} turns that into the
 * {@code mods/<name>.jar} line the installer needs.
 *
 * <p>{@code name} is a best-effort human name taken from the pack's own {@code modlist.html}
 * (CurseForge writes it in the same order as {@code files[]}) and is only used as a fallback when
 * the id lookup fails -- never to guess an identity, see {@link ModpackResolver}.
 */
public record CurseForgeEntry(long projectId, long fileId, boolean required, String name) {

    /** "238222/8807823 -- Just Enough Items (JEI)" for logs and the "couldn't fetch" list. */
    public String describe() {
        String id = projectId + "/" + fileId;
        return name == null || name.isBlank() ? id : id + " -- " + name;
    }
}
