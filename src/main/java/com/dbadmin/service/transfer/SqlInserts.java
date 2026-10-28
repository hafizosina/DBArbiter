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
     * @param existsBy   columns that find a row that already exists, which is then skipped instead of failing;
     *                   empty: plain INSERTs. They must be among the exported columns.
     * @param primaryKey the table's primary key: when {@code existsBy} is just that, the database's own
     *                   skip-on-conflict form is used, else a NOT EXISTS check on those columns
     */
    public static RowSink writer(Writer out, DbType type, String table, String quote, List<String> existsBy, List<String> primaryKey) {
        boolean byPrimaryKey = sameColumns(existsBy, primaryKey);
        return new RowSink() {
            private List<String> columns;

            @Override
            public void begin(List<String> columns, List<String> types) throws IOException {
                for (String k : existsBy) {
                    if (columns.stream().noneMatch(c -> c.equalsIgnoreCase(k))) {
                        throw new IOException("The column " + k + ", which finds rows that already exist, is not in the exported rows.");
                    }
                }
                this.columns = columns;
            }

            @Override
            public void row(List<Object> values) throws IOException {
                List<String> literals = new ArrayList<>(values.size());
                for (Object v : values) literals.add(literal(v, type));
                out.write(byPrimaryKey || existsBy.isEmpty()
                        ? statement(type, table, quote, columns, literals, existsBy)
                        : statementIfAbsent(type, table, quote, columns, literals, existsBy));
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

    /**
     * One INSERT that adds the row only when no row has its values in the {@code existsBy} columns
     * (INSERT ... SELECT ... WHERE NOT EXISTS, which every database runs and which needs no unique constraint).
     */
    static String statementIfAbsent(DbType type, String table, String quote, List<String> columns, List<String> literals,
                                    List<String> existsBy) {
        String cols = String.join(", ", columns.stream().map(c -> quoted(c, quote)).toList());
        List<String> keys = new ArrayList<>();
        for (String k : existsBy) {
            int i = indexOfIgnoreCase(columns, k);
            String name = quoted(columns.get(i), quote);
            String value = literals.get(i);
            keys.add(name + (value.equals("NULL") ? " IS NULL" : " = " + value));
        }
        return "INSERT INTO " + table + " (" + cols + ") SELECT " + String.join(", ", literals)
                + (type == DbType.MYSQL ? " FROM DUAL" : "") + " WHERE NOT EXISTS (SELECT 1 FROM " + table
                + " WHERE " + String.join(" AND ", keys) + ");";
    }

    private static String quoted(String name, String quote) {
        return quote + name.replace(quote, quote + quote) + quote;
    }

    private static int indexOfIgnoreCase(List<String> names, String name) {
        for (int i = 0; i < names.size(); i++) if (names.get(i).equalsIgnoreCase(name)) return i;
        return -1;
    }

    /** The same columns, in any order and case. */
    static boolean sameColumns(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        return a.stream().allMatch(x -> b.stream().anyMatch(x::equalsIgnoreCase));
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
