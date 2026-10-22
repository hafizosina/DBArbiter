package com.dbadmin.service;

/**
 * A column as the database defines it, exactly enough to write it back in DDL: {@code type} as the database writes
 * it (e.g. "character varying(20)", "enum('a','b')", "int unsigned"); {@code defaultValue} an SQL expression or
 * null; {@code extra} what MySQL keeps beside the type (auto_increment, on update …), or PostgreSQL's identity, as
 * text; {@code collation} only where it is set on the column; {@code generated}: a computed column, whose expression
 * is not kept here.
 */
public record ColumnSpec(String name, String type, boolean nullable, String defaultValue, String comment, String extra,
                         String collation, boolean generated) {

    /** A new column: no extra, collation or comment yet. */
    public static ColumnSpec of(String name, String type, boolean nullable, String defaultValue) {
        return new ColumnSpec(name, type, nullable, defaultValue, "", "", "", false);
    }

    public ColumnSpec withName(String n) {
        return new ColumnSpec(n, type, nullable, defaultValue, comment, extra, collation, generated);
    }

    public ColumnSpec withType(String t) {
        return new ColumnSpec(name, t, nullable, defaultValue, comment, extra, collation, generated);
    }

    public ColumnSpec withNullable(boolean n) {
        return new ColumnSpec(name, type, n, defaultValue, comment, extra, collation, generated);
    }

    public ColumnSpec withDefault(String d) {
        return new ColumnSpec(name, type, nullable, d, comment, extra, collation, generated);
    }

    public ColumnSpec withComment(String c) {
        return new ColumnSpec(name, type, nullable, defaultValue, c, extra, collation, generated);
    }
}
