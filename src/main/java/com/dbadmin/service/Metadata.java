package com.dbadmin.service;

import com.dbadmin.service.DatabaseSession.ColumnDef;

import com.dbadmin.model.ColumnInfo;
import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads a database's structure for a {@link DatabaseSession}, which delegates to it: schemas, tables and views,
 * columns, keys, indexes, constraints, foreign keys, triggers, view definitions and DDL. Where the databases differ
 * (their catalog queries), the branches on the database type are here, in one place; a per-database dialect would
 * split them. Uses the session's current connection, on the session's worker thread.
 */
final class Metadata {

    private static final Set<String> WANTED_TABLE_TYPES = Set.of("TABLE", "VIEW", "MATERIALIZED VIEW");

    private final DatabaseSession session;
    private final ConnectionConfig config;
    /** The catalog queries of this database. */
    private final Dialect dialect;

    Metadata(DatabaseSession session) {
        this.session = session;
        this.config = session.getConfig();
        this.dialect = Dialect.of(config.getType());
    }

    /** The session's current connection (it changes when the session reconnects). For the dialects too. */
    Connection connection() {
        return session.connection();
    }

    /** The table's name as written in SQL (schema.table, quoted where needed). */
    String qualifiedName(TableRef t) throws SQLException {
        return session.qualifiedName(t);
    }

