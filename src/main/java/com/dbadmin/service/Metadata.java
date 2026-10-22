package com.dbadmin.service;

import com.dbadmin.service.DatabaseSession.ColumnDef;

import com.dbadmin.model.ColumnInfo;
import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    Metadata(DatabaseSession session) {
        this.session = session;
        this.config = session.getConfig();
    }

    private Connection connection() {
        return session.connection();
    }

    public List<Namespace> listNamespaces() throws SQLException {
        DatabaseMetaData md = connection().getMetaData();
        List<Namespace> result = new ArrayList<>();
        switch (config.getType()) {
            case POSTGRESQL -> {
                String catalog = connection().getCatalog();
                try (ResultSet rs = md.getSchemas()) {
                    while (rs.next()) {
                        String schema = rs.getString("TABLE_SCHEM");
                        if (schema.startsWith("pg_toast") || schema.startsWith("pg_temp")) continue;
                        result.add(new Namespace(catalog, schema, schema));
                    }
                }
            }
            case MYSQL -> {
                try (ResultSet rs = md.getCatalogs()) {
                    while (rs.next()) {
                        String db = rs.getString("TABLE_CAT");
                        result.add(new Namespace(db, null, db));
                    }
                }
            }
            case SQLITE -> result.add(new Namespace(null, null, "main"));
        }
        return result;
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
        return switch (config.getType()) {
            case POSTGRESQL -> connection().getSchema();
            case MYSQL -> connection().getCatalog();
            case SQLITE -> "main";
        };
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
        List<List<Object>> rows = switch (config.getType()) {
            case POSTGRESQL -> query("""
                    select pg_get_viewdef(c.oid, true)
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = ? and c.relname = ?
                    """, t.schema(), t.name());
            case MYSQL -> query("select VIEW_DEFINITION from information_schema.VIEWS where TABLE_SCHEMA = ? and TABLE_NAME = ?",
                    t.catalog(), t.name());
            case SQLITE -> query("select sql from sqlite_master where type = 'view' and name = ?", t.name());
        };
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get(0));
    }

    /**
     * The statement(s) that create the table, view or materialized view, as text to read or copy:
     * MySQL's SHOW CREATE, SQLite's stored SQL, and for PostgreSQL built from the catalog (columns,
     * constraints as the server prints them, other indexes, comments).
     */
    public String ddl(TableRef t) throws SQLException {
        return switch (config.getType()) {
            case MYSQL -> {
                String what = t.isView() ? "VIEW" : "TABLE";
                List<List<Object>> rows = query("SHOW CREATE " + what + " " + session.qualifiedName(t));
                yield rows.isEmpty() ? "" : rows.get(0).get(1) + ";";
            }
            case SQLITE -> {
                StringBuilder sb = new StringBuilder();
                for (List<Object> r : query("select sql from sqlite_master where tbl_name = ? and sql is not null "
                        + "order by case type when 'table' then 0 when 'view' then 0 when 'index' then 1 else 2 end, name", t.name())) {
                    sb.append(r.get(0)).append(";\n\n");
                }
                yield sb.toString().strip();
            }
            case POSTGRESQL -> postgresDdl(t);
        };
    }

    private String postgresDdl(TableRef t) throws SQLException {
        String rel = "(select c.oid from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = ? and c.relname = ?)";
        String s = t.schema();
        String n = t.name();
        List<List<Object>> head = query("select quote_ident(n.nspname) || '.' || quote_ident(c.relname), c.relkind, "
                + "pg_get_viewdef(c.oid, true), obj_description(c.oid, 'pg_class') "
                + "from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = ? and c.relname = ?", s, n);
        if (head.isEmpty()) return "";
        String name = String.valueOf(head.get(0).get(0));
        String kind = String.valueOf(head.get(0).get(1));
        StringBuilder sb = new StringBuilder();
        if (kind.equals("v") || kind.equals("m")) {
            sb.append(kind.equals("v") ? "CREATE OR REPLACE VIEW " : "CREATE MATERIALIZED VIEW ").append(name).append(" AS\n")
                    .append(String.valueOf(head.get(0).get(2)).stripTrailing());
            if (sb.charAt(sb.length() - 1) == ';') sb.setLength(sb.length() - 1);
            sb.append(kind.equals("m") ? "\nWITH DATA;\n" : ";\n");
        } else {
            List<String> lines = new ArrayList<>();
            for (List<Object> c : query("select quote_ident(a.attname), format_type(a.atttypid, a.atttypmod), a.attnotnull, "
                    + "coalesce(pg_get_expr(d.adbin, d.adrelid), ''), a.attidentity, a.attgenerated, "
                    + "coalesce(pg_get_serial_sequence(a.attrelid::regclass::text, a.attname), '') "
                    + "from pg_attribute a left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum "
                    + "where a.attrelid = " + rel + " and a.attnum > 0 and not a.attisdropped order by a.attnum", s, n)) {
                String identity = String.valueOf(c.get(4));
                String generated = String.valueOf(c.get(5));
                String expr = String.valueOf(c.get(3));
                // nextval of the column's own sequence is what serial creates: write it that way
                String serial = serialType(String.valueOf(c.get(1)), expr, String.valueOf(c.get(6)));
                StringBuilder col = new StringBuilder("    ").append(c.get(0)).append(' ');
                if (serial != null) {
                    // the default comes with serial; NOT NULL does too, but is written out as DBeaver does, to read clearly
                    lines.add(col.append(serial).append(" NOT NULL").toString());
                    continue;
                }
                col.append(c.get(1));
                if (generated.equals("s")) col.append(" GENERATED ALWAYS AS (").append(expr).append(") STORED");
                else if (identity.equals("a")) col.append(" GENERATED ALWAYS AS IDENTITY");
                else if (identity.equals("d")) col.append(" GENERATED BY DEFAULT AS IDENTITY");
                else if (!expr.isEmpty()) col.append(" DEFAULT ").append(expr);
                if (Boolean.TRUE.equals(c.get(2))) col.append(" NOT NULL");
                lines.add(col.toString());
            }
            for (List<Object> c : query("select quote_ident(conname), pg_get_constraintdef(oid, true) from pg_constraint "
                    + "where conrelid = " + rel + " and contype <> 'n' "
                    + "order by case contype when 'p' then 0 when 'u' then 1 when 'c' then 2 when 'f' then 3 else 4 end, conname", s, n)) {
                lines.add("    CONSTRAINT " + c.get(0) + " " + c.get(1));
            }
            sb.append("CREATE TABLE ").append(name).append(" (\n").append(String.join(",\n", lines)).append("\n);\n");
        }
        // indexes not created by a constraint (tables and materialized views)
        List<List<Object>> indexes = query("select pg_get_indexdef(i.indexrelid) from pg_index i "
                + "join pg_class ic on ic.oid = i.indexrelid where i.indrelid = " + rel
                + " and not exists (select 1 from pg_constraint k where k.conindid = i.indexrelid) order by ic.relname", s, n);
        if (!indexes.isEmpty()) sb.append('\n');
        for (List<Object> r : indexes) sb.append(r.get(0)).append(";\n");
        String comment = String.valueOf(head.get(0).get(3));
        List<List<Object>> columnComments = query("select quote_ident(a.attname), col_description(a.attrelid, a.attnum) "
                + "from pg_attribute a where a.attrelid = " + rel + " and a.attnum > 0 and not a.attisdropped "
                + "and col_description(a.attrelid, a.attnum) is not null order by a.attnum", s, n);
        if (!comment.isEmpty() || !columnComments.isEmpty()) sb.append('\n');
        String what = kind.equals("v") ? "VIEW" : kind.equals("m") ? "MATERIALIZED VIEW" : "TABLE";
        if (!comment.isEmpty()) sb.append("COMMENT ON ").append(what).append(' ').append(name).append(" IS ").append(literal(comment)).append(";\n");
        for (List<Object> r : columnComments) {
            sb.append("COMMENT ON COLUMN ").append(name).append('.').append(r.get(0)).append(" IS ").append(literal(String.valueOf(r.get(1)))).append(";\n");
        }
        return sb.toString().strip();
    }

    /**
     * "bigserial" for a bigint column whose default is nextval of the sequence it owns (likewise serial,
     * smallserial); null when the column is not like that (no default, a shared or hand-made sequence).
     */
    static String serialType(String type, String defaultExpr, String ownedSequence) {
        if (ownedSequence.isEmpty() || !defaultExpr.startsWith("nextval(")) return null;
        String seq = ownedSequence.substring(ownedSequence.lastIndexOf('.') + 1).replace("\"", "");
        if (!defaultExpr.contains(seq + "'::regclass)") && !defaultExpr.contains(seq + "\"'::regclass)")) return null;
        return switch (type) {
            case "integer" -> "serial";
            case "bigint" -> "bigserial";
            case "smallint" -> "smallserial";
            default -> null;
        };
    }

    private static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * The table a query reads from, given as written ("orders" or "shop.orders"); an unqualified
     * name is looked up in the default schema. Null if there is no such table (or it is a view).
     */
    public TableRef resolveTable(String qualifier, String name) throws SQLException {
        String ns = qualifier != null ? qualifier : config.getType() == DbType.SQLITE ? "main" : defaultNamespace();
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
            switch (config.getType()) {
                case POSTGRESQL -> {
                    return new TableData(headers, query("""
                            select c.conname,
                                   case c.contype when 'p' then 'PRIMARY KEY' when 'u' then 'UNIQUE'
                                                  when 'c' then 'CHECK' else 'EXCLUDE' end,
                                   coalesce((select string_agg(a.attname, ', ' order by k.ord)
                                             from unnest(c.conkey) with ordinality k(attnum, ord)
                                             join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum), ''),
                                   pg_get_constraintdef(c.oid)
                            from pg_constraint c
                            join pg_class r on r.oid = c.conrelid
                            join pg_namespace n on n.oid = r.relnamespace
                            where n.nspname = ? and r.relname = ? and c.contype in ('p', 'u', 'c', 'x')
                            order by case c.contype when 'p' then 0 when 'u' then 1 else 2 end, c.conname
                            """, t.schema(), t.name()));
                }
                case MYSQL -> {
                    return new TableData(headers, withDefinitions(query("""
                            select tc.CONSTRAINT_NAME, tc.CONSTRAINT_TYPE,
                                   coalesce((select group_concat(k.COLUMN_NAME order by k.ORDINAL_POSITION separator ', ')
                                             from information_schema.KEY_COLUMN_USAGE k
                                             where k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA and k.TABLE_NAME = tc.TABLE_NAME
                                               and k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME), ''),
                                   coalesce((select cc.CHECK_CLAUSE from information_schema.CHECK_CONSTRAINTS cc
                                             where cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                                               and cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME), '')
                            from information_schema.TABLE_CONSTRAINTS tc
                            where tc.TABLE_SCHEMA = ? and tc.TABLE_NAME = ? and tc.CONSTRAINT_TYPE <> 'FOREIGN KEY'
                            order by tc.CONSTRAINT_TYPE = 'PRIMARY KEY' desc, tc.CONSTRAINT_NAME
                            """, t.catalog(), t.name())));
                }
                default -> { }
            }
        } catch (SQLException e) {
            // e.g. an older MySQL without CHECK_CONSTRAINTS: fall back to what JDBC can tell
        }
        List<List<Object>> rows = genericConstraints(t);
        if (config.getType() == DbType.SQLITE) rows.addAll(sqliteChecks(t));
        return new TableData(headers, rows);
    }

    /** Fills an empty Definition with "TYPE (columns)", e.g. MySQL primary keys. */
    private static List<List<Object>> withDefinitions(List<List<Object>> rows) {
        List<List<Object>> result = new ArrayList<>();
        for (List<Object> r : rows) {
            String definition = String.valueOf(r.get(3));
            if (definition.isEmpty()) definition = r.get(1) + " (" + r.get(2) + ")";
            result.add(List.of(r.get(0), r.get(1), r.get(2), definition));
        }
        return result;
    }

    private static final Pattern CHECK_START = Pattern.compile(
            "(?:\\bCONSTRAINT\\s+(\"[^\"]+\"|`[^`]+`|\\[[^]]+]|\\w+)\\s+)?\\bCHECK\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** SQLite has no catalog of check constraints, so they are read from the table's CREATE TABLE text. */
    private List<List<Object>> sqliteChecks(TableRef t) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> r : query("select sql from sqlite_master where type = 'table' and name = ?", t.name())) {
            String sql = String.valueOf(r.get(0));
            Matcher m = CHECK_START.matcher(sql);
            int from = 0;
            while (m.find(from)) {
                int open = m.end() - 1;
                int close = matchingParen(sql, open);
                String name = m.group(1) == null ? "CHECK" : m.group(1).replaceAll("^[\"`\\[]|[\"`\\]]$", "");
                rows.add(List.of(name, "CHECK", "", "CHECK " + sql.substring(open, close + 1)));
                from = close + 1;
            }
        }
        return rows;
    }

    /** Index of the ')' matching the '(' at {@code open}, skipping quoted text; the last index if unbalanced. */
    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = SqlLexer.closingQuote(s, i, false) - 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return s.length() - 1;
    }

    /** Primary key and unique indexes, from standard JDBC metadata (used for SQLite and as a fallback). */
    private List<List<Object>> genericConstraints(TableRef t) throws SQLException {
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

    private static final Pattern TRIGGER_TIMING = Pattern.compile("\\b(BEFORE|AFTER|INSTEAD\\s+OF)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIGGER_EVENT = Pattern.compile("\\b(INSERT|UPDATE|DELETE)\\b", Pattern.CASE_INSENSITIVE);

    public TableData listTriggers(TableRef t) throws SQLException {
        List<String> headers = List.of("Trigger", "Timing", "Event", "Level", "Definition");
        List<List<Object>> rows = switch (config.getType()) {
            case POSTGRESQL -> query("""
                    select trigger_name, action_timing, string_agg(event_manipulation, ' OR ' order by event_manipulation),
                           action_orientation, action_statement
                    from information_schema.triggers
                    where event_object_schema = ? and event_object_table = ?
                    group by trigger_name, action_timing, action_orientation, action_statement
                    order by trigger_name
                    """, t.schema(), t.name());
            case MYSQL -> query("""
                    select TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, ACTION_ORIENTATION, ACTION_STATEMENT
                    from information_schema.TRIGGERS
                    where EVENT_OBJECT_SCHEMA = ? and EVENT_OBJECT_TABLE = ?
                    order by TRIGGER_NAME
                    """, t.catalog(), t.name());
            case SQLITE -> {
                List<List<Object>> result = new ArrayList<>();
                for (List<Object> r : query("select name, sql from sqlite_master where type = 'trigger' and tbl_name = ? order by name", t.name())) {
                    String sql = String.valueOf(r.get(1));
                    // timing and event come before "ON <table>" in CREATE TRIGGER
                    int on = sql.toUpperCase().indexOf(" ON ");
                    String head = on > 0 ? sql.substring(0, on) : sql;
                    Matcher timing = TRIGGER_TIMING.matcher(head);
                    Matcher event = TRIGGER_EVENT.matcher(head);
                    result.add(List.of(r.get(0), timing.find() ? timing.group(1).toUpperCase() : "BEFORE",
                            event.find() ? event.group(1).toUpperCase() : "", "ROW", sql));
                }
                yield result;
            }
        };
        return new TableData(headers, rows);
    }

    /** Runs a metadata query with string parameters; nulls become empty strings. */
    private List<List<Object>> query(String sql, String... params) throws SQLException {
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
