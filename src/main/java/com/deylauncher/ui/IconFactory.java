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
        SUN
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
        };
    }
}
