package com.dbadmin.service;

import com.dbadmin.model.DbType;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;

import java.sql.SQLException;
import java.util.List;

/**
 * What {@link Metadata} must ask each database in its own way: its catalog queries. Everything the databases
 * share (tables, columns, keys, indexes, foreign keys through JDBC) stays in Metadata; SQL text differences are on
 * {@link DbType}. A fourth database adds one implementation here.
 */
interface Dialect {

    static Dialect of(DbType type) {
        return switch (type) {
            case POSTGRESQL -> new PostgresDialect();
            case MYSQL -> new MySqlDialect();
            case SQLITE -> new SqliteDialect();
        };
    }

    /** The schemas (PostgreSQL), databases (MySQL) or the one "main" (SQLite) to list in the navigator. */
    List<Namespace> namespaces(Metadata m) throws SQLException;

    /** The namespace that unqualified table names resolve to. */
    String defaultNamespace(Metadata m) throws SQLException;

    /** The SQL a view (or materialized view) is defined by, as the database reports it; null if unknown. */
    String viewDefinition(Metadata m, TableRef t) throws SQLException;

    /** The statement(s) that create the table or view, as text to read or copy. */
    String ddl(Metadata m, TableRef t) throws SQLException;

    /**
     * Primary key, unique and check constraints as rows of (name, type, columns, definition). A failure falls back to
     * what plain JDBC can tell ({@link Metadata#genericConstraints}).
     */
    List<List<Object>> constraints(Metadata m, TableRef t) throws SQLException;

    /** The table's columns in order, exactly as defined (see {@link ColumnSpec}), for editing them. */
    List<ColumnSpec> columns(Metadata m, TableRef t) throws SQLException;

    /**
     * Each table's row count as the catalog estimates it (by table name), without reading the tables; a table the
     * catalog has no estimate for is left out.
     */
    java.util.Map<String, Long> estimatedRows(Metadata m, Namespace ns) throws SQLException;

    /** Triggers as rows of (name, timing, event, level, definition). */
    List<List<Object>> triggers(Metadata m, TableRef t) throws SQLException;

    /**
     * The namespace's sequences (MySQL: AUTO_INCREMENT counters, SQLite: sqlite_sequence) as rows of (name, last value,
     * increment, start, table, column); only those of {@code table} when it is not null. Last value is "" before the
     * first use.
     */
    List<List<Object>> sequences(Metadata m, Namespace ns, String table) throws SQLException;

    /** The statement that makes the sequence give {@code next} next. */
    String restartSequence(Metadata m, Namespace ns, String sequence, String table, long next) throws SQLException;
}
