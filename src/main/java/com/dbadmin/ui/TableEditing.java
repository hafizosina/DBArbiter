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
     * What edits write to. {@code columns}: the table's column names (lower case). With
     * {@code readOnlyConnection} nothing is editable, but the user is told why.
     */
    record Target(DatabaseSession session, TableRef table, List<String> primaryKey, Set<String> columns,
                  boolean readOnlyConnection) {
    }

    private TableEditing() {
    }

    /** Calls back (FX thread) if the table has a primary key; never for one without. */
    static void forTable(DatabaseSession session, TableRef table, Consumer<Target> then) {
        Async.run(session, () -> {
            List<String> pk = session.primaryKey(table);
            Set<String> columns = new HashSet<>();
            session.columnDefs(table).forEach(c -> columns.add(c.name().toLowerCase(Locale.ROOT)));
            return pk.isEmpty() ? null
                    : new Target(session, table, pk, columns, session.getConfig().isReadOnly());
        }, target -> {
            if (target != null) then.accept(target);
        }, ex -> { });
    }

    /** Calls back if {@code sql} is a plain SELECT from one table that has a primary key. */
    static void forQuery(DatabaseSession session, String sql, Consumer<Target> then) {
        EditableQuery.source(sql).ifPresent(source -> Async.run(session,
                () -> session.resolveTable(source.qualifier(), source.table()),
                table -> {
                    if (table != null) forTable(session, table, then);
                }, ex -> { }));
    }
}
