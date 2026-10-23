package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.SchemaChange;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * What can be done to a whole table, from the navigator's menu and the table tab's Table menu: Backup… and Rename… on a
 * writable connection; Truncate…, Reset… and Drop… only when its warning level is 0 (no warnings: a connection the
 * user trusts, not production). Backup and Rename show their SQL in a form and run as the warning level says; Truncate,
 * Reset and Drop always ask, showing the SQL, with Cancel as the default.
 */
final class TableActions {

    enum Change { EMPTIED, DROPPED, RENAMED, BACKED_UP }

    /** Told after a change was made, to show it: reload tabs, refresh the navigator. */
    interface Host {
        void tableChanged(DatabaseSession session, TableRef table, Change change);
    }

    /** The table an action works on, and its session. */
    record Target(DatabaseSession session, TableRef table) {
    }

    private TableActions() {
    }

    /** Backup and Rename: a writable connection, a table (not a view). */
    static boolean canChange(ConnectionConfig c, TableRef t) {
        return c != null && t != null && !c.isReadOnly() && !t.isView();
    }

    /** Truncate, Reset and Drop: as {@link #canChange}, and the connection warns about nothing (level 0). */
    static boolean canDestroy(ConnectionConfig c, TableRef t) {
        return canChange(c, t) && c.getWarningLevel() == 0;
    }

    /** The menu items, for the target the supplier gives when one is clicked; {@link Items#showFor} sets which show. */
    static Items items(Supplier<Window> owner, Supplier<Target> target, Host host) {
        return new Items(owner, target, host);
    }

    static final class Items {
        final MenuItem backup = new MenuItem("Backup Table…");
        final MenuItem rename = new MenuItem("Rename Table…");
        final SeparatorMenuItem separator = new SeparatorMenuItem();
        final MenuItem truncate = new MenuItem("Truncate Table…");
        final MenuItem reset = new MenuItem("Reset Table…");
        final MenuItem drop = new MenuItem("Drop Table…");

        Items(Supplier<Window> owner, Supplier<Target> target, Host host) {
            backup.setOnAction(e -> with(target, t -> backup(owner.get(), t, host)));
            rename.setOnAction(e -> with(target, t -> rename(owner.get(), t, host)));
            truncate.setOnAction(e -> with(target, t -> destroy(owner.get(), t, host, Change.EMPTIED, false)));
            reset.setOnAction(e -> with(target, t -> destroy(owner.get(), t, host, Change.EMPTIED, true)));
            drop.setOnAction(e -> with(target, t -> destroy(owner.get(), t, host, Change.DROPPED, false)));
        }

        List<MenuItem> all() {
            return List.of(backup, rename, separator, truncate, reset, drop);
        }

        /** Shows the items the connection allows for the table; none for a view or on a read-only connection. */
        void showFor(ConnectionConfig c, TableRef t) {
            boolean change = canChange(c, t);
            boolean destroy = canDestroy(c, t);
            backup.setVisible(change);
            rename.setVisible(change);
            separator.setVisible(destroy);
            truncate.setVisible(destroy);
            reset.setVisible(destroy);
            drop.setVisible(destroy);
        }

        boolean anyShown() {
            return backup.isVisible() || truncate.isVisible();
        }

        private static void with(Supplier<Target> target, java.util.function.Consumer<Target> action) {
            Target t = target.get();
            if (t != null) action.accept(t);
        }
    }

    // ---- the actions ----

    private static void backup(Window owner, Target t, Host host) {
        DbType type = t.session().getConfig().getType();
        StructureDialog d = new StructureDialog(owner, "Backup Table", "A copy of " + t.table().displayName()
                + " with all its rows (columns and rows; no keys, indexes or defaults)");
        TextField name = new TextField(SchemaChange.backupName(t.table(), LocalDate.now()));
        d.row("New table", name);
        d.statements(() -> SchemaChange.backupTable(type, t.table(), name.getText()), name.textProperty());
        InAppDialogs.showAndWait(d).ifPresent(sql -> {
            if (RunWarning.confirm(owner, t.session().getConfig(), sql)) run(owner, t, sql, host, Change.BACKED_UP);
        });
    }

