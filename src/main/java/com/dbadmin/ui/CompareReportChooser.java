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

    /** Asks where to save a report; null if cancelled. */
    static Path saveReport(Window owner, Path current, String name) {
        return save(owner, current, name, CompareReportFiles.EXTENSION, "Save Compare", "compare files");
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
