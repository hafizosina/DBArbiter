package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.transfer.Csv;
import com.dbadmin.service.transfer.Json;
import com.dbadmin.service.transfer.RowSink;
import com.dbadmin.service.transfer.SqlInserts;
import com.dbadmin.service.transfer.Xlsx;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Export of a table's rows to a file (CSV, Excel, SQL INSERT, JSON) and import into it (CSV, after a
 * preview, in one transaction; an SQL file opens in a script tab instead). Used by the table tab and
 * the navigator's right-click menu.
 */
final class TableTransfer {

    /** What the window around the table provides. */
    interface Host {
        void status(String text);

        /** Opens an SQL file in a script tab on this connection. */
        void openScript(Path file, ConnectionConfig connection);

        /** Rows were imported into the table (open views of it reload). */
        void imported(DatabaseSession session, TableRef table);
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    /** The folder last exported to or imported from, for the next file chooser. */
    private static File lastDir;

    private TableTransfer() {
    }

    /** @param currentQuery the table tab's current query (a plain read-only SELECT), or null */
    static void export(Window owner, DatabaseSession session, TableRef table, String currentQuery, Host host) {
        Async.run(session, () -> session.primaryKey(table), primaryKey -> {
            ExportDialog.Choice choice = InAppDialogs.showAndWait(new ExportDialog(owner, table.displayName(),
                    !primaryKey.isEmpty(), currentQuery != null)).orElse(null);
            if (choice == null) return;
            FileChooser chooser = chooser("Export " + table.name());
            chooser.setInitialFileName(table.name() + "-" + STAMP.format(LocalDateTime.now()) + choice.format().extension);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(choice.format().label, "*" + choice.format().extension));
            File file = chooser.showSaveDialog(owner);
            if (file == null) return;
            lastDir = file.getParentFile();
            Path path = file.toPath();
            host.status("Exporting " + table.name() + "…");
            Async.run(session, () -> {
                String sql = choice.currentQuery() ? currentQuery : "SELECT * FROM " + session.qualifiedName(table);
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
                    Writer text = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
                    RowSink sink = switch (choice.format()) {
                        case CSV -> {
                            text.write('﻿'); // so Excel reads the file as UTF-8
                            yield Csv.writer(text, choice.delimiter(), choice.header());
                        }
                        case XLSX -> Xlsx.writer(out, table.name());
                        case SQL -> SqlInserts.writer(text, session.getConfig().getType(), session.qualifiedName(table),
                                session.identifierQuote(), choice.skipExisting(), primaryKey);
                        case JSON -> Json.writer(text);
                    };
                    long n = session.streamRows(sql, sink, rows -> Platform.runLater(
                            () -> host.status("Exporting " + table.name() + ": " + String.format("%,d", rows) + " rows…")));
                    text.flush();
                    return n;
                }
            }, n -> host.status("Exported " + String.format("%,d", n) + " row(s) of " + table.name() + " to " + path),
                    ex -> {
                        try {
                            Files.deleteIfExists(path); // no half-written file
                        } catch (java.io.IOException ignored) {
                        }
                        host.status("Export failed");
                        error(owner, "Could not export " + table.name(), ex);
                    });
        }, ex -> error(owner, "Could not export " + table.name(), ex));
    }

    /** Asks for a CSV or SQL file; a CSV is previewed, then its rows are inserted in one transaction. */
    static void importData(Window owner, DatabaseSession session, TableRef table, Host host) {
        if (session.getConfig().isReadOnly()) {
            error(owner, "Read-only connection", new IllegalStateException("Untick \"Read-only\" in Edit Connection to import into "
                    + table.name() + "."));
            return;
        }
        FileChooser chooser = chooser("Import into " + table.name());
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("CSV or SQL", "*.csv", "*.tsv", "*.txt", "*.sql"),
                new FileChooser.ExtensionFilter("All files", "*"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) return;
        lastDir = file.getParentFile();
        Path path = file.toPath();
        if (file.getName().toLowerCase().endsWith(".sql")) {
            host.openScript(path, session.getConfig()); // reviewed and run by the user
            return;
        }
        Async.run(() -> Files.readString(path, StandardCharsets.UTF_8), text -> Async.run(session,
                () -> List.of(session.columnDefs(table).stream().map(DatabaseSession.ColumnDef::name).toList(), session.primaryKey(table)),
                meta -> {
                    List<String> columns = meta.get(0);
                    List<String> primaryKey = meta.get(1);
                    ImportDialog.Plan plan = InAppDialogs.showAndWait(new ImportDialog(owner, file.getName(), text,
                            table.displayName(), columns, primaryKey)).orElse(null);
                    if (plan != null) insert(owner, session, table, primaryKey, plan, host);
                }, ex -> error(owner, "Could not read the columns of " + table.name(), ex)),
                ex -> error(owner, "Could not read " + file.getName(), ex));
    }

    /** Leaves out rows whose primary key already exists (when the key is imported), asks, then inserts. */
    private static void insert(Window owner, DatabaseSession session, TableRef table, List<String> primaryKey,
                               ImportDialog.Plan plan, Host host) {
        List<Integer> keyAt = primaryKey.stream().map(k -> plan.columns().stream().map(String::toLowerCase).toList()
                .indexOf(k.toLowerCase())).toList();
        boolean keyImported = !primaryKey.isEmpty() && !keyAt.contains(-1);
        host.status("Checking which rows already exist…");
        Async.run(session, () -> keyImported ? session.keyValues(table, primaryKey) : Set.<String>of(), existing -> {
            List<List<String>> fresh = plan.rows().stream()
                    .filter(r -> !keyImported || !existing.contains(DatabaseSession.keyText(keyAt.stream().map(r::get).toList())))
                    .toList();
            int skipped = plan.rows().size() - fresh.size();
            host.status("");
            if (fresh.isEmpty()) {
                info(owner, "Nothing to import", "All " + skipped + " row(s) already exist in " + table.name() + " (same "
                        + String.join(", ", primaryKey) + ").");
                return;
            }
            ButtonType go = new ButtonType("Insert " + fresh.size() + " row(s)", ButtonBar.ButtonData.OK_DONE);
            Alert ask = new Alert(Alert.AlertType.CONFIRMATION, "Write to \"" + session.getConfig().getName() + "\" · "
                    + table.displayName() + " in one transaction: if any row fails, nothing is imported."
                    + (skipped > 0 ? "\n\n" + skipped + " row(s) are left out: their " + String.join(", ", primaryKey) + " already exists." : ""),
                    go, ButtonType.CANCEL);
            ask.initOwner(owner);
            ask.setHeaderText("Insert " + fresh.size() + " row(s) into " + table.name() + "?");
            if (InAppDialogs.showAndWait(ask).orElse(ButtonType.CANCEL) != go) return;
            host.status("Importing into " + table.name() + "…");
            Async.run(session, () -> session.insertRows(table, plan.columns(), fresh, n -> Platform.runLater(
                    () -> host.status("Importing into " + table.name() + ": " + String.format("%,d", n) + " rows…"))), n -> {
                host.status("Imported " + n + " row(s) into " + table.name() + (skipped > 0 ? " (" + skipped + " already there, left out)" : ""));
                host.imported(session, table);
            }, ex -> {
                host.status("Import failed: nothing was imported");
                error(owner, "Nothing was imported", ex);
            });
        }, ex -> error(owner, "Could not check the existing rows of " + table.name(), ex));
    }

    private static FileChooser chooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        File dir = lastDir != null && lastDir.isDirectory() ? lastDir : new File(System.getProperty("user.home"));
        chooser.setInitialDirectory(dir);
        return chooser;
    }

    private static void error(Window owner, String header, Throwable ex) {
        Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(ex.getMessage()), ButtonType.OK);
        alert.initOwner(owner);
        alert.setHeaderText(header);
        alert.getDialogPane().setMinWidth(480);
        InAppDialogs.show(alert);
    }

    private static void info(Window owner, String header, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        alert.initOwner(owner);
        alert.setHeaderText(header);
        InAppDialogs.show(alert);
    }
}