    /** The first value of the first row as text; null when there are no rows. */
    static String first(List<List<Object>> rows) {
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get(0));
    }

    public List<Namespace> listNamespaces() throws SQLException {
        return dialect.namespaces(this);
    }

    public List<TableRef> listTables(Namespace ns) throws SQLException {
        DatabaseMetaData md = connection().getMetaData();
        List<TableRef> result = new ArrayList<>();
        try (ResultSet rs = md.getTables(ns.catalog(), ns.schema(), "%", supportedTableTypes(md))) {
            while (rs.next()) {
                result.add(new TableRef(ns.catalog(), ns.schema(),
                        rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE")));
            }
        }
        return result;
    }

    private String[] supportedTableTypes(DatabaseMetaData md) throws SQLException {
        Set<String> types = new HashSet<>();
        try (ResultSet rs = md.getTableTypes()) {
            while (rs.next()) {
                String t = rs.getString(1).trim();
                if (WANTED_TABLE_TYPES.contains(t.toUpperCase())) types.add(t);
            }
        }
        return types.toArray(String[]::new);
    }

    /** The schema (PostgreSQL) or database (MySQL) that unqualified table names resolve to. */
    public String defaultNamespace() throws SQLException {
        return dialect.defaultNamespace(this);
    }

    /** Column names and types, for autocomplete. */
    public List<ColumnInfo> columnInfo(TableRef t) throws SQLException {
        List<ColumnInfo> result = new ArrayList<>();
        try (ResultSet rs = connection().getMetaData().getColumns(t.catalog(), t.schema(), t.name(), "%")) {
            while (rs.next()) result.add(new ColumnInfo(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")));
        }
        return result;
    }

    public TableData listColumns(TableRef t) throws SQLException {
        DatabaseMetaData md = connection().getMetaData();
        Set<String> pk = new HashSet<>();
        try (ResultSet rs = md.getPrimaryKeys(t.catalog(), t.schema(), t.name())) {
            while (rs.next()) pk.add(rs.getString("COLUMN_NAME"));
        }
        List<List<Object>> rows = new ArrayList<>();
        try (ResultSet rs = md.getColumns(t.catalog(), t.schema(), t.name(), "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                rows.add(List.of(
                        rs.getInt("ORDINAL_POSITION"),
                        name,
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        "YES".equals(rs.getString("IS_NULLABLE")) ? "YES" : "NO",
                        pk.contains(name) ? "PK" : "",
                        nullToEmpty(rs.getString("COLUMN_DEF")),
                        nullToEmpty(rs.getString("REMARKS"))));
            }
        }
        return new TableData(List.of("#", "Column", "Type", "Size", "Nullable", "Key", "Default", "Comment"), rows);
    }

    public List<ColumnDef> columnDefs(TableRef t) throws SQLException {
        List<ColumnDef> result = new ArrayList<>();
        try (ResultSet rs = connection().getMetaData().getColumns(t.catalog(), t.schema(), t.name(), "%")) {
            while (rs.next()) {
                int digits = rs.getInt("DECIMAL_DIGITS");
                result.add(new ColumnDef(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"),
                        rs.wasNull() ? null : digits, "YES".equals(rs.getString("IS_NULLABLE")), rs.getString("COLUMN_DEF")));
            }
        }
        return result;
    }

    /** The columns exactly as defined, for editing them (see {@link ColumnSpec}). */
    public List<ColumnSpec> columnSpecs(TableRef t) throws SQLException {
        return dialect.columns(this, t);
    }

    /** Columns the database fills itself on insert: serial / identity / auto-increment, or a nextval default. */
    public Set<String> generatedColumns(TableRef t) throws SQLException {
        Set<String> result = new HashSet<>();
        try (ResultSet rs = connection().getMetaData().getColumns(t.catalog(), t.schema(), t.name(), "%")) {
            while (rs.next()) {
                String def = rs.getString("COLUMN_DEF");
                if ("YES".equals(rs.getString("IS_AUTOINCREMENT")) || def != null && def.startsWith("nextval(")) {
                    result.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return result;
    }

    /** The primary key's columns in key order; empty if the table has none. */
    public List<String> primaryKey(TableRef t) throws SQLException {
        Map<Short, String> columns = new TreeMap<>();
        try (ResultSet rs = connection().getMetaData().getPrimaryKeys(t.catalog(), t.schema(), t.name())) {
            while (rs.next()) columns.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(columns.values());
    }

    /** The SQL a view (or materialized view) is defined by, as the database reports it; null if unknown. */
    public String viewDefinition(TableRef t) throws SQLException {
        return dialect.viewDefinition(this, t);
    }

    /**
     * The statement(s) that create the table, view or materialized view, as text to read or copy:
     * MySQL's SHOW CREATE, SQLite's stored SQL, and for PostgreSQL built from the catalog (columns,
     * constraints as the server prints them, other indexes, comments).
     */
    public String ddl(TableRef t) throws SQLException {
        return dialect.ddl(this, t);
    }

    /**
     * The table a query reads from, given as written ("orders" or "shop.orders"); an unqualified
     * name is looked up in the default schema. Null if there is no such table (or it is a view).
     */
    public TableRef resolveTable(String qualifier, String name) throws SQLException {
        String ns = qualifier != null ? qualifier : defaultNamespace();
        for (Namespace n : listNamespaces()) {
            if (!n.label().equalsIgnoreCase(ns)) continue;
            for (TableRef t : listTables(n)) {
                if (t.name().equalsIgnoreCase(name) && !t.isView()) return t;
            }
        }
        return null;
    }

    /** One row per index, with its columns in order. */
    public TableData listIndexes(TableRef t) throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new HashMap<>();
        if (!t.isView()) {
            try (ResultSet rs = connection().getMetaData().getIndexInfo(t.catalog(), t.schema(), t.name(), false, true)) {
                while (rs.next()) {
                    if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                    String name = nullToEmpty(rs.getString("INDEX_NAME"));
                    String column = nullToEmpty(rs.getString("COLUMN_NAME"));
                    if ("D".equals(rs.getString("ASC_OR_DESC"))) column += " DESC";
                    columns.computeIfAbsent(name, k -> new ArrayList<>()).add(column);
                    unique.put(name, !rs.getBoolean("NON_UNIQUE"));
                }
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        columns.forEach((name, cols) -> rows.add(List.of(name, String.join(", ", cols), unique.get(name) ? "YES" : "NO")));
        return new TableData(List.of("Index", "Columns", "Unique"), rows);
    }

    /** Primary key, unique, check (and PostgreSQL exclusion) constraints. Foreign keys are listed separately. */
    public TableData listConstraints(TableRef t) throws SQLException {
        List<String> headers = List.of("Constraint", "Type", "Columns", "Definition");
        try {
            return new TableData(headers, dialect.constraints(this, t));
        } catch (SQLException e) {
            // e.g. an older MySQL without CHECK_CONSTRAINTS: fall back to what JDBC can tell
            return new TableData(headers, genericConstraints(t));
        }
    }

    /** Primary key and unique indexes, from standard JDBC metadata (used for SQLite and as a fallback). */
    List<List<Object>> genericConstraints(TableRef t) throws SQLException {
        DatabaseMetaData md = connection().getMetaData();
        List<List<Object>> rows = new ArrayList<>();
        Map<Short, String> pkColumns = new TreeMap<>();
        String pkName = null;
        try (ResultSet rs = md.getPrimaryKeys(t.catalog(), t.schema(), t.name())) {
            while (rs.next()) {
                pkColumns.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                pkName = rs.getString("PK_NAME");
            }
        }
        if (!pkColumns.isEmpty()) {
            String cols = String.join(", ", pkColumns.values());
            rows.add(List.of(pkName == null || pkName.isEmpty() ? "PRIMARY KEY" : pkName, "PRIMARY KEY", cols,
                    "PRIMARY KEY (" + cols + ")"));
        }
        String pkList = String.join(", ", pkColumns.values());
        for (List<Object> index : listIndexes(t).rows()) {
            String name = (String) index.get(0);
            // skip the index backing the primary key (same name, or SQLite's autoindex on the same columns)
            if ("YES".equals(index.get(2)) && !name.equals(pkName) && !index.get(1).equals(pkList)) {
                rows.add(List.of(name, "UNIQUE", index.get(1), "UNIQUE (" + index.get(1) + ")"));
            }
        }
        return rows;
    }

    /** One row per foreign key of this table, with its columns and the table/columns it references. */
    public TableData listForeignKeys(TableRef t) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        if (!t.isView()) {
            try (ResultSet rs = connection().getMetaData().getImportedKeys(t.catalog(), t.schema(), t.name())) {
                String name = null;
                String target = null;
                List<String> from = new ArrayList<>();
                List<String> to = new ArrayList<>();
                String onUpdate = null;
                String onDelete = null;
                while (rs.next()) {
                    if (rs.getShort("KEY_SEQ") == 1 && !from.isEmpty()) {
                        rows.add(foreignKeyRow(name, from, target, to, onUpdate, onDelete));
                        from = new ArrayList<>();
                        to = new ArrayList<>();
                    }
                    name = rs.getString("FK_NAME");
                    String targetNs = config.getType().qualifiesByCatalog() ? rs.getString("PKTABLE_CAT") : rs.getString("PKTABLE_SCHEM");
                    target = (targetNs == null ? "" : targetNs + ".") + rs.getString("PKTABLE_NAME");
                    from.add(rs.getString("FKCOLUMN_NAME"));
                    to.add(rs.getString("PKCOLUMN_NAME"));
                    onUpdate = rule(rs.getShort("UPDATE_RULE"));
                    onDelete = rule(rs.getShort("DELETE_RULE"));
                }
                if (!from.isEmpty()) rows.add(foreignKeyRow(name, from, target, to, onUpdate, onDelete));
            }
        }
        return new TableData(List.of("Foreign Key", "Columns", "References", "Referenced Columns", "On Update", "On Delete"), rows);
    }

    private static List<Object> foreignKeyRow(String name, List<String> from, String target, List<String> to,
                                              String onUpdate, String onDelete) {
        return List.of(name == null || name.isEmpty() ? "(unnamed)" : name, String.join(", ", from), target,
                String.join(", ", to), onUpdate, onDelete);
    }

    private static String rule(short rule) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            default -> "NO ACTION";
        };
    }

    public TableData listTriggers(TableRef t) throws SQLException {
        return new TableData(List.of("Trigger", "Timing", "Event", "Level", "Definition"), dialect.triggers(this, t));
    }

    /** Runs a metadata query with string parameters; nulls become empty strings. */
    List<List<Object>> query(String sql, String... params) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        Object v = rs.getObject(i);
                        row.add(v == null ? "" : v);
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