    private static void rename(Window owner, Target t, Host host) {
        DbType type = t.session().getConfig().getType();
        StructureDialog d = new StructureDialog(owner, "Rename Table", "Rename " + t.table().displayName()
                + " (scripts and views that use the old name are not changed)");
        TextField name = new TextField(t.table().name());
        d.row("New name", name);
        d.statements(() -> SchemaChange.renameTable(type, t.table(), name.getText()), name.textProperty());
        d.setOnShown(e -> name.selectAll());
        InAppDialogs.showAndWait(d).ifPresent(sql -> {
            if (RunWarning.confirm(owner, t.session().getConfig(), sql)) run(owner, t, sql, host, Change.RENAMED);
        });
    }

    /** Truncate ({@code restartIds} false), Reset (true) or Drop: asks, showing the SQL, then runs. */
    private static void destroy(Window owner, Target t, Host host, Change change, boolean restartIds) {
        DbType type = t.session().getConfig().getType();
        // SQLite keeps AUTOINCREMENT ids in sqlite_sequence, which exists only once such a table does
        Async.run(t.session(), () -> restartIds && type == DbType.SQLITE
                && !t.session().execute("SELECT name FROM sqlite_master WHERE name = 'sqlite_sequence'", 1).data().rows().isEmpty(), sequence -> {
            List<String> sql = change == Change.DROPPED ? SchemaChange.dropTable(type, t.table())
                    : restartIds ? SchemaChange.resetTable(type, t.table(), sequence) : SchemaChange.truncateTable(type, t.table());
            String name = t.table().displayName();
            String on = " on \"" + t.session().getConfig().getName() + "\"?";
            String header;
            String what;
            String button;
            if (change == Change.DROPPED) {
                header = "Drop table " + name + on;
                what = "The table and all its rows are deleted. This cannot be undone.";
                button = "Drop Table";
            } else if (restartIds) {
                header = "Reset table " + name + on;
                what = "Every row is deleted and the ids start again from 1. This cannot be undone.";
                button = "Reset Table";
            } else {
                header = "Truncate table " + name + on;
                what = "Every row is deleted" + (type == DbType.MYSQL ? " (MySQL also restarts the ids)" : "; the ids keep counting")
                        + ". This cannot be undone.";
                button = "Truncate Table";
            }
            if (confirm(owner, header, what, sql, button)) run(owner, t, sql, host, change);
        }, ex -> error(owner, "Could not prepare it", ex.getMessage()));
    }

    /** A warning with the SQL; true only if the user picks {@code action} (Cancel is the default). */
    private static boolean confirm(Window owner, String header, String what, List<String> sql, String action) {
        ButtonType go = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        Alert ask = new Alert(Alert.AlertType.WARNING, "", go, ButtonType.CANCEL);
        if (owner != null) ask.initOwner(owner);
        ask.setHeaderText(header);
        TextArea text = new TextArea(what + "\n\n" + String.join(";\n", sql) + ";");
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefRowCount(5);
        text.getStyleClass().add("sql-messages");
        ask.getDialogPane().setContent(text);
        ask.getDialogPane().setPrefWidth(600);
        ((Button) ask.getDialogPane().lookupButton(go)).setDefaultButton(false);
        ((Button) ask.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return InAppDialogs.showAndWait(ask).orElse(ButtonType.CANCEL) == go;
    }

    private static void run(Window owner, Target t, List<String> sql, Host host, Change change) {
        Async.run(t.session(), () -> {
            t.session().applySchemaChange(sql);
            return null;
        }, x -> host.tableChanged(t.session(), t.table(), change), ex -> error(owner, "Nothing was changed", ex.getMessage()));
    }

    private static void error(Window owner, String header, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        if (owner != null) a.initOwner(owner);
        a.setHeaderText(header);
        InAppDialogs.show(a);
    }
}
