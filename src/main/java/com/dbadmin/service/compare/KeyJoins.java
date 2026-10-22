package com.dbadmin.service.compare;

import com.dbadmin.service.SqlSplitter;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Joins that give a data compare its key from other tables: the compared table stays one main table
 * (e.g. m_product_image mpi), and a key column may come from a joined table (mpt.code via
 * mpi.etalase_id → mpe.id, mpe.tier_3 → mpt.id) when the main table's own ids differ between
 * databases. Joined columns appear as "alias.column" and can be a key or shown, never compared.
 * The same joins later tell generated INSERT / UPDATE statements how to find the row in the target.
 */
public final class KeyJoins {

    /** One join: {@code [LEFT] JOIN table alias ON on}; the table is in the same schema as the main table. */
    public record Join(boolean left, String table, String alias, String on) {
    }

    private static final Pattern ALIAS = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private KeyJoins() {
    }

    /** "mpt.code": a column of a joined table, as listed in the column picker. */
    public static boolean isJoinedColumn(String column) {
        return column.indexOf('.') > 0;
    }

    /** What is wrong with the joins, for the user; null when they can be used. */
    public static String problem(String mainAlias, List<Join> joins) {
        if (mainAlias == null || !ALIAS.matcher(mainAlias).matches()) {
            return "The main table needs an alias (letters, digits, _), e.g. mpi.";
        }
        Set<String> aliases = new HashSet<>();
        aliases.add(mainAlias.toLowerCase(Locale.ROOT));
        for (Join j : joins) {
            if (j.table() == null || j.table().isBlank()) return "Pick the table of every join.";
            if (j.alias() == null || !ALIAS.matcher(j.alias()).matches()) {
                return "The join on " + j.table() + " needs an alias (letters, digits, _).";
            }
            if (!aliases.add(j.alias().toLowerCase(Locale.ROOT))) return "The alias " + j.alias() + " is used twice.";
            if (j.on() == null || j.on().isBlank()) return "The join on " + j.table() + " needs an ON condition, e.g. "
                    + j.alias() + ".id = " + mainAlias + "." + j.table() + "_id.";
            String probe = "select * from t where (" + j.on() + ")";
            if (SqlSplitter.split(probe, null).size() != 1 || !SqlSplitter.isReadOnlyQuery(probe)) {
                return "The ON condition of " + j.alias() + " must be one read-only condition.";
            }
        }
        return null;
    }

    /**
     * The main table's rows with the joined columns it needs, filtered and in key order:
     * {@code SELECT * FROM (SELECT mpi.*, mpt."code" AS "mpt.code" FROM main mpi JOIN ... ) q WHERE (where) ORDER BY keys}.
     * The Where filter sees the main table's columns by name and joined ones as "alias.column".
     *
     * @param mainTable  the main table's qualified, quoted name on this side
     * @param joinTables each join's qualified, quoted table name on this side, in the joins' order
     */
    public static String select(String mainTable, String mainAlias, List<Join> joins, List<String> joinTables,
                                List<String> joinedColumns, String where, List<String> keys, String quote) {
        StringBuilder sql = new StringBuilder("SELECT * FROM (\nSELECT ").append(mainAlias).append(".*");
        for (String c : joinedColumns) {
            int dot = c.indexOf('.');
            sql.append(", ").append(c, 0, dot).append('.').append(quoted(c.substring(dot + 1), quote))
                    .append(" AS ").append(quoted(c, quote));
        }
        sql.append("\nFROM ").append(mainTable).append(' ').append(mainAlias);
        for (int i = 0; i < joins.size(); i++) {
            Join j = joins.get(i);
            sql.append('\n').append(j.left() ? "LEFT JOIN " : "JOIN ").append(joinTables.get(i)).append(' ').append(j.alias())
                    .append(" ON (").append(j.on()).append(')');
        }
        sql.append("\n) q");
        if (where != null && !where.isBlank()) sql.append(" WHERE (").append(where).append(")");
        for (int i = 0; i < keys.size(); i++) sql.append(i == 0 ? " ORDER BY " : ", ").append(quoted(keys.get(i), quote));
        return sql.toString();
    }

    /** "join brankas_mobile... mpe on mpe.id = mpi.etalase_id" as written in reports. */
    public static String describe(Join j) {
        return (j.left() ? "left join " : "join ") + j.table() + " " + j.alias() + " on " + j.on();
    }

    private static String quoted(String name, String quote) {
        return quote + name.replace(quote, quote + quote) + quote;
    }
}
