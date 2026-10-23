package com.dbadmin.model;

/** One column as JDBC lists it: its table, name, type name and java.sql.Types code. */
public record CatalogColumn(String table, String name, String typeName, int sqlType) {
}
