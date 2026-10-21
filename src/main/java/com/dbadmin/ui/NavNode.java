package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;

/** What a node in the navigator tree represents. */
record NavNode(Kind kind, String label, ConnectionConfig connection, Namespace namespace, TableRef table) {

    enum Kind { CONNECTION, NAMESPACE, FOLDER, TABLE, MESSAGE }

    static NavNode connection(ConnectionConfig c) {
        return new NavNode(Kind.CONNECTION, null, c, null, null);
    }

    static NavNode namespace(ConnectionConfig c, Namespace ns) {
        return new NavNode(Kind.NAMESPACE, ns.label(), c, ns, null);
    }

    static NavNode folder(ConnectionConfig c, String label) {
        return new NavNode(Kind.FOLDER, label, c, null, null);
    }

    static NavNode table(ConnectionConfig c, TableRef t) {
        return new NavNode(Kind.TABLE, t.name(), c, null, t);
    }

    static NavNode message(String text) {
        return new NavNode(Kind.MESSAGE, text, null, null, null);
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
