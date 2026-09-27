package com.deylauncher.servers;

/**
 * One tab of a server's management window. Named as its own type (rather than passing raw tab titles
 * around) so the permission rules in {@link ManagerRole} can be checked by the UI without the UI
 * having to re-encode "which tab is this" as a string comparison.
 */
public enum ServerTab {
    CONSOLE("Console"),
    PROPERTIES("Properties"),
    PLAYERS("Players"),
    ADDONS("Addons"),
    FILES("Files"),
    SETTINGS("Settings"),
    PERMISSIONS("Permissions");

    private final String title;

    ServerTab(String title) {
        this.title = title;
    }

    /** The title the server management window shows for this tab. */
    public String title() {
        return title;
    }
}
