package com.dbadmin.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The bar at the bottom of a window: status text on the left, the app version and build date on the right.
 * The version comes from version.properties, which Maven fills in from pom.xml (&lt;revision&gt;).
 */
class AppFooter extends HBox {

    private static final Properties BUILD = new Properties();

    static {
        try (InputStream in = AppFooter.class.getResourceAsStream("/com/dbadmin/version.properties")) {
            if (in != null) BUILD.load(in);
        } catch (IOException ignored) {
            // shown as "dev"
        }
    }

    private final Label status;

    AppFooter(String statusText) {
        status = new Label(statusText);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        Label version = new Label("v" + version() + " · built " + build("date"));
        version.getStyleClass().add("hint-small");
        version.setTooltip(new Tooltip("Commit " + build("commit")));
        for (Label l : new Label[]{status, version}) l.setPadding(new Insets(3, 8, 3, 8));
        getChildren().addAll(status, version);
        setAlignment(Pos.CENTER_LEFT);
    }

    /** The status text on the left. */
    Label status() {
        return status;
    }

    /** E.g. "0.1.0". */
    static String version() {
        return build("version");
    }

    /** "version", "commit" or "date"; "dev" when not built by Maven (e.g. run from an IDE). */
    static String build(String key) {
        String v = BUILD.getProperty(key, "");
        return v.isBlank() || v.startsWith("${") ? "dev" : v;
    }
}
