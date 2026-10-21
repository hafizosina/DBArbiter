package com.dbadmin.model;

/**
 * A container of tables: a schema in PostgreSQL, a database (catalog) in MySQL,
 * or the single "main" database in SQLite.
 */
public record Namespace(String catalog, String schema, String label) {
}
