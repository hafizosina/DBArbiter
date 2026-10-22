package com.dbadmin.ui;

import com.dbadmin.service.ScriptFolder;
import com.dbadmin.service.compare.CompareReportFiles;
import javafx.stage.Window;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * In-app file dialogs (see {@link FileDialog}) for compare reports (.dbcompare) and their HTML exports;
 * "Reports" next to the app by default.
 */
final class CompareReportChooser {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private CompareReportChooser() {
    }

    /** "DataCompare-m_product-UAT-vs-Prod-20261022-1405" (no extension). */
    static String defaultName(String what, String source, String target) {
        return ScriptFolder.safe(what + "-" + source + "-vs-" + target + "-" + STAMP.format(LocalDateTime.now()));
    }

    /** Where to save a compare, and whether with its results (rows, verdicts) or only its settings. */
    record SaveChoice(Path file, boolean withResults) {
    }

    /**
     * The Save dialog of a compare, with the choice of what to save: the settings and results (the default when
     * there are results) or the settings only (opening such a file compares again). Null if cancelled.
     */
    static SaveChoice saveCompare(Window owner, Path current, String name, boolean hasResults, boolean hasSettings) {
        Path home = reportDir();
        Path start = current != null && current.getParent() != null ? current.getParent() : home;
        // a compare opened from (or saved to) a file: its own name first
        String suggested = current != null ? current.getFileName().toString() : name + CompareReportFiles.EXTENSION;
        FileDialog dialog = new FileDialog(owner, FileDialog.Mode.SAVE, "Save Compare", home, "Reports", start,
                CompareReportFiles.EXTENSION, "compare files", suggested);
        javafx.scene.control.ToggleGroup what = new javafx.scene.control.ToggleGroup();
        javafx.scene.control.RadioButton results = new javafx.scene.control.RadioButton("Settings and results (rows and verdicts, as they are now)");
        javafx.scene.control.RadioButton settings = new javafx.scene.control.RadioButton("Settings only (opening the file compares again)");
        results.setToggleGroup(what);
        settings.setToggleGroup(what);
        results.setDisable(!hasResults);
        settings.setDisable(!hasSettings);
        (hasResults ? results : settings).setSelected(true);
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, new javafx.scene.control.Label("Save:"),
                new javafx.scene.layout.VBox(4, results, settings));
        dialog.addOptions(row);
        Path file = InAppDialogs.showAndWait(dialog).orElse(null);
        return file == null ? null : new SaveChoice(file, results.isSelected());
    }

    /** Asks where to save the HTML version; null if cancelled. */
    static Path saveHtml(Window owner, Path report, String name) {
        String base = report != null ? report.getFileName().toString().replaceFirst("\\.dbcompare$", "") : name;
        return save(owner, report, base, ".html", "Export HTML", "HTML pages");
    }

    /** Asks for a report to open; null if cancelled. */
    static Path open(Window owner) {
        Path home = reportDir();
        return InAppDialogs.showAndWait(new FileDialog(owner, FileDialog.Mode.OPEN, "Open Compare", home, "Reports", home,
                CompareReportFiles.EXTENSION, "compare files", null)).orElse(null);
    }

    private static Path save(Window owner, Path near, String name, String extension, String title, String kind) {
        Path home = reportDir();
        Path start = near != null && near.getParent() != null ? near.getParent() : home;
        return InAppDialogs.showAndWait(new FileDialog(owner, FileDialog.Mode.SAVE, title, home, "Reports", start,
                extension, kind, name + extension)).orElse(null);
    }

    private static Path reportDir() {
        try {
            return ScriptFolder.reportDir();
        } catch (UncheckedIOException e) {
            return Path.of(System.getProperty("user.home"));
        }
    }
}
