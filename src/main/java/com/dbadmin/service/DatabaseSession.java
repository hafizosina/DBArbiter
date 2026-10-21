package com.dbadmin.service;

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
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * One open JDBC connection, read-only unless the connection is configured otherwise. JDBC connections are not thread-safe,
 * so all work for a session runs on its own single worker thread.
 */
public class DatabaseSession implements AutoCloseable {

    private static final Set<String> WANTED_TABLE_TYPES = Set.of("TABLE", "VIEW", "MATERIALIZED VIEW");

    private final ConnectionConfig config;
    private final Connection connection;
    private final ExecutorService worker;
    private volatile Statement running;

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
        return new DatabaseSession(config, openConnection(config));
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

    /** Runs a task on this session's worker thread. */
    public <T> Future<T> submit(Callable<T> task) {
        return worker.submit(task);
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

    public TableData listIndexes(TableRef t) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        if (!t.isView()) {
            try (ResultSet rs = connection.getMetaData().getIndexInfo(t.catalog(), t.schema(), t.name(), false, true)) {
                while (rs.next()) {
                    if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                    rows.add(List.of(
                            nullToEmpty(rs.getString("INDEX_NAME")),
                            nullToEmpty(rs.getString("COLUMN_NAME")),
                            rs.getShort("ORDINAL_POSITION"),
                            rs.getBoolean("NON_UNIQUE") ? "NO" : "YES"));
                }
            }
        }
        return new TableData(List.of("Index", "Column", "Position", "Unique"), rows);
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
        long start = System.nanoTime();
        try (Statement st = params.isEmpty() ? connection.createStatement() : connection.prepareStatement(sql)) {
            running = st;
            st.setMaxRows(maxRows + 1);
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
                    TableData data = readRows(rs, maxRows);
                    boolean truncated = rs.next();
                    return new StatementResult(sql, data, -1, elapsedMillis(start), truncated);
                }
            }
            return new StatementResult(sql, null, st.getUpdateCount(), elapsedMillis(start), false);
        } finally {
            running = null;
        }
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

    private static TableData readRows(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData rsmd = rs.getMetaData();
        int n = rsmd.getColumnCount();
        List<String> columns = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) columns.add(rsmd.getColumnLabel(i));
        List<List<Object>> rows = new ArrayList<>();
        while (rows.size() < maxRows && rs.next()) {
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) row.add(displayValue(rs.getObject(i)));
            rows.add(row);
        }
        return new TableData(columns, rows);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String qualifiedName(TableRef t) throws SQLException {
        String q = connection.getMetaData().getIdentifierQuoteString().trim();
        StringBuilder sb = new StringBuilder();
        // MySQL addresses tables as db.table, PostgreSQL as schema.table
        String prefix = config.getType() == DbType.MYSQL ? t.catalog() : t.schema();
        if (prefix != null) sb.append(quote(prefix, q)).append('.');
        return sb.append(quote(t.name(), q)).toString();
    }

    private static String quote(String identifier, String q) {
        return q + identifier.replace(q, q + q) + q;
    }

    private static Object displayValue(Object v) {
        if (v instanceof byte[] bytes) return "[BLOB " + bytes.length + " bytes]";
        return v;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public void close() {
        worker.shutdownNow();
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
