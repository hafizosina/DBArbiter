package com.dbadmin.ui;

import com.dbadmin.license.License;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
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

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final Label status;
    private final Label license = new Label();
    /** "Update available: v0.1.20", shown when a newer release is out; opens its download page. */
    private final javafx.scene.control.Hyperlink update = new javafx.scene.control.Hyperlink();

    AppFooter(String statusText) {
        status = new Label(statusText);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        Label version = new Label("v" + version() + " · built " + build("date"));
        version.getStyleClass().add("hint-small");
        version.setTooltip(new Tooltip("Commit " + build("commit")));
        for (Label l : new Label[]{status, license, version}) l.setPadding(new Insets(3, 8, 3, 8));
        update.setVisible(false);
        update.setManaged(false);
        getChildren().addAll(status, update, license, version);
        setAlignment(Pos.CENTER_LEFT);
    }

    /** The license line next to the version: who it is for and how long it lasts; orange near and after the end. */
    void showLicense(Optional<License> current, LocalDate today) {
        boolean warn = false;
        String text;
        if (current.isEmpty()) {
            text = "No license";
        } else {
            License l = current.get();
            long left = l.daysLeft(today);
            String ends = l.expires() == null ? "" : DATE.format(l.expires());
            text = switch (l.status(today)) {
                case VALID -> l.type() == License.Type.LIFETIME ? "Licensed to " + l.name()
                        : l.type() == License.Type.TRIAL ? "Trial · " + (left + 1) + " days left · " + l.name()
                        : "Licensed to " + l.name() + " · until " + ends;
                case EXPIRING -> (l.type() == License.Type.TRIAL ? "Trial" : l.name()) + " · "
                        + (left == 0 ? "last day today" : (left + 1) + " days left");
                case GRACE -> l.name() + " · ended " + ends + ", renew within "
                        + (License.GRACE_DAYS + left + 1) + " days";
                case EXPIRED -> (l.type() == License.Type.TRIAL ? "Trial" : l.name()) + " ended " + ends;
            };
            warn = l.status(today) != License.Status.VALID;
        }
        license.setText(text);
        license.getStyleClass().setAll("label", warn ? "binding-warning" : "hint-small");
    }

    /** Shows that a newer release is out; clicking it runs {@code open} (the download page in the browser). */
    void showUpdate(String newVersion, Runnable open) {
        update.setText("Update available: v" + newVersion);
        update.setTooltip(new Tooltip("Open the download page of v" + newVersion));
        update.setOnAction(e -> open.run());
        update.setVisible(true);
        update.setManaged(true);
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
