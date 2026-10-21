package com.dbadmin.model;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User preferences, saved to ~/.dbadmin/settings.json. New fields need defaults: older files lack them. */
public class AppSettings {

    public enum Theme {
        LIGHT("Light"), DARK("Dark");

        private final String label;

        Theme(String label) {
            this.label = label;
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
    private static final Map<String, String> DARK_STATUS_COLORS = Map.of(
            "MATCH", "#1f4a2a", "CHANGED", "#5a3d12", "ONLY_SOURCE", "#173a5e", "ONLY_TARGET", "#5e1f26",
            "DUPLICATE_KEY", "#47265a");

    private Theme theme = Theme.LIGHT;
    /** Status cell colors per theme (status name -> #rrggbb); missing entries use the defaults. */
    private Map<Theme, Map<String, String>> statusColors = new EnumMap<>(Theme.class);

    public Theme getTheme() { return theme; }
    public void setTheme(Theme theme) { this.theme = theme; }
    public Map<Theme, Map<String, String>> getStatusColors() { return statusColors; }
    public void setStatusColors(Map<Theme, Map<String, String>> statusColors) {
        this.statusColors = statusColors == null ? new EnumMap<>(Theme.class) : new EnumMap<>(statusColors);
    }

    /** The color of a status cell in a theme: the user's choice, else the default. */
    public String statusColor(Theme theme, String status) {
        Map<String, String> chosen = statusColors.get(theme);
        if (chosen != null && chosen.get(status) != null) return chosen.get(status);
        return defaultStatusColor(theme, status);
    }

    public static String defaultStatusColor(Theme theme, String status) {
        return (theme == Theme.DARK ? DARK_STATUS_COLORS : LIGHT_STATUS_COLORS).getOrDefault(status, "#cccccc");
    }

    public void setStatusColor(Theme theme, String status, String color) {
        statusColors.computeIfAbsent(theme, t -> new LinkedHashMap<>()).put(status, color);
    }

    public AppSettings copy() {
        AppSettings c = new AppSettings();
        c.theme = theme;
        statusColors.forEach((t, m) -> c.statusColors.put(t, new LinkedHashMap<>(m)));
        return c;
    }
}
