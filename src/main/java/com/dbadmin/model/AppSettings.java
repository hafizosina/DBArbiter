package com.dbadmin.model;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User preferences, saved to ~/.dbadmin/settings.json. New fields need defaults: older files lack them. */
public class AppSettings {

    public enum Theme {
        LIGHT("Light"), DARK("Dark"), CATPPUCCIN("Catppuccin Mocha");

        private final String label;

        Theme(String label) {
            this.label = label;
        }

        /** Dark themes build on dark.css (Catppuccin Mocha adds its palette on top). */
        public boolean isDark() {
            return this != LIGHT;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Data-compare statuses that get a color, in display order. */
    public static final List<String> STATUSES = List.of("MATCH", "CHANGED", "ONLY_SOURCE", "ONLY_TARGET", "DUPLICATE_KEY");

    private static final Map<String, String> LIGHT_STATUS_COLORS = Map.of(
            "MATCH", "#d7f0da", "CHANGED", "#ffe0b2", "ONLY_SOURCE", "#d0e7fb", "ONLY_TARGET", "#fbd5d8",
            "DUPLICATE_KEY", "#ead5f2");
    /** Mocha's green, peach, blue, red and mauve, dimmed to sit under its text. */
    private static final Map<String, String> CATPPUCCIN_STATUS_COLORS = Map.of(
            "MATCH", "#2f4a3a", "CHANGED", "#5a4632", "ONLY_SOURCE", "#2d3f5f", "ONLY_TARGET", "#5a2f3f",
            "DUPLICATE_KEY", "#4a3a64");
    private static final Map<String, String> DARK_STATUS_COLORS = Map.of(
            "MATCH", "#1f4a2a", "CHANGED", "#5a3d12", "ONLY_SOURCE", "#173a5e", "ONLY_TARGET", "#5e1f26",
            "DUPLICATE_KEY", "#47265a");

    private Theme theme = Theme.LIGHT;
    /** Status cell colors per theme (status name -> #rrggbb); missing entries use the defaults. */
    private Map<Theme, Map<String, String>> statusColors = new EnumMap<>(Theme.class);

    /** At startup, ask GitHub whether a newer release is out (and say so in the footer). */
    private boolean checkForUpdates = true;
    /** Ask "Close DBArbiter?" before the window closes (unsaved work is asked about either way). */
    private boolean confirmExit;
    /** At startup, open the scripts, tables and compares that were open when the app was last closed. */
    private boolean reopenTabs;

    public boolean isConfirmExit() { return confirmExit; }
    public void setConfirmExit(boolean confirmExit) { this.confirmExit = confirmExit; }
    public boolean isReopenTabs() { return reopenTabs; }
    public void setReopenTabs(boolean reopenTabs) { this.reopenTabs = reopenTabs; }
    public boolean isCheckForUpdates() { return checkForUpdates; }
    public void setCheckForUpdates(boolean checkForUpdates) { this.checkForUpdates = checkForUpdates; }
    public Theme getTheme() { return theme; }
    public void setTheme(Theme theme) { this.theme = theme; }
    public Map<Theme, Map<String, String>> getStatusColors() { return statusColors; }
    public void setStatusColors(Map<Theme, Map<String, String>> statusColors) {
        // not new EnumMap<>(map): that throws for an empty map that is not an EnumMap (as read from JSON)
        this.statusColors = new EnumMap<>(Theme.class);
        if (statusColors != null) this.statusColors.putAll(statusColors);
    }

    /** The color of a status cell in a theme: the user's choice, else the default. */
    public String statusColor(Theme theme, String status) {
        Map<String, String> chosen = statusColors.get(theme);
        if (chosen != null && chosen.get(status) != null) return chosen.get(status);
        return defaultStatusColor(theme, status);
    }

    public static String defaultStatusColor(Theme theme, String status) {
        Map<String, String> colors = switch (theme) {
            case LIGHT -> LIGHT_STATUS_COLORS;
            case DARK -> DARK_STATUS_COLORS;
            case CATPPUCCIN -> CATPPUCCIN_STATUS_COLORS;
        };
        return colors.getOrDefault(status, "#cccccc");
    }

    public void setStatusColor(Theme theme, String status, String color) {
        statusColors.computeIfAbsent(theme, t -> new LinkedHashMap<>()).put(status, color);
    }

    public AppSettings copy() {
        AppSettings c = new AppSettings();
        c.theme = theme;
        c.checkForUpdates = checkForUpdates;
        c.confirmExit = confirmExit;
        c.reopenTabs = reopenTabs;
        statusColors.forEach((t, m) -> c.statusColors.put(t, new LinkedHashMap<>(m)));
        return c;
    }
}
