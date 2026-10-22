package com.dbadmin.ui;

import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.EditableQuery;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Finds out whether shown rows can be edited: one table with a primary key, on a writable connection. */
final class TableEditing {

    /**
     * What edits write to. {@code columns}: the table's column names (lower case); {@code generated}: those the
     * database fills in (serial, auto increment), lower case. With {@code readOnlyConnection} nothing is
     * editable, but the user is told why.
     */
    record Target(DatabaseSession session, TableRef table, List<String> primaryKey, Set<String> columns,
                  Set<String> generated, boolean readOnlyConnection) {
    }

    private TableEditing() {
    }

    /**
     * Calls back (FX thread) with the target if the table has a primary key; otherwise calls
     * {@code notEditable} with the reason.
     */
    static void forTable(DatabaseSession session, TableRef table, Consumer<Target> then, Consumer<String> notEditable) {
        if (table.isView()) {
            notEditable.accept(table.displayName() + " is a view; edit the table it reads from");
            return;
        }
        Async.run(session, () -> {
            List<String> pk = session.primaryKey(table);
            Set<String> columns = new HashSet<>();
            session.columnDefs(table).forEach(c -> columns.add(c.name().toLowerCase(Locale.ROOT)));
            if (pk.isEmpty()) return null;
            Set<String> generated = new HashSet<>();
            session.generatedColumns(table).forEach(c -> generated.add(c.toLowerCase(Locale.ROOT)));
            return new Target(session, table, pk, columns, generated, session.getConfig().isReadOnly());
        }, target -> {
            if (target != null) then.accept(target);
            else notEditable.accept(table.displayName() + " has no primary key, so a changed row could not be found again");
        }, ex -> notEditable.accept("could not read the structure of " + table.displayName() + " (" + ex.getMessage() + ")"));
    }

    /**
     * Calls back if {@code sql} is a plain SELECT from one table that has a primary key; otherwise
     * calls {@code notEditable} with the reason. Statements that read no table get neither.
     */
    static void forQuery(DatabaseSession session, String sql, Consumer<Target> then, Consumer<String> notEditable) {
        EditableQuery.Check check = EditableQuery.check(sql);
        if (check.reason() != null) {
            notEditable.accept(check.reason());
            return;
        }
        if (check.source() == null) return;
        EditableQuery.Source source = check.source();
        String written = source.qualifier() == null ? source.table() : source.qualifier() + "." + source.table();
        Async.run(session, () -> session.resolveTable(source.qualifier(), source.table()), table -> {
            if (table != null) forTable(session, table, then, notEditable);
            else notEditable.accept(written + " is a view or was not found as a table");
        }, ex -> notEditable.accept("could not look up " + written + " (" + ex.getMessage() + ")"));
    }
}
