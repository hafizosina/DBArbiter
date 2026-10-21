package com.dbadmin.ui;

import com.dbadmin.model.AppSettings;
import com.dbadmin.model.AppSettings.Theme;
import com.dbadmin.service.SettingsStore;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.List;

/**
 * The app's settings and its theme: app.css always, plus dark.css for the dark theme.
 * Scenes and popups registered here follow theme changes immediately. FX thread only.
 */
final class Themes {

    private static final String APP_CSS = css("app.css");
    private static final String DARK_CSS = css("dark.css");
    private static final SettingsStore STORE = new SettingsStore();
    private static AppSettings settings = STORE.load();
    private static final ObjectProperty<Theme> THEME = new SimpleObjectProperty<>(settings.getTheme());
    /** The settings shown right now (saved ones, or a preview from the Settings dialog). */
    private static final ObjectProperty<AppSettings> SHOWN = new SimpleObjectProperty<>(settings.copy());

    private Themes() {
    }

    private static String css(String name) {
        return Themes.class.getResource("/com/dbadmin/" + name).toExternalForm();
    }

    static AppSettings settings() {
        return settings.copy();
    }

    /** Saves the settings and applies them. */
    static void save(AppSettings changed) {
        STORE.save(changed);
        settings = changed.copy();
        THEME.set(settings.getTheme());
        SHOWN.set(settings.copy());
    }

    /** Shows settings without saving them (e.g. a preview in the Settings dialog). */
    static void preview(AppSettings shown) {
        THEME.set(shown.getTheme());
        SHOWN.set(shown.copy());
    }

    /** Undoes a preview: back to the saved settings. */
    static void revertPreview() {
        THEME.set(settings.getTheme());
        SHOWN.set(settings.copy());
    }

    /** The background color of a data-compare status cell in the theme shown now. */
    static String statusColor(String status) {
        return SHOWN.get().statusColor(THEME.get(), status);
    }

    /** Fires when colors may have changed (theme or status colors), e.g. to repaint grids. */
    static javafx.beans.value.ObservableValue<AppSettings> shownSettings() {
        return SHOWN;
    }

    static Theme current() {
        return THEME.get();
    }

    static void install(Scene scene) {
        apply(scene.getStylesheets(), THEME.get());
        THEME.addListener((o, a, theme) -> apply(scene.getStylesheets(), theme));
    }

    /** For popups, which have their own scene. */
    static void install(Parent popupContent) {
        apply(popupContent.getStylesheets(), THEME.get());
        THEME.addListener((o, a, theme) -> apply(popupContent.getStylesheets(), theme));
    }

    private static void apply(ObservableList<String> sheets, Theme theme) {
        sheets.setAll(theme == Theme.DARK ? List.of(APP_CSS, DARK_CSS) : List.of(APP_CSS));
    }
}
