package com.deylauncher.server;

public enum ServerType {
    VANILLA, PURPUR, FABRIC, FORGE;

    public String displayName() {
        return switch (this) {
            case VANILLA -> "Vanilla";
            case PURPUR -> "Purpur";
            case FABRIC -> "Fabric";
            case FORGE -> "Forge";
        };
    }
}
