package com.dbadmin.ui;

import com.dbadmin.service.ScriptFolder;
import com.dbadmin.service.compare.CompareReportFiles;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** File choosers for compare reports (.dbcompare) and their HTML exports; "Reports" next to the app by default. */
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
        return save(owner, current, name, CompareReportFiles.EXTENSION, "DBAdmin compare report");
    }

    /** Asks where to save the HTML version; null if cancelled. */
    static Path saveHtml(Window owner, Path report, String name) {
        String base = report != null ? report.getFileName().toString().replaceFirst("\\.dbcompare$", "") : name;
        return save(owner, report, base, ".html", "HTML page");
    }

    /** Asks for a report to open; null if cancelled. */
    static Path open(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Compare Report");
        chooser.setInitialDirectory(reportDir());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("DBAdmin compare report", "*" + CompareReportFiles.EXTENSION));
        File file = chooser.showOpenDialog(owner);
        return file == null ? null : file.toPath();
    }

    private static Path save(Window owner, Path near, String name, String extension, String description) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save " + description);
        File dir = near != null && near.getParent() != null ? near.getParent().toFile() : reportDir();
        chooser.setInitialDirectory(dir.isDirectory() ? dir : reportDir());
        chooser.setInitialFileName(name + extension);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, "*" + extension));
        File file = chooser.showSaveDialog(owner);
        if (file == null) return null;
        String path = file.getPath();
        return Path.of(path.toLowerCase().endsWith(extension) ? path : path + extension);
    }

    private static File reportDir() {
        try {
            return ScriptFolder.reportDir().toFile();
        } catch (UncheckedIOException e) {
            return new File(System.getProperty("user.home"));
        }
    }
}
