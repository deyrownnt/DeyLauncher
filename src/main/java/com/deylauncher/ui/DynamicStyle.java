package com.deylauncher.ui;

import java.util.Base64;
import java.util.Locale;

/**
 * theme.css hardcodes absolute px sizes per class (deliberately, for a
 * consistent baseline look) rather than relative em units, so a plain
 * "-fx-font-size on root" doesn't cascade into them. Instead, we regenerate
 * a small override stylesheet from the *base* numbers below, scaled by
 * whatever the user picked, and load it as a data: URI stylesheet after
 * theme.css -- same specificity, later in the list, so it wins. This is
 * what makes the Launcher settings tab's sliders update the window live.
 */
public class DynamicStyle {

    // Base (100%) values, matching theme.css's original numbers.
    private static final double BASE_ROOT_FONT = 14;
    private static final double BASE_LOGO = 34;
    private static final double BASE_TITLE = 22;
    private static final double BASE_SUBTITLE = 13;
    private static final double BASE_CARD_HEADING = 28;
    private static final double BASE_FIELD_LABEL = 13;
    private static final double BASE_NOTICE = 13.5;
    private static final double BASE_INPUT_FONT = 16;
    private static final double BASE_PLAY_FONT = 19;
    private static final double BASE_LOG_FONT = 14.5;
    private static final double BASE_PILL_PADDING_V = 10;
    private static final double BASE_PILL_PADDING_H = 22;
    private static final double BASE_INPUT_PADDING = 12;
    private static final double BASE_PLAY_PADDING = 18;
    private static final double BASE_CARD_PADDING = 36;

    public static String dataUri(double uiScale, double textScale, String fontFamily) {
        String css = build(uiScale, textScale, fontFamily);
        String base64 = Base64.getEncoder().encodeToString(css.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "data:text/css;base64," + base64;
    }

    private static String build(double ui, double text, String fontFamily) {
        StringBuilder sb = new StringBuilder();
        sb.append(".root-pane { -fx-font-family: \"").append(fontFamily).append("\", sans-serif; ")
                .append("-fx-font-size: ").append(px(BASE_ROOT_FONT * text)).append("; }\n");

        sb.append(".theme-dark .logo-glyph, .theme-light .logo-glyph { -fx-font-size: ")
                .append(px(BASE_LOGO * text)).append("; }\n");
        sb.append(".title-label { -fx-font-size: ").append(px(BASE_TITLE * text)).append("; }\n");
        sb.append(".subtitle-label { -fx-font-size: ").append(px(BASE_SUBTITLE * text)).append("; }\n");
        sb.append(".card-heading { -fx-font-size: ").append(px(BASE_CARD_HEADING * text)).append("; }\n");
        sb.append(".field-label { -fx-font-size: ").append(px(BASE_FIELD_LABEL * text)).append("; }\n");
        sb.append(".notice-label { -fx-font-size: ").append(px(BASE_NOTICE * text)).append("; }\n");
        sb.append(".log-area { -fx-font-size: ").append(px(BASE_LOG_FONT * text)).append("; }\n");

        sb.append(".input-field { -fx-font-size: ").append(px(BASE_INPUT_FONT * text))
                .append("; -fx-padding: ").append(px(BASE_INPUT_PADDING * ui)).append("; }\n");
        sb.append(".play-button { -fx-font-size: ").append(px(BASE_PLAY_FONT * text))
                .append("; -fx-padding: ").append(px(BASE_PLAY_PADDING * ui)).append("; }\n");
        sb.append(".pill-button { -fx-padding: ").append(px(BASE_PILL_PADDING_V * ui))
                .append(" ").append(px(BASE_PILL_PADDING_H * ui)).append("; }\n");
        // No max-width here anymore -- .play-card is now the right-hand details panel next
        // to the left tile column, so it should fill the space HBox.setHgrow gives it rather
        // than being capped to the width of the old single centered card.
        sb.append(".play-card { -fx-padding: ").append(px(BASE_CARD_PADDING * ui)).append("; }\n");

        return sb.toString();
    }

    private static String px(double value) {
        return String.format(Locale.US, "%.1fpx", value);
    }
}
