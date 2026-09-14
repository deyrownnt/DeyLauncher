package com.deylauncher.optionskits;

import java.util.UUID;

/**
 * One saved snapshot of a Minecraft instance's {@code options.txt} (video settings, keybinds,
 * language, etc.) -- a "kit" the user can name, icon, and re-apply later to any instance.
 *
 * <p>The whole options.txt content travels as one base64 blob ({@link #optionsTxtBase64}) so the
 * kit is a single self-contained JSON object: easy to store as one entry in the shared GitHub file
 * (see {@link OptionsKitsRepository}), easy to export/import as a standalone {@code .json} file for
 * drag & drop, and immune to line-ending/charset surprises on the way there.
 *
 * <p>{@link #crossVersion} decides how {@link #minecraftVersion} is read: when true, the kit was
 * saved to be applied to ANY version/loader (this is honest -- options.txt keys are almost entirely
 * stable across versions, but a handful of newer keys simply won't exist on an older client and
 * older keys are harmlessly ignored on newer ones); when false, {@link #minecraftVersion} records
 * the exact instance it came from purely as a label for the user ("Fabric 1.21.1"), not an
 * enforced restriction -- DeyLauncher never blocks applying a kit to a different version, since
 * that would make the feature far less useful than the "same file, every version" reality of
 * options.txt actually is. The label just tells the user where it came from.
 */
public class OptionsKit {
    public String id = UUID.randomUUID().toString();
    public String name;
    /** Base64 PNG, ~64x64 recommended. Null/blank shows a generated default tile, like server icons do. */
    public String iconBase64;
    /** Label only (see class doc) -- e.g. "1.21.1-fabric", or null when crossVersion is true. */
    public String minecraftVersion;
    public boolean crossVersion;
    /** The saved options.txt, base64-encoded so it round-trips through JSON with zero escaping concerns. */
    public String optionsTxtBase64;
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = System.currentTimeMillis();

    public OptionsKit() {}

    public OptionsKit(String name, String iconBase64, String minecraftVersion, boolean crossVersion,
                       String optionsTxtBase64) {
        this.name = name;
        this.iconBase64 = iconBase64;
        this.minecraftVersion = crossVersion ? null : minecraftVersion;
        this.crossVersion = crossVersion;
        this.optionsTxtBase64 = optionsTxtBase64;
    }
}
