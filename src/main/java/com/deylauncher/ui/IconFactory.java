package com.deylauncher.ui;

import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/**
 * Cross-platform icon system for DeyLauncher.
 *
 * All icons are vector paths rendered by JavaFX, so they do not depend on
 * emoji/unicode glyphs being installed on Windows or Linux.
 */
public final class IconFactory {

    private IconFactory() {}

    public enum Icon {
        PICKAXE,
        SETTINGS,
        TOOLS,
        PLAY,
        PUZZLE,
        DOWNLOAD,
        LOCK,
        TRASH,
        CHECK,
        LOGOUT,
        FOLDER,
        CLIPBOARD,
        MOON,
        SUN,
        CHEVRON_DOWN,
        CHEVRON_UP,
        FILE,
        STOP,
        SEARCH,
        ADD,
        FILTER,
        MODPACK,
        SERVER,
        REFRESH,
        CONTROLLER,
        PEOPLE,
        // ---- Top-bar nav tab icons (see the NAV TABS block in theme.css) ----
        /** Home: filled house with a knocked-out door. */
        HOUSE,
        /** Servers: two stacked rack units, each with a boss and a status light knocked out. */
        SERVER_RACK
    }

    public static Node create(Icon icon, double size) {
        SVGPath path = new SVGPath();
        path.setContent(pathData(icon));
        path.setScaleX(size / 24.0);
        path.setScaleY(size / 24.0);
        path.getStyleClass().add("icon-svg");
        return path;
    }

