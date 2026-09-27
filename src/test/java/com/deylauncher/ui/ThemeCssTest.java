package com.deylauncher.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text-level checks on {@code theme.css} -- the one source file whose mistakes Java can never catch.
 *
 * <p>A style class that is added in code but never defined in the stylesheet, or a dark rule with no
 * light twin, compiles perfectly and only shows up as "the launcher looks wrong". The two failures
 * this locks down are ones that actually happened:
 *
 * <ul>
 *   <li>{@code role-pill} / {@code given-badge} were added to the shared-server cards but never
 *       styled, so they rendered as bare, ellipsised text ("OWN…");</li>
 *   <li>every launcher dialog puts its theme class on the {@code DialogPane} <em>itself</em>
 *       ({@code dialogPane.getStyleClass().addAll("root-pane", "theme-dark")}), which a descendant
 *       selector such as {@code .theme-dark .dialog-pane} can never match -- so each dialog kept
 *       JavaFX's light grey pane background in the middle of a dark window.</li>
 * </ul>
 */
class ThemeCssTest {

    private static final String THEME_CSS = "/theme.css";

    /** {@code .theme-dark .role-pill} is a descendant rule; only the same-node form themes a dialog. */
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{");

    /** Style classes the shared-server UI adds in code and therefore must find in the stylesheet. */
    private static final List<String> SHARED_SERVER_CLASSES = List.of(
            "role-pill",
            "role-pill-none",
            "given-badge",
            "given-badge-sm",
            "sync-inline",
            "sync-progress",
            "badge-online",
            "badge-offline");

    @Test
    void darkAndLightThemesStayInStep() throws IOException {
        Set<String> dark = themeRelativeSelectors(".theme-dark");
        Set<String> light = themeRelativeSelectors(".theme-light");

        assertTrue(dark.size() > 100, "theme.css should carry a full set of themed rules, not a handful");

        // Both directions are reported separately: whichever half was edited, the other half is what
        // names the rule that is missing.
        assertEquals(new TreeSet<>(difference(light, dark)), new TreeSet<>(),
                "every dark rule needs a light twin (see the LIGHT THEME banner in theme.css)");
        assertEquals(new TreeSet<>(difference(dark, light)), new TreeSet<>(),
                "every light rule needs a dark twin (see the LIGHT THEME banner in theme.css)");
        assertEquals(dark.size(), light.size());
    }

    @Test
    void sharedServerBadgesAndSyncProgressAreStyledInBothThemes() throws IOException {
        Set<String> selectors = allSelectors();
        List<String> missing = new ArrayList<>();
        for (String styleClass : SHARED_SERVER_CLASSES) {
            for (String theme : List.of(".theme-dark", ".theme-light")) {
                // An exact selector match, not a substring one: ".theme-dark .role-pill" is a prefix
                // of ".theme-dark .role-pill-none", so a contains() check would pass on the wrong rule.
                if (!selectors.contains(theme + " ." + styleClass)) {
                    missing.add(theme + " ." + styleClass);
                }
            }
        }
        assertEquals(List.of(), missing, "these style classes are used in code but never themed");
    }

    @Test
    void dialogsCanBeThemedOnTheDialogPaneItself() throws IOException {
        Set<String> selectors = allSelectors();

        // LauncherApp adds the theme class to the DialogPane node (not to an ancestor), so only the
        // same-node form matches. Without these rules a dark launcher draws its own dialogs -- the
        // role picker on the Players/Permissions tabs included -- on a light grey pane.
        assertTrue(selectors.contains(".theme-dark.dialog-pane"),
                "theme.css must theme the dialog pane itself, not only .theme-dark .dialog-pane");
        assertTrue(selectors.contains(".theme-light.dialog-pane"),
                "theme.css must theme the light dialog pane itself too");
        assertTrue(selectors.contains(".theme-dark.dialog-pane > .content"),
                "the dialog's content area needs the same-node form as well");
        assertTrue(selectors.contains(".theme-dark.dialog-pane > .button-bar"),
                "the dialog's button bar needs the same-node form as well");
    }

    /** The stylesheet as shipped, so the checks never depend on the working directory. */
    private static String css() throws IOException {
        try (InputStream in = ThemeCssTest.class.getResourceAsStream(THEME_CSS)) {
            assertNotNull(in, THEME_CSS + " must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Every selector in the file, normalised to single spaces, comments removed. */
    private static Set<String> allSelectors() throws IOException {
        String body = css().replaceAll("(?s)/\\*.*?\\*/", "");
        Set<String> selectors = new LinkedHashSet<>();
        Matcher m = RULE.matcher(body);
        while (m.find()) {
            String chunk = m.group(1).trim();
            // @media/@font-face blocks have no element selectors; JavaFX CSS has no nested rules, so
            // skipping the whole at-rule is safe.
            if (chunk.startsWith("@")) continue;
            for (String selector : chunk.split(",")) {
                String normalised = selector.replaceAll("\\s+", " ").trim();
                if (!normalised.isEmpty()) selectors.add(normalised);
            }
        }
        return selectors;
    }

    /**
     * Every selector that hangs off a theme class, with the theme class itself replaced by
     * {@code @T} so the two themes can be compared directly: {@code .theme-dark .role-pill} and
     * {@code .theme-light .role-pill} both come back as {@code @T .role-pill}, while the same-node
     * dialog selector comes back as the distinct {@code @T.dialog-pane}.
     */
    private static Set<String> themeRelativeSelectors(String themeClass) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (String selector : allSelectors()) {
            if (selector.startsWith(themeClass)) {
                keys.add(selector.replace(themeClass, "@T"));
            }
        }
        return keys;
    }

    private static Set<String> difference(Set<String> from, Set<String> without) {
        Set<String> copy = new LinkedHashSet<>(from);
        copy.removeAll(without);
        return copy;
    }
}
