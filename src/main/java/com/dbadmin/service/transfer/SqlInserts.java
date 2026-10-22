package com.dbadmin.service.transfer;

import com.dbadmin.model.DbType;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Rows as INSERT statements, one per row, to run on another database. */
public final class SqlInserts {

    private SqlInserts() {
    }

    /**
     * @param table      the table's name as written in the statements (qualified and quoted)
     * @param quote      how the database quotes names
     * @param skipExisting rows whose primary key already exists are skipped instead of failing (the table must have one)
     */
    public static RowSink writer(Writer out, DbType type, String table, String quote, boolean skipExisting, List<String> primaryKey) {
        return new RowSink() {
            private List<String> columns;

            @Override
            public void begin(List<String> columns, List<String> types) {
                this.columns = columns;
            }

            @Override
            public void row(List<Object> values) throws IOException {
                List<String> literals = new ArrayList<>(values.size());
                for (Object v : values) literals.add(literal(v, type));
                out.write(statement(type, table, quote, columns, literals, skipExisting ? primaryKey : List.of()));
                out.write('\n');
            }

            @Override
            public void end() throws IOException {
                out.flush();
            }
        };
    }

    /** One INSERT; with a primary key given, a row whose key exists is skipped. */
    static String statement(DbType type, String table, String quote, List<String> columns, List<String> literals,
                            List<String> primaryKey) {
        List<String> names = columns.stream().map(c -> quote + c.replace(quote, quote + quote) + quote).toList();
        String cols = String.join(", ", names);
        String vals = String.join(", ", literals);
        if (primaryKey.isEmpty()) return "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ");";
        return switch (type) {
            case POSTGRESQL -> "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ") ON CONFLICT DO NOTHING;";
            case SQLITE -> "INSERT OR IGNORE INTO " + table + " (" + cols + ") VALUES (" + vals + ");";
            case MYSQL -> {
                List<String> keys = new ArrayList<>();
                for (String k : primaryKey) {
                    int i = columns.indexOf(k);
                    String value = i < 0 ? "NULL" : literals.get(i);
                    keys.add(quote + k + quote + (value.equals("NULL") ? " IS NULL" : " = " + value));
                }
                yield "INSERT INTO " + table + " (" + cols + ") SELECT " + vals + " FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM "
                        + table + " WHERE " + String.join(" AND ", keys) + ");";
            }
        };
    }

    /** A value as an SQL literal of the given database. */
    static String literal(Object v, DbType type) {
        if (v == null) return "NULL";
        if (v instanceof Boolean b) return type.booleanLiteral(b);
        if (v instanceof BigDecimal d) return d.toPlainString();
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) return v.toString();
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            return Double.isFinite(d) ? v.toString() : string(v.toString(), type);
        }
        if (v instanceof byte[] bytes) {
            String hex = HexFormat.of().formatHex(bytes);
            return type.binaryLiteral(hex);
        }
        return string(v.toString(), type);
    }

    private static String string(String s, DbType type) {
        String escaped = s.replace("'", "''");
        // MySQL reads a backslash in a string as an escape
        if (type.backslashEscapes()) escaped = escaped.replace("\\", "\\\\");
        return "'" + escaped + "'";
    }
}
