package com.dbadmin.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes a query whose rows can be edited: a plain SELECT from one table, optionally with an
 * alias, WHERE, ORDER BY, LIMIT/OFFSET. Joins, grouping, DISTINCT and set operations are not.
 */
public final class EditableQuery {

    /** The table as written in the query: optional schema (or database) and name, unquoted. */
    public record Source(String qualifier, String table) {
    }

    private static final String IDENT = "(?:\"[^\"]+\"|`[^`]+`|[\\w$]+)";
    private static final Pattern SIMPLE_SELECT = Pattern.compile(
            "^\\s*select\\s+.+?\\s+from\\s+(?<first>" + IDENT + ")(?:\\s*\\.\\s*(?<second>" + IDENT + "))?"
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
        if (!m.matches()) return Optional.empty();
        String second = m.group("second");
        return Optional.of(second == null
                ? new Source(null, unquote(m.group("first")))
                : new Source(unquote(m.group("first")), unquote(second)));
    }

    private static String unquote(String ident) {
        if (ident.length() >= 2 && (ident.startsWith("\"") && ident.endsWith("\"") || ident.startsWith("`") && ident.endsWith("`"))) {
            return ident.substring(1, ident.length() - 1);
        }
        return ident;
    }
}
