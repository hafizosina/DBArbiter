package com.dbadmin.model;

/**
 * A result column's type: the database's name for it (e.g. "jsonb"), its {@link java.sql.Types}
 * code and the JDBC display size (maximum characters, 0 or huge when unbounded).
 */
public record ColumnType(String name, int sqlType, int displaySize) {
}
