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

    /**
     * The table to edit, or (source null) why the rows cannot be edited. Both are null for a statement
     * that does not read a table at all ("select 1", SHOW, ...): there is nothing to explain.
     */
    public record Check(Source source, String reason) {
    }

    private static final String IDENT = "(?:\"[^\"]+\"|`[^`]+`|[\\w$]+)";
    private static final Pattern SELECT = Pattern.compile("^\\s*select\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM = Pattern.compile("\\sfrom\\s", Pattern.CASE_INSENSITIVE);
    /** A comma right after the first table: "from a x, b". */
    private static final Pattern SEVERAL_TABLES = Pattern.compile(
            "\\s*from\\s+" + IDENT + "(?:\\s*\\.\\s*" + IDENT + ")?(?:\\s+(?:as\\s+)?[\\w$]+)?\\s*,.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
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
        return Optional.ofNullable(check(sql).source());
    }

    public static Check check(String sql) {
        String s = sql.strip();
        Matcher from = FROM.matcher(s);
        if (!SELECT.matcher(s).find() || !from.find()) return new Check(null, null);
        Matcher notSimple = NOT_SIMPLE.matcher(s);
        if (notSimple.find()) {
            return notEditable("the query uses " + notSimple.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " "));
        }
        if (SEVERAL_TABLES.matcher(s.substring(from.start())).matches()) {
            return notEditable("the query reads from more than one table");
        }
        Matcher m = SIMPLE_SELECT.matcher(s);
        if (!m.matches()) {
            return notEditable("only a plain SELECT from one table (with WHERE, ORDER BY or LIMIT) can be edited");
        }
        String why = whyNotPlainColumns(m.group("list"));
        if (why != null) return notEditable(why);
        String second = m.group("second");
        return new Check(second == null
                ? new Source(null, unquote(m.group("first")))
                : new Source(unquote(m.group("first")), unquote(second)), null);
    }

    private static Check notEditable(String reason) {
        return new Check(null, reason);
    }

    /** "*" (or "t.*"), or a column per item, optionally qualified ("t.name"). */
    private static final Pattern STAR = Pattern.compile("(?:" + IDENT + "\\s*\\.\\s*)?\\*");
    private static final Pattern COLUMN = Pattern.compile("(?:" + IDENT + "\\s*\\.\\s*)?(?<name>" + IDENT + ")");
    private static final Pattern ALIASED = Pattern.compile(
            "(?:" + IDENT + "\\s*\\.\\s*)?" + IDENT + "\\s+(?:as\\s+)?" + IDENT, Pattern.CASE_INSENSITIVE);
    private static final Set<String> NOT_COLUMN = Set.of("current_date", "current_time", "current_timestamp",
            "localtime", "localtimestamp", "current_user", "session_user", "user", "null", "true", "false");

    /**
     * Null when every result column is a table column under its own name, so a column's label says
     * which table column an edit goes to; otherwise why not. Aliases ("name AS email"), expressions
     * ("id + 1"), a column listed twice, or "*" next to other columns would send edits to the wrong
     * column or row.
     */
    private static String whyNotPlainColumns(String list) {
        String[] items = list.split(",", -1);
        if (items.length == 1 && STAR.matcher(items[0].strip()).matches()) return null;
        Set<String> seen = new HashSet<>();
        for (String raw : items) {
            String item = raw.strip();
            if (STAR.matcher(item).matches()) return "* is combined with other columns";
            Matcher c = COLUMN.matcher(item);
            if (!c.matches()) {
                return ALIASED.matcher(item).matches()
                        ? "\"" + shorten(item) + "\" renames a column"
                        : "\"" + shorten(item) + "\" is not a plain column";
            }
            String name = c.group("name");
            // an unquoted name may still be a function or keyword without parentheses (e.g. current_date)
            if (!name.startsWith("\"") && !name.startsWith("`") && NOT_COLUMN.contains(name.toLowerCase(Locale.ROOT))) {
                return "\"" + name + "\" is not a column";
            }
            if (!seen.add(unquote(name).toLowerCase(Locale.ROOT))) return "column \"" + unquote(name) + "\" is listed twice";
        }
        return null;
    }

    private static String shorten(String s) {
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() > 40 ? oneLine.substring(0, 40) + "…" : oneLine;
    }

    private static String unquote(String ident) {
        if (ident.length() >= 2 && (ident.startsWith("\"") && ident.endsWith("\"") || ident.startsWith("`") && ident.endsWith("`"))) {
            return ident.substring(1, ident.length() - 1);
        }
        return ident;
    }
}
