package com.dbadmin.ui;

import java.util.List;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;

/** What a node in the navigator tree represents. */
/** {@code sequence}: for a SEQUENCE node, its row of {@link com.dbadmin.service.DatabaseSession#listSequences}. */
record NavNode(Kind kind, String label, ConnectionConfig connection, Namespace namespace, TableRef table, List<Object> sequence) {

    enum Kind { CONNECTION, NAMESPACE, FOLDER, TABLE, SEQUENCE, MESSAGE }

    static NavNode connection(ConnectionConfig c) {
        return new NavNode(Kind.CONNECTION, null, c, null, null, null);
    }

    static NavNode namespace(ConnectionConfig c, Namespace ns) {
        return new NavNode(Kind.NAMESPACE, ns.label(), c, ns, null, null);
    }

    static NavNode folder(ConnectionConfig c, String label) {
        return new NavNode(Kind.FOLDER, label, c, null, null, null);
    }

    static NavNode table(ConnectionConfig c, TableRef t) {
        return new NavNode(Kind.TABLE, t.name(), c, null, t, null);
    }

    /** A sequence of the namespace, labelled "name · last value". */
    static NavNode sequence(ConnectionConfig c, Namespace ns, List<Object> row) {
        String last = String.valueOf(row.get(1));
        return new NavNode(Kind.SEQUENCE, row.get(0) + (last.isEmpty() ? "" : "  · " + last), c, ns, null, row);
    }

    static NavNode message(String text) {
        return new NavNode(Kind.MESSAGE, text, null, null, null, null);
    }

    /** Nodes whose children are fetched from the database when first expanded. */
    boolean isLazy() {
        return kind == Kind.CONNECTION || kind == Kind.NAMESPACE;
    }

    @Override
    public String toString() {
        // connection name is read live so renames show up
        return kind == Kind.CONNECTION ? connection.getName() : label;
    }
}
