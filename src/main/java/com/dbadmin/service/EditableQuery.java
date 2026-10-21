package com.dbadmin.service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes a query whose rows can be edited: a plain SELECT from one table, optionally with an
 * alias, WHERE, ORDER BY, LIMIT/OFFSET, selecting "*" or plain columns. Joins, grouping, DISTINCT,
 * set operations, column aliases and expressions are not.
 */
public final class EditableQuery {

    /** The table as written in the query: optional schema (or database) and name, unquoted. */
    public record Source(String qualifier, String table) {
    }

    private static final String IDENT = "(?:\"[^\"]+\"|`[^`]+`|[\\w$]+)";
    private static final Pattern SIMPLE_SELECT = Pattern.compile(
            "^\\s*select\\s+(?<list>.+?)\\s+from\\s+(?<first>" + IDENT + ")(?:\\s*\\.\\s*(?<second>" + IDENT + "))?"
                    + "(?:\\s+(?:as\\s+)?[\\w$]+)?"
                    + "(?:\\s+(?:where|order|limit|offset|fetch)\\b.*)?\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NOT_SIMPLE = Pattern.compile(
            "\\b(join|union|intersect|except|group\\s+by|having|distinct|window|into|for\\s+update)\\b",
            Pattern.CASE_INSENSITIVE);

    private EditableQuery() {
    }

    public static Optional<Source> source(String sql) {
        String s = sql.strip();
        int from = s.toLowerCase().indexOf(" from ");
        // a comma after FROM means several tables
        if (from < 0 || NOT_SIMPLE.matcher(s).find() || s.substring(from).matches("(?is)\\s+from\\s+[^\\s,]+(\\s+[\\w$]+)?\\s*,.*")) {
            return Optional.empty();
        }
        Matcher m = SIMPLE_SELECT.matcher(s);
        if (!m.matches() || !isPlainColumnList(m.group("list"))) return Optional.empty();
        String second = m.group("second");
        return Optional.of(second == null
                ? new Source(null, unquote(m.group("first")))
                : new Source(unquote(m.group("first")), unquote(second)));
    }

    /** "*" (or "t.*"), or a column per item, optionally qualified ("t.name"). */
    private static final Pattern STAR = Pattern.compile("(?:" + IDENT + "\\s*\\.\\s*)?\\*");
    private static final Pattern COLUMN = Pattern.compile("(?:" + IDENT + "\\s*\\.\\s*)?(?<name>" + IDENT + ")");

    /**
     * True when every result column is a table column under its own name, so a column's label says
     * which table column an edit goes to. Aliases ("name AS email"), expressions ("id + 1"), a column
     * listed twice, or "*" next to other columns would send edits to the wrong column or row.
     */
    private static boolean isPlainColumnList(String list) {
        String[] items = list.split(",", -1);
        if (items.length == 1 && STAR.matcher(items[0].strip()).matches()) return true;
        Set<String> seen = new HashSet<>();
        for (String item : items) {
            Matcher c = COLUMN.matcher(item.strip());
            if (!c.matches()) return false;
            String name = c.group("name");
            // an unquoted name may still be a function or keyword without parentheses (e.g. current_date)
            if (!name.startsWith("\"") && !name.startsWith("`") && NOT_COLUMN.contains(name.toLowerCase(Locale.ROOT))) {
                return false;
            }
            if (!seen.add(unquote(name).toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static final Set<String> NOT_COLUMN = Set.of("current_date", "current_time", "current_timestamp",
            "localtime", "localtimestamp", "current_user", "session_user", "user", "null", "true", "false");

    private static String unquote(String ident) {
        if (ident.length() >= 2 && (ident.startsWith("\"") && ident.endsWith("\"") || ident.startsWith("`") && ident.endsWith("`"))) {
            return ident.substring(1, ident.length() - 1);
        }
        return ident;
    }
}
