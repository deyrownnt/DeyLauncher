package com.deylauncher.modpack;

import com.deylauncher.server.ModrinthClient;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Icon compatibility for modpacks.
 *
 * A pack's icon can come from two places, and we handle both so the pack looks like itself inside
 * DeyLauncher (in the modpack menu and next to the active instance):
 *
 * <ol>
 *   <li><b>Inside the pack</b> -- an exported instance/folder carries {@code icon.png} (MultiMC /
 *       Prism), {@code pack.png}, or a {@code logo.png}; we read whichever is there.</li>
 *   <li><b>Modrinth</b> -- an {@code .mrpack} carries no image at all, so we look the pack up by
 *       name on Modrinth (project_type {@code modpack}) and use the project icon.</li>
 * </ol>
 *
 * Whatever we get is decoded with ImageIO (the bundled TwelveMonkeys plugin covers Modrinth's WebP
 * icons, exactly like {@code ModrinthClient.iconFor} already does for mods) and cached as a plain
 * 256px PNG under {@code ~/.deylauncher/modpacks/icons/}, so JavaFX can render it natively and the
 * cache stays tiny.
 */
public final class PackIcons {

    private PackIcons() {}

    /** Candidate icon file names, in the order a pack is most likely to mean them. */
    private static final List<String> ICON_NAMES = List.of(
            "icon.png", "pack.png", "logo.png", "icon.jpg", "icon.jpeg",
            "minecraft/icon.png", "minecraft/pack.png", ".minecraft/icon.png",
            "overrides/icon.png", "overrides/pack.png");

    /**
     * Resolves (and caches) the icon for a pack, returning the cached PNG path or null when the pack
     * has no icon and Modrinth doesn't know it either. Never throws -- a missing icon is cosmetic.
     */
    public static Path resolve(ModpackInfo info, Path launcherRoot) {
        if (info == null || launcherRoot == null) return null;
        Path cacheDir = launcherRoot.resolve("modpacks").resolve("icons");
        Path out = cacheDir.resolve(safeName(info.name()) + ".png");
        try {
            if (Files.exists(out) && Files.size(out) > 0) return out;
        } catch (IOException ignored) {
        }

        byte[] raw = iconBytesFromPack(info);
        if (raw == null) raw = iconBytesFromModrinth(info.name());
        if (raw == null) return null;

        BufferedImage image = decode(raw);
        if (image == null) return null;
        try {
            Files.createDirectories(cacheDir);
            ImageIO.write(ModrinthClient.scaleTo(image, 256), "png", out.toFile());
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Copies a resolved icon into an instance folder as icon.png, so the instance keeps its pack
     *  icon even if the shared cache is cleared. Best-effort. */
    public static void copyIntoInstance(Path icon, Path instanceDir) {
        if (icon == null || instanceDir == null || !Files.exists(icon)) return;
        try {
            Files.createDirectories(instanceDir);
            Files.copy(icon, instanceDir.resolve("icon.png"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    // ---- internals ----

    private static byte[] iconBytesFromPack(ModpackInfo info) {
        Path source = info.source();
        if (source == null || !Files.exists(source)) return null;
        try {
            if (info.format() == ModpackFormat.FOLDER) {
                for (String name : ICON_NAMES) {
                    Path candidate = source.resolve(name);
                    if (Files.isRegularFile(candidate)) return Files.readAllBytes(candidate);
                }
                return null;
            }
            try (ZipFile zip = new ZipFile(source.toFile())) {
                for (String name : ICON_NAMES) {
                    ZipEntry entry = zip.getEntry(name);
                    if (entry == null || entry.isDirectory()) continue;
                    try (var in = zip.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (Exception ignored) {
            // Corrupt/odd archive -- fall through to the Modrinth lookup below.
        }
        return null;
    }

    private static byte[] iconBytesFromModrinth(String packName) {
        if (packName == null || packName.isBlank()) return null;
        try {
            ModrinthClient client = new ModrinthClient();
            List<ModrinthClient.Hit> hits = client.search(packName, "modpack");
            if (hits.isEmpty()) return null;
            ModrinthClient.Hit hit = null;
            for (ModrinthClient.Hit h : hits) {
                if (h.name() != null && h.name().equalsIgnoreCase(packName)) { hit = h; break; }
            }
            if (hit == null) hit = hits.get(0);
            if (hit.iconUrl() == null || hit.iconUrl().isBlank()) return null;
            return client.downloadBytes(hit.iconUrl());
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes png/jpg/webp bytes with ImageIO (TwelveMonkeys provides the WebP reader). */
    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) return null;
            var readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(iis);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeName(String name) {
        String base = (name == null || name.isBlank()) ? "modpack" : name;
        String safe = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 60 ? safe.substring(0, 60) : safe;
    }
}