package com.dbadmin.service;

import com.dbadmin.model.BlobValue;
import com.dbadmin.model.ColumnInfo;
import com.dbadmin.model.ColumnType;
import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.StatementResult;
import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One open JDBC connection, read-only unless the connection is configured otherwise. JDBC connections are not thread-safe,
 * so all work for a session runs on its own single worker thread.
 */
public class DatabaseSession implements AutoCloseable {

    private static final Logger LOG = AppLog.get(DatabaseSession.class);
    private static final Set<String> WANTED_TABLE_TYPES = Set.of("TABLE", "VIEW", "MATERIALIZED VIEW");

    /** A connection idle longer than this is checked (and reopened if dead) before it is used. */
    private static final long IDLE_CHECK_NANOS = 30_000_000_000L;

    private final ConnectionConfig config;
    /** Replaced when a dead connection is reopened; only used on the worker thread (and by close). */
    private volatile Connection connection;
    private final ExecutorService worker;
    private volatile Statement running;
    private volatile Consumer<String> onReconnect;
    // worker-thread state
    private long lastUsedNanos = System.nanoTime();
    /** Set when the current task sent a user statement, which must not be retried automatically. */
    private boolean statementSent;

    private DatabaseSession(ConnectionConfig config, Connection connection) {
        this.config = config;
        this.connection = connection;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "db-" + config.getName());
            t.setDaemon(true);
            return t;
        });
    }

    public static DatabaseSession open(ConnectionConfig config) throws SQLException {
        DatabaseSession session = new DatabaseSession(config, openConnection(config));
        LOG.info("Connected to \"" + config.getName() + "\" (" + config.getType() + (config.isReadOnly() ? ", read-only)" : ", read-write)"));
        return session;
    }

    /** Opens and closes a connection, throwing if anything is wrong. */
    public static String test(ConnectionConfig config) throws SQLException {
        try (Connection c = openConnection(config)) {
            DatabaseMetaData md = c.getMetaData();
            return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
        }
    }

    private static Connection openConnection(ConnectionConfig config) throws SQLException {
        Properties props = new Properties();
        boolean readOnly = config.isReadOnly();
        if (config.getType() == DbType.SQLITE) {
            // SQLITE_OPEN_READONLY or SQLITE_OPEN_READWRITE, never CREATE: a wrong path must not
            // silently create an empty database file
            props.setProperty("open_mode", readOnly ? "1" : "2");
        } else {
            if (readOnly && config.getType() == DbType.POSTGRESQL) {
                // the default ("transaction") only enforces read-only when autocommit is off
                props.setProperty("readOnlyMode", "always");
            }
            if (config.getType() == DbType.POSTGRESQL) {
                // send string parameters untyped, so ":id" = '5' works against a bigint column
                props.setProperty("stringtype", "unspecified");
            }
            if (config.getUsername() != null) props.setProperty("user", config.getUsername());
            if (config.getPassword() != null) props.setProperty("password", config.getPassword());
        }
        DriverManager.setLoginTimeout(10);
        Connection c = DriverManager.getConnection(config.jdbcUrl(), props);
        if (readOnly && config.getType() != DbType.SQLITE) {
            c.setReadOnly(true);
        }
        return c;
    }

    public ConnectionConfig getConfig() {
        return config;
    }

    /** Called (on the worker thread) with the connection name after a dead connection was reopened. */
    public void setOnReconnect(Consumer<String> listener) {
        this.onReconnect = listener;
    }

    /**
     * Runs a task on this session's worker thread. A dead connection is reopened first, or when the
     * task fails because the connection died. Then the task is retried once, unless it had sent a
     * user statement (script, query box): that might already have run, so it is reported instead.
     * Only if reconnecting fails does the caller see an error.
     */
    public <T> Future<T> submit(Callable<T> task) {
        return worker.submit(() -> {
            statementSent = false;
            try {
                if (connection.isClosed() || System.nanoTime() - lastUsedNanos > IDLE_CHECK_NANOS && !isAlive()) {
                    reopen();
                }
                try {
                    return task.call();
                } catch (Exception e) {
                    if (isAlive()) throw e; // an ordinary error, e.g. bad SQL
                    reopen();
                    if (statementSent) {
                        throw new SQLException("The connection was lost and has been re-opened. "
                                + "The statement may not have run; run it again.", e);
                    }
                    return task.call();
                }
            } finally {
                lastUsedNanos = System.nanoTime();
            }
        });
    }

    private boolean isAlive() {
        try {
            return !connection.isClosed() && connection.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    private void reopen() throws SQLException {
        LOG.warning("Connection to \"" + config.getName() + "\" was lost; reopening it");
        try {
            connection.close();
        } catch (SQLException ignored) {
            // already broken
        }
        try {
            connection = openConnection(config);
        } catch (SQLException e) {
            throw new SQLException("Lost the connection to \"" + config.getName() + "\" and could not reconnect: "
                    + e.getMessage(), e.getSQLState(), e);
        }
        Consumer<String> listener = onReconnect;
        if (listener != null) listener.accept(config.getName());
    }

    // ---- structure -------------------------------------------------------

    public List<Namespace> listNamespaces() throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        List<Namespace> result = new ArrayList<>();
        switch (config.getType()) {
            case POSTGRESQL -> {
                String catalog = connection.getCatalog();
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
        DatabaseMetaData md = connection.getMetaData();
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
            case POSTGRESQL -> connection.getSchema();
            case MYSQL -> connection.getCatalog();
            case SQLITE -> "main";
        };
    }

    /** Column names and types, for autocomplete. */
    public List<ColumnInfo> columnInfo(TableRef t) throws SQLException {
        List<ColumnInfo> result = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getColumns(t.catalog(), t.schema(), t.name(), "%")) {
            while (rs.next()) result.add(new ColumnInfo(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")));
        }
        return result;
    }

    public TableData listColumns(TableRef t) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
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

    /** A column as compared between databases. {@code digits} is the scale of numeric types, or null. */
    public record ColumnDef(String name, String type, int size, Integer digits, boolean nullable, String defaultValue) {
    }

    public List<ColumnDef> columnDefs(TableRef t) throws SQLException {
        List<ColumnDef> result = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getColumns(t.catalog(), t.schema(), t.name(), "%")) {
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
        try (ResultSet rs = connection.getMetaData().getColumns(t.catalog(), t.schema(), t.name(), "%")) {
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
        try (ResultSet rs = connection.getMetaData().getPrimaryKeys(t.catalog(), t.schema(), t.name())) {
            while (rs.next()) columns.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(columns.values());
    }

    /** One edited row: its primary key (column -> original value) and the new values (column -> value or null). */
    public record RowUpdate(Map<String, Object> key, Map<String, Object> values) {
    }

    /** The UPDATE for one row, for showing; the real statement binds the values. */
    public String describeUpdate(TableRef t, RowUpdate u) throws SQLException {
        String q = connection.getMetaData().getIdentifierQuoteString().trim();
        StringBuilder sql = new StringBuilder("UPDATE ").append(qualifiedName(t)).append(" SET ");
        int i = 0;
        for (Map.Entry<String, Object> v : u.values().entrySet()) {
            if (i++ > 0) sql.append(", ");
            sql.append(quote(v.getKey(), q)).append(" = ").append(literal(v.getValue()));
        }
        sql.append(" WHERE ");
        i = 0;
        for (Map.Entry<String, Object> k : u.key().entrySet()) {
            if (i++ > 0) sql.append(" AND ");
            sql.append(quote(k.getKey(), q)).append(" = ").append(literal(k.getValue()));
        }
        return sql.toString();
    }

    private static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return "'" + v.toString().replace("'", "''") + "'";
    }

    /**
     * Applies row updates in one transaction. Each must change exactly one row (found by its primary
     * key); otherwise everything is rolled back and an exception says which row. New values are sent
     * as text (the database converts them), booleans as booleans; key values with their original types.
     */
    public int applyUpdates(TableRef t, List<RowUpdate> updates) throws SQLException {
        String q = connection.getMetaData().getIdentifierQuoteString().trim();
        boolean autoCommit = connection.getAutoCommit();
        statementSent = true;
        connection.setAutoCommit(false);
        try {
            int done = 0;
            for (RowUpdate u : updates) {
                List<String> sets = new ArrayList<>();
                u.values().keySet().forEach(c -> sets.add(quote(c, q) + " = ?"));
                List<String> where = new ArrayList<>();
                u.key().keySet().forEach(c -> where.add(quote(c, q) + " = ?"));
                String sql = "UPDATE " + qualifiedName(t) + " SET " + String.join(", ", sets)
                        + " WHERE " + String.join(" AND ", where);
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    int i = 1;
                    for (Object v : u.values().values()) {
                        if (v == null) ps.setNull(i++, java.sql.Types.VARCHAR);
                        else if (v instanceof Boolean b) ps.setBoolean(i++, b); // e.g. MySQL reads 'true' as 0
                        else ps.setString(i++, v.toString());
                    }
                    for (Object k : u.key().values()) ps.setObject(i++, k);
                    int count = ps.executeUpdate();
                    if (count != 1) {
                        throw new SQLException("The row with " + u.key() + " " + (count == 0
                                ? "no longer exists (changed or deleted meanwhile)" : "is not unique (" + count + " rows)")
                                + ". Nothing was saved.");
                    }
                }
                done++;
            }
            connection.commit();
            return done;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
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
                List<List<Object>> rows = query("SHOW CREATE " + what + " " + qualifiedName(t));
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

    /**
     * Rows of a table matching {@code where} (SQL condition, or null for all), ordered by {@code orderBy},
     * at most {@code maxRows} (truncated tells if more exist). Used to compare data between databases.
     * The caller makes sure {@code where} is a plain condition (see SqlSplitter.isReadOnlyQuery).
     */
    public StatementResult fetchOrdered(TableRef t, List<String> orderBy, String where, int maxRows) throws SQLException {
        return execute(orderedSql(t, orderBy, where), List.of(), maxRows);
    }

    /** The query {@link #fetchOrdered} runs: the table's rows, filtered, in the given order. */
    public String orderedSql(TableRef t, List<String> orderBy, String where) throws SQLException {
        String q = connection.getMetaData().getIdentifierQuoteString().trim();
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualifiedName(t));
        if (where != null && !where.isBlank()) sql.append(" WHERE (").append(where).append(")");
        for (int i = 0; i < orderBy.size(); i++) {
            sql.append(i == 0 ? " ORDER BY " : ", ").append(quote(orderBy.get(i), q));
        }
        return sql.toString();
    }

    /** One row per index, with its columns in order. */
    public TableData listIndexes(TableRef t) throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new HashMap<>();
        if (!t.isView()) {
            try (ResultSet rs = connection.getMetaData().getIndexInfo(t.catalog(), t.schema(), t.name(), false, true)) {
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
        DatabaseMetaData md = connection.getMetaData();
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
            try (ResultSet rs = connection.getMetaData().getImportedKeys(t.catalog(), t.schema(), t.name())) {
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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

    // ---- data ------------------------------------------------------------

    public long countRows(TableRef t) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + qualifiedName(t))) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public TableData fetchRows(TableRef t, int limit, long offset) throws SQLException {
        String sql = "SELECT * FROM " + qualifiedName(t) + " LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setLong(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                return readRows(rs, Integer.MAX_VALUE);
            }
        }
    }

    // ---- scripts ---------------------------------------------------------

    /** Executes one statement, fetching at most {@code maxRows} rows if it returns a result set. */
    public StatementResult execute(String sql, int maxRows) throws SQLException {
        return execute(sql, List.of(), maxRows);
    }

    /**
     * Executes one statement with values for its '?' placeholders (a null entry binds SQL NULL).
     * Values are sent as untyped text so the database converts them to the column's type.
     */
    public StatementResult execute(String sql, List<String> params, int maxRows) throws SQLException {
        return execute(sql, params, maxRows, 0);
    }

    /**
     * Like {@link #execute(String, List, int)}, but skips the first {@code skip} rows: re-runs a
     * read-only query to fetch its next chunk. The server is asked for no more than it takes.
     */
    public StatementResult execute(String sql, List<String> params, int maxRows, int skip) throws SQLException {
        long start = System.nanoTime();
        statementSent = true;
        try (Statement st = params.isEmpty() ? connection.createStatement() : connection.prepareStatement(sql)) {
            running = st;
            st.setMaxRows(skip + maxRows + 1);
            boolean hasResultSet;
            if (st instanceof PreparedStatement ps) {
                for (int i = 0; i < params.size(); i++) {
                    if (params.get(i) == null) ps.setNull(i + 1, java.sql.Types.VARCHAR);
                    else ps.setString(i + 1, params.get(i));
                }
                hasResultSet = ps.execute();
            } else {
                hasResultSet = st.execute(sql);
            }
            if (hasResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                    for (int i = 0; i < skip && rs.next(); i++) {
                        // rows of earlier chunks
                    }
                    TableData data = readRows(rs, maxRows);
                    boolean truncated = rs.next();
                    return new StatementResult(sql, params, data, -1, elapsedMillis(start), truncated);
                }
            }
            return new StatementResult(sql, params, null, st.getUpdateCount(), elapsedMillis(start), false);
        } finally {
            running = null;
        }
    }

    /**
     * Runs a query and hands every row to {@code sink} as it is read, without keeping the rows (for exports of
     * any size). Values are whole (binary data as byte[], long text in full). Returns the number of rows.
     */
    public long streamRows(String sql, com.dbadmin.service.transfer.RowSink sink, java.util.function.LongConsumer progress)
            throws SQLException, java.io.IOException {
        statementSent = true;
        boolean autoCommit = connection.getAutoCommit();
        // PostgreSQL only fetches in chunks (instead of all rows at once) inside a transaction
        boolean postgres = config.getType().streamsInTransactionOnly();
        if (postgres && autoCommit) connection.setAutoCommit(false);
        try (Statement st = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            running = st;
            st.setFetchSize(config.getType().streamingFetchSize(1000));
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> columns = new ArrayList<>(n);
                List<String> types = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    columns.add(md.getColumnLabel(i));
                    types.add(md.getColumnTypeName(i));
                }
                sink.begin(columns, types);
                long rows = 0;
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) row.add(wholeValue(rs.getObject(i)));
                    sink.row(row);
                    if (++rows % 1000 == 0) progress.accept(rows);
                }
                sink.end();
                progress.accept(rows);
                return rows;
            }
        } finally {
            running = null;
            if (postgres && autoCommit) {
                connection.rollback(); // only read
                connection.setAutoCommit(true);
            }
        }
    }

    /** A value as exported: binary and long text in full (not cut like {@link #displayValue}). */
    private static Object wholeValue(Object v) throws SQLException {
        if (v instanceof java.sql.SQLXML xml) return xml.getString();
        if (v instanceof java.sql.Clob clob) return clob.getSubString(1, (int) clob.length());
        if (v instanceof java.sql.Blob blob) return blob.getBytes(1, (int) blob.length());
        return v;
    }

    /** The values of {@code columns} in every row, each row's values joined (see {@link #keyText}). */
    public java.util.Set<String> keyValues(TableRef t, List<String> columns) throws SQLException, java.io.IOException {
        String q = identifierQuote();
        String sql = "SELECT " + String.join(", ", columns.stream().map(c -> quote(c, q)).toList()) + " FROM " + qualifiedName(t);
        java.util.Set<String> keys = new java.util.HashSet<>();
        streamRows(sql, new com.dbadmin.service.transfer.RowSink() {
            @Override
            public void begin(List<String> c, List<String> types) {
            }

            @Override
            public void row(List<Object> values) {
                keys.add(keyText(values));
            }

            @Override
            public void end() {
            }
        }, n -> { });
        return keys;
    }

    /** A key as text, comparable between database values and imported text: 1.50 = 1.5, NULL apart from ''. */
    public static String keyText(List<?> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            if (v == null) {
                sb.append("\u0000NULL");
            } else {
                String s = v.toString();
                try {
                    s = new java.math.BigDecimal(s.strip()).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException ignored) {
                    // not a number: compared as written
                }
                sb.append(s);
            }
            sb.append('\u0001');
        }
        return sb.toString();
    }

    /**
     * Inserts rows of text values (null for NULL) into {@code columns}, in one transaction: all rows or none.
     * Each value is converted to its column's type by the database. Returns the number of rows inserted.
     */
    public int insertRows(TableRef t, List<String> columns, List<List<String>> rows, java.util.function.IntConsumer progress)
            throws SQLException {
        String q = identifierQuote();
        Map<String, String> types = new HashMap<>();
        for (ColumnDef c : columnDefs(t)) types.put(c.name(), c.type() == null ? "" : c.type().toLowerCase(Locale.ROOT));
        List<String> placeholders = new ArrayList<>();
        for (String c : columns) placeholders.add(config.getType() == DbType.POSTGRESQL ? "CAST(? AS " + postgresCastType(types.get(c)) + ")" : "?");
        String sql = "INSERT INTO " + qualifiedName(t) + " (" + String.join(", ", columns.stream().map(c -> quote(c, q)).toList())
                + ") VALUES (" + String.join(", ", placeholders) + ")";
        boolean autoCommit = connection.getAutoCommit();
        statementSent = true;
        connection.setAutoCommit(false);
        int done = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            running = ps;
            int batchStart = 0;
            for (List<String> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    String v = row.get(i);
                    String type = types.getOrDefault(columns.get(i), "");
                    // "true"/"false" into a MySQL tinyint(1) / bit or a SQLite boolean would not become 1 / 0
                    if (v != null && config.getType() != DbType.POSTGRESQL && (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false"))
                            && (type.contains("bool") || type.contains("bit") || type.contains("tinyint"))) {
                        v = v.equalsIgnoreCase("true") ? "1" : "0";
                    }
                    if (v == null) ps.setNull(i + 1, java.sql.Types.VARCHAR);
                    else ps.setString(i + 1, v);
                }
                ps.addBatch();
                done++;
                if (done % 500 == 0 || done == rows.size()) {
                    try {
                        ps.executeBatch();
                    } catch (SQLException e) {
                        SQLException cause = e.getNextException() != null ? e.getNextException() : e;
                        throw new SQLException("Rows " + (batchStart + 1) + "–" + done + ": " + cause.getMessage()
                                + "\nNothing was imported.", cause);
                    }
                    batchStart = done;
                    progress.accept(done);
                }
            }
            connection.commit();
            return done;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            running = null;
            connection.setAutoCommit(autoCommit);
        }
    }

    /** A PostgreSQL column type as written in CAST: serial types are their integers, "_int4" is int4[]. */
    public static String postgresCastType(String type) {
        if (type == null || type.isEmpty()) return "text";
        String t = switch (type) {
            case "serial", "serial4" -> "int4";
            case "bigserial", "serial8" -> "int8";
            case "smallserial", "serial2" -> "int2";
            default -> type;
        };
        boolean array = t.startsWith("_");
        if (array) t = t.substring(1);
        if (!t.matches("[a-z_][a-z0-9_]*")) t = "\"" + t.replace("\"", "\"\"") + "\"";
        return array ? t + "[]" : t;
    }

    /** Asks the database to abort the statement currently executing, if any. Safe from any thread. */
    public void cancelRunning() {
        Statement st = running;
        if (st != null) {
            try {
                st.cancel();
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * Runs a query once and keeps it open, to read its rows in chunks ({@link RowCursor#next}); the first
     * {@code skip} rows are passed over. Use a session of its own: PostgreSQL only streams rows inside a
     * transaction (autocommit off here), and MySQL's streaming blocks the connection until the rows are read,
     * so other work on the session would wait or run inside that transaction. Closing the session ends it all.
     */
    public RowCursor openCursor(String sql, List<String> params, int skip) throws SQLException {
        if (config.getType().streamsInTransactionOnly()) connection.setAutoCommit(false); // else every row is read at once
        Statement st = params.isEmpty() ? connection.createStatement() : connection.prepareStatement(sql);
        st.setFetchSize(config.getType().streamingFetchSize(500));
        running = st;
        try {
            ResultSet rs;
            if (st instanceof PreparedStatement ps) {
                for (int i = 0; i < params.size(); i++) {
                    if (params.get(i) == null) ps.setNull(i + 1, java.sql.Types.VARCHAR);
                    else ps.setString(i + 1, params.get(i));
                }
                rs = ps.executeQuery();
            } else {
                rs = st.executeQuery(sql);
            }
            RowCursor cursor = new RowCursor(st, rs);
            for (int i = 0; i < skip && cursor.advance(); i++) {
                // rows already shown
            }
            return cursor;
        } catch (SQLException | RuntimeException e) {
            st.close();
            throw e;
        } finally {
            running = null;
        }
    }

    /** An open query's rows, read in chunks; see {@link #openCursor}. Use on its session's worker thread. */
    public final class RowCursor {
        private final Statement statement;
        private final ResultSet rs;
        private final List<String> columns = new ArrayList<>();
        private final List<ColumnType> types = new ArrayList<>();
        /** The row read ahead (to know whether more are left), or null. */
        private List<Object> ahead;
        private boolean done;

        private RowCursor(Statement statement, ResultSet rs) throws SQLException {
            this.statement = statement;
            this.rs = rs;
            ResultSetMetaData rsmd = rs.getMetaData();
            for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                columns.add(rsmd.getColumnLabel(i));
                types.add(new ColumnType(rsmd.getColumnTypeName(i), rsmd.getColumnType(i), rsmd.getColumnDisplaySize(i)));
            }
            ahead = readRow();
        }

        /** Moves past one row; false at the end. */
        private boolean advance() throws SQLException {
            if (ahead == null) return false;
            ahead = readRow();
            return true;
        }

        private List<Object> readRow() throws SQLException {
            if (done || !rs.next()) {
                done = true;
                return null;
            }
            List<Object> row = new ArrayList<>(columns.size());
            for (int i = 1; i <= columns.size(); i++) row.add(displayValue(rs.getObject(i)));
            return row;
        }

        /** The next rows, at most {@code maxRows}. */
        public TableData next(int maxRows) throws SQLException {
            running = statement;
            try {
                List<List<Object>> rows = new ArrayList<>();
                while (rows.size() < maxRows && ahead != null) {
                    rows.add(ahead);
                    ahead = readRow();
                }
                return new TableData(columns, types, rows);
            } finally {
                running = null;
            }
        }

        /** Rows are left to read. */
        public boolean hasMore() {
            return ahead != null;
        }
    }

    private static TableData readRows(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData rsmd = rs.getMetaData();
        int n = rsmd.getColumnCount();
        List<String> columns = new ArrayList<>(n);
        List<ColumnType> types = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            columns.add(rsmd.getColumnLabel(i));
            types.add(new ColumnType(rsmd.getColumnTypeName(i), rsmd.getColumnType(i), rsmd.getColumnDisplaySize(i)));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (rows.size() < maxRows && rs.next()) {
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) row.add(displayValue(rs.getObject(i)));
            rows.add(row);
        }
        return new TableData(columns, types, rows);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** How the database quotes names: " on PostgreSQL and SQLite, ` on MySQL. */
    public String identifierQuote() throws SQLException {
        return connection.getMetaData().getIdentifierQuoteString().trim();
    }

    /** The table's quoted name with its schema (PostgreSQL) or database (MySQL), as used in SQL. */
    public String qualifiedName(TableRef t) throws SQLException {
        String q = connection.getMetaData().getIdentifierQuoteString().trim();
        StringBuilder sb = new StringBuilder();
        // MySQL addresses tables as db.table, PostgreSQL as schema.table
        String prefix = config.getType().qualifiesByCatalog() ? t.catalog() : t.schema();
        if (prefix != null) sb.append(quote(prefix, q)).append('.');
        return sb.append(quote(t.name(), q)).toString();
    }

    private static String quote(String identifier, String q) {
        return q + identifier.replace(q, q + q) + q;
    }

    /** Converts driver objects that are only readable while the row is current (XML, CLOB, BLOB). */
    private static Object displayValue(Object v) throws SQLException {
        if (v instanceof byte[] bytes) return BlobValue.of(bytes);
        if (v instanceof java.sql.SQLXML xml) return xml.getString();
        if (v instanceof java.sql.Clob clob) return clob.getSubString(1, (int) Math.min(clob.length(), BlobValue.MAX_KEPT));
        if (v instanceof java.sql.Blob blob) {
            return new BlobValue(blob.getBytes(1, (int) Math.min(blob.length(), BlobValue.MAX_KEPT)), blob.length());
        }
        return v;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public void close() {
        LOG.info("Disconnected from \"" + config.getName() + "\"");
        worker.shutdownNow();
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