    private static String pathData(Icon icon) {
        return switch (icon) {
            case PLAY -> "M8 5v14l11-7z";
            case CHECK -> "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
            case TRASH -> "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM8 9h8v10H8V9zm7.5-5-1-1h-5l-1 1H5v2h14V4z";
            case SETTINGS -> "M19.43 12.98c.04-.32.07-.65.07-.98s-.02-.66-.07-.98l2.11-1.65-2-3.46-2.49 1c-.52-.4-1.08-.73-1.69-.98L15 3h-4l-.36 2.93c-.61.25-1.18.59-1.69.98l-2.49-1-2 3.46 2.11 1.65c-.04.32-.08.65-.08.98s.03.66.08.98l-2.11 1.65 2 3.46 2.49-1c.52.4 1.08.73 1.69.98L11 21h4l.36-2.93c.61-.25 1.18-.59 1.69-.98l2.49 1 2-3.46-2.11-1.65zM13 15.5A3.5 3.5 0 1 1 13 8a3.5 3.5 0 0 1 0 7.5z";
            case FOLDER -> "M3 5h7l2 2h9v12H3V5zm2 4v8h14V9H5z";
            case DOWNLOAD -> "M11 3h2v9.17l3.59-3.58L18 10l-6 6-6-6 1.41-1.41L11 12.17V3zm-6 15h14v2H5v-2z";
            case LOCK -> "M18 8h-1V6a4 4 0 0 0-8 0v2H8c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-5 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4zm2-9h-4V6a2 2 0 0 1 4 0v2z";
            case LOGOUT -> "M10 17l5-5-5-5v3H3v4h7v3zm9-14H9c-1.1 0-2 .9-2 2v3h2V5h10v14H9v-3H7v3c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z";
            case CLIPBOARD -> "M16 1H8c-1.1 0-2 .9-2 2v1H4c-1.1 0-2 .9-2 2v15c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2v-1h2c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-2V3c0-1.1-.9-2-2-2zm0 18H4V6h2v11c0 1.1.9 2 2 2h8v0zm4-3H8V3h8v2h4v11z";
            case MOON -> "M20 15.31A8.5 8.5 0 0 1 8.69 4 8.5 8.5 0 1 0 20 15.31z";
            case SUN -> "M12 4V2h0v2zm0 18v-2h0v2zM4.93 6.34 3.51 4.93 4.93 6.34zM20.49 19.07l-1.42-1.41 1.42 1.41zM4 13H2v-2h2v2zm18 0h-2v-2h2v2zM4.93 17.66l-1.42 1.41 1.42-1.41zM20.49 4.93l-1.42 1.41 1.42-1.41zM12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z";
            case PUZZLE -> "M20 11h-2.1a2.9 2.9 0 1 0-5.8 0H10V8.9a2.9 2.9 0 1 0-5.8 0V11H2v6h2.2a2.9 2.9 0 1 0 5.8 0V17h2.1v2.1a2.9 2.9 0 1 0 5.8 0V17H20v-6z";
            case TOOLS -> "M21.7 19.3l-5.1-5.1a6.5 6.5 0 0 0-8.5-8.5l3.2 3.2-2.8 2.8-3.2-3.2a6.5 6.5 0 0 0 8.5 8.5l5.1 5.1a2 2 0 0 0 2.8-2.8z";
            case PICKAXE -> "M4 5h7l9 9-3 3-9-9v7H4V5zm2 2v3h3L6 7zm9.59 7L18 15.41 16.41 17 14 14.59 15.59 13z";
            case CHEVRON_DOWN -> "M12 5L5 19 19 19Z";
            case CHEVRON_UP -> "M12 19L5 5 19 5Z";
            case FILE -> "M4 4h16v16h-16zM16 2l4 4-4 0-0-4z";
            case STOP -> "M6 6h12v12h-12z";
            case SEARCH -> "M15.5 15.5a3.5 3.5 0 1 1-7-7a3.5 3.5 0 0 0-7-7 3.5 3.5 0 1 0 7 7z";
            case ADD -> "M5 11h14v2h-14zM11 5v14h2v-14z";
            case FILTER -> "M4.25 5.61C6.27 8.2 10 13 10 13v6c0 .55.45 1 1 1h2c.55 0 1-.45 1-1v-6s3.72-4.8 5.74-7.39c.51-.66.04-1.61-.79-1.61H5.04c-.83 0-1.3.95-.79 1.61z";
            // An isometric "modpack" box: a lid (top face) plus the two side faces, with a thin
            // centre seam so it reads as a package rather than a solid cube at 17-20px.
            case MODPACK -> "M12 2 L3 6.5 L12 11 L21 6.5 Z M3 7.6 L11.4 11.8 L11.4 20.4 L3 16.2 Z M12.6 11.8 L21 7.6 L21 16.2 L12.6 20.4 Z";
            case SERVER -> "M21 12a9 9 0 1 0-18 0a9 9 0 0 1 18 0zM7.5 12h9M12 3v18";
            // A circular arrow: a near-full ring drawn as an arc plus a solid arrow head, all in
            // one filled path so it needs no stroke settings and no font -- the same reason every
            // other icon here is a vector instead of a "\u21BB"/emoji glyph that may be missing
            // on a given Windows or Linux box.
            case REFRESH -> "M12 5V2L7.5 6.5 12 11V8a5 5 0 1 1-5 5H4a8 8 0 1 0 8-8z";
            // Game controller: rounded body + two grips, D-pad and face buttons read fine at 22-26px.
            case CONTROLLER -> "M7.5 6h9a5.5 5.5 0 0 1 5.4 6.6l-.9 4.5a2.6 2.6 0 0 1-4.6 1.1L15 16H9l-1.4 2.2a2.6 2.6 0 0 1-4.6-1.1l-.9-4.5A5.5 5.5 0 0 1 7.5 6zM8 10v1.5H6.5V13H8v1.5h1.5V13H11v-1.5H9.5V10zm7.25 1a1.25 1.25 0 1 0 0 2.5 1.25 1.25 0 0 0 0-2.5zm2.5 3a1.25 1.25 0 1 0 0 2.5 1.25 1.25 0 0 0 0-2.5z";
            // Two overlapping people (friends list).
            case PEOPLE -> "M9 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm7-6a3 3 0 1 1-2.13 5.12A5 5 0 0 1 16 15v1h5v-1a4 4 0 0 0-4.06-4A3 3 0 0 1 16 6zM9 14c-3.33 0-6 1.79-6 4v1h12v-1c0-2.21-2.67-4-6-4z";
            // Home tab: a filled house -- roof overhang, body, and a door knocked out of the body.
            // The door is wound the opposite way round from the outline (clockwise outside,
            // counter-clockwise inside) so the default NON_ZERO fill rule punches it out as a hole
            // instead of painting it back in. Same trick as the rack's boss/LED cut-outs below.
            case HOUSE -> "M13 2 L23 11 L19 11 L19 22 L5 22 L5 11 L1 11 Z"
                    + " M10 22 L14 22 L14 14.5 L10 14.5 Z";
            // Servers tab: two stacked rack units (a 1U and a 2U), each showing a mounting boss on
            // the left and a status light on the right as knocked-out holes.
            case SERVER_RACK -> "M3 3.5 L21 3.5 L21 10 L3 10 Z"
                    + " M3 12 L21 12 L21 18.5 L3 18.5 Z"
                    + " M6 8.5 L9.5 8.5 L9.5 5.5 L6 5.5 Z"
                    + " M16.5 8.5 L19 8.5 L19 5.5 L16.5 5.5 Z"
                    + " M6 17 L9.5 17 L9.5 14 L6 14 Z"
                    + " M16.5 17 L19 17 L19 14 L16.5 14 Z";
        };
    }
}
