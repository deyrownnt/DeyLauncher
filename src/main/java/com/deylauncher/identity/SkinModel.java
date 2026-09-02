package com.deylauncher.identity;

/** Matches Minecraft's own "variant" values exactly (lowercase in the API), so no translation is needed at upload time. */
public enum SkinModel {
    CLASSIC, SLIM;

    /** The exact lowercase string Minecraft Services / skin metadata expects. */
    public String apiValue() {
        return name().toLowerCase();
    }

    public static SkinModel fromApiValue(String value) {
        return "slim".equalsIgnoreCase(value) ? SLIM : CLASSIC;
    }
}
