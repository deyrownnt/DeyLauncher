package com.deylauncher.identity;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Minecraft skins are PNGs at specific dimensions: 64x64 (the modern
 * format, used since 1.8) or 64x32 (the legacy format, still technically
 * accepted by the game). Anything else -- a random photo, a non-PNG, a
 * weirdly-sized image -- gets rejected here with a clear reason, rather
 * than silently accepted and only failing (or looking broken in-game)
 * later.
 */
public class SkinValidator {

    public record Result(boolean valid, String reason, int width, int height) {
        public static Result ok(int w, int h) {
            return new Result(true, null, w, h);
        }
        public static Result fail(String reason) {
            return new Result(false, reason, 0, 0);
        }
    }

    public Result validate(Path file) {
        if (!file.toString().toLowerCase().endsWith(".png")) {
            return Result.fail("Not a PNG file -- Minecraft skins must be .png.");
        }

        try (ImageInputStream iis = ImageIO.createImageInputStream(file.toFile())) {
            if (iis == null) {
                return Result.fail("Couldn't read this file as an image.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return Result.fail("This file isn't a valid PNG image.");
            }
            ImageReader reader = readers.next();
            if (!reader.getFormatName().equalsIgnoreCase("png")) {
                return Result.fail("This file isn't actually a PNG (detected: " + reader.getFormatName() + ").");
            }
            reader.setInput(iis);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            reader.dispose();

            boolean modern = width == 64 && height == 64;
            boolean legacy = width == 64 && height == 32;
            if (!modern && !legacy) {
                return Result.fail("Minecraft skins must be 64x64 (or legacy 64x32) -- this image is "
                        + width + "x" + height + ".");
            }
            return Result.ok(width, height);
        } catch (IOException e) {
            return Result.fail("Couldn't read this file: " + e.getMessage());
        }
    }
}
