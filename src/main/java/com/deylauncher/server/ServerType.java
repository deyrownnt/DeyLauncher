package com.deylauncher.server;

public enum ServerType {
    VANILLA, PURPUR, FABRIC, FORGE, NEOFORGE;

    public String displayName() {
        return switch (this) {
            case VANILLA -> "Vanilla";
            case PURPUR -> "Purpur";
            case FABRIC -> "Fabric";
            case FORGE -> "Forge";
            case NEOFORGE -> "NeoForge";
        };
    }

    /**
     * Plain-English name, so a JavaFX {@code ComboBox<ServerType>} shows "NeoForge" instead of the
     * enum constant's "NEOFORGE". Persisted data (ServerSoftwareMarker, server.json) uses
     * {@link #name()} and is unaffected by this.
     */
    @Override
    public String toString() {
        return displayName();
    }
}
