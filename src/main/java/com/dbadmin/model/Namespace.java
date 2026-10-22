package com.dbadmin.model;

import java.util.Locale;
import java.util.Set;

/**
 * A container of tables: a schema in PostgreSQL, a database (catalog) in MySQL,
 * or the single "main" database in SQLite.
 */
public record Namespace(String catalog, String schema, String label) {

    private static final Set<String> SYSTEM = Set.of(
            "information_schema", "pg_catalog", "mysql", "sys", "performance_schema");

    /**
     * The database's own catalog schemas (PostgreSQL pg_catalog / information_schema; MySQL mysql,
     * sys, performance_schema, information_schema). Hidden in the navigator and search, like DBeaver.
     */
    public boolean isSystem(DbType type) {
        return type != null && type.hasSystemSchemas() && SYSTEM.contains(label.toLowerCase(Locale.ROOT));
    }
}
