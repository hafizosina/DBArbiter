package com.dbadmin.model;

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

    private Theme theme = Theme.LIGHT;

    public Theme getTheme() { return theme; }
    public void setTheme(Theme theme) { this.theme = theme; }

    public AppSettings copy() {
        AppSettings c = new AppSettings();
        c.theme = theme;
        return c;
    }
}
