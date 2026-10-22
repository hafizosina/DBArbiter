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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * One open JDBC connection, read-only unless the connection is configured otherwise. JDBC connections are not thread-safe,
 * so all work for a session runs on its own single worker thread.
 */
public class DatabaseSession implements AutoCloseable {

    private static final Logger LOG = AppLog.get(DatabaseSession.class);

    /** A connection idle longer than this is checked (and reopened if dead) before it is used. */
    private static final long IDLE_CHECK_NANOS = 30_000_000_000L;

    private final ConnectionConfig config;
    /** Replaced when a dead connection is reopened; only used on the worker thread (and by close). */
    private volatile Connection connection;
    private final ExecutorService worker;
    /** Reads the structure (schemas, tables, columns, keys, views, DDL, …) through this session's connection. */
    private final Metadata metadata;
    private volatile Statement running;
    private volatile Consumer<String> onReconnect;
    // worker-thread state
    private long lastUsedNanos = System.nanoTime();
    /** Set when the current task sent a user statement, which must not be retried automatically. */
    private boolean statementSent;

    private DatabaseSession(ConnectionConfig config, Connection connection) {
        this.config = config;
        this.connection = connection;
        this.metadata = new Metadata(this); // after config: it reads the database type from the session
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

    /** The current connection (it changes when the session reconnects). */
    Connection connection() {
        return connection;
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

    // ---- structure: read by Metadata ------------------------------------

    /** A column as compared between databases. {@code digits} is the scale of numeric types, or null. */
    public record ColumnDef(String name, String type, int size, Integer digits, boolean nullable, String defaultValue) {
    }

    public List<Namespace> listNamespaces() throws SQLException {
        return metadata.listNamespaces();
    }

    public List<TableRef> listTables(Namespace ns) throws SQLException {
        return metadata.listTables(ns);
    }

    public String defaultNamespace() throws SQLException {
        return metadata.defaultNamespace();
    }

    public List<ColumnInfo> columnInfo(TableRef t) throws SQLException {
        return metadata.columnInfo(t);
    }

    public TableData listColumns(TableRef t) throws SQLException {
        return metadata.listColumns(t);
    }

    public List<ColumnDef> columnDefs(TableRef t) throws SQLException {
        return metadata.columnDefs(t);
    }

    public Set<String> generatedColumns(TableRef t) throws SQLException {
        return metadata.generatedColumns(t);
    }

    public List<String> primaryKey(TableRef t) throws SQLException {
        return metadata.primaryKey(t);
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

    public String viewDefinition(TableRef t) throws SQLException {
        return metadata.viewDefinition(t);
    }

    public String ddl(TableRef t) throws SQLException {
        return metadata.ddl(t);
    }

    public TableRef resolveTable(String qualifier, String name) throws SQLException {
        return metadata.resolveTable(qualifier, name);
    }

    /** See {@link Metadata#serialType}. */
    static String serialType(String type, String defaultExpr, String ownedSequence) {
        return Metadata.serialType(type, defaultExpr, ownedSequence);
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

    public TableData listIndexes(TableRef t) throws SQLException {
        return metadata.listIndexes(t);
    }

    public TableData listConstraints(TableRef t) throws SQLException {
        return metadata.listConstraints(t);
    }

    public TableData listForeignKeys(TableRef t) throws SQLException {
        return metadata.listForeignKeys(t);
    }

    public TableData listTriggers(TableRef t) throws SQLException {
        return metadata.listTriggers(t);
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
