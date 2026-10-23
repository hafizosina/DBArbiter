package com.dbadmin.service.scan;

import com.dbadmin.model.DbType;

import java.sql.Types;
import java.util.Locale;

/** A column the scan may search: TEXT (char, varchar, text, enum …), JSON (json, jsonb, read as text), or OTHER (not searched). */
public record ScanColumn(String name, String type, Kind kind) {

    public enum Kind { TEXT, JSON, OTHER }

    public boolean searchable() {
        return kind != Kind.OTHER;
    }

    /** From what JDBC lists: the type name and its java.sql.Types code. */
    public static ScanColumn of(DbType db, String name, String typeName, int sqlType) {
        String t = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
        if (t.equals("json") || t.equals("jsonb")) return new ScanColumn(name, typeName, Kind.JSON);
        boolean text = switch (sqlType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB -> true;
            default -> false;
        };
        // by name too: PostgreSQL's text-like types, MySQL enum / set, SQLite's declared types (TEXT affinity), citext
        if (t.contains("char") || t.contains("text") || t.contains("clob") || t.equals("enum") || t.equals("set")) text = true;
        // SQLite: a column declared without a type can hold text
        if (db == DbType.SQLITE && t.isBlank()) text = true;
        if (t.contains("binary") || t.contains("blob") || t.equals("bytea")) text = false;
        return new ScanColumn(name, typeName, text ? Kind.TEXT : Kind.OTHER);
    }
}
