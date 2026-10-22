package com.dbadmin.service;

import com.dbadmin.model.DbType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a script into statements on ';' and on blank lines (as DBeaver does), ignoring both inside
 * quotes, comments and PostgreSQL dollar-quoted bodies, and blank lines inside parentheses (a
 * subquery or CTE laid out with blank lines stays whole). Comment-only fragments are dropped.
 */
public final class SqlSplitter {

    private SqlSplitter() {
    }

    /** One statement: its trimmed text and where it is in the script ([start, end), without the ';'). */
    public record Statement(int start, int end, String sql) {
    }

    /**
     * The statements of a script, trimmed: split at ';' and blank lines outside parentheses, quotes and comments.
     *
     * @param type the database whose syntax applies; null: plain SQL, with only what every database shares (no {@code $$} quotes, {@code #} comments or backslash escapes)
     */
    public static List<String> split(String script, DbType type) {
        return statements(script, type).stream().map(Statement::sql).toList();
    }

    public static List<Statement> statements(String script, DbType type) {
        return segments(script, type).stream()
                .filter(Segment::hasCode)
                .map(s -> trimmed(script, s.start(), s.end()))
                .toList();
    }

    /**
     * A stretch of the script between statement delimiters, [start, end): {@code end} is the ';', the
     * line break before a blank line, or the end of the script. {@code hasCode}: more than comments
     * and whitespace. The segments cover the whole script.
     */
    record Segment(int start, int end, boolean hasCode) {
    }

    static List<Segment> segments(String script, DbType type) {
        List<Segment> segments = new ArrayList<>();
        int segmentStart = 0;
        boolean hasCode = false;
        int depth = 0;
        for (SqlLexer.Token t : SqlLexer.tokens(script, type)) {
            switch (t.kind()) {
                case QUOTED, DOLLAR_QUOTED -> hasCode = true;
                case CODE -> {
                    for (int i = t.start(); i < t.end(); i++) {
                        char c = script.charAt(i);
                        if (c == ';' || c == '\n' && hasCode && depth == 0 && blankLineAfter(script, i)) {
                            segments.add(new Segment(segmentStart, i, hasCode));
                            segmentStart = i + 1;
                            hasCode = false;
                            depth = 0;
                        } else {
                            if (c == '(') depth++;
                            else if (c == ')' && depth > 0) depth--;
                            hasCode |= !Character.isWhitespace(c);
                        }
                    }
                }
                default -> { } // comments
            }
        }
        segments.add(new Segment(segmentStart, script.length(), hasCode));
        return segments;
    }

    /** True if the line after the line break at {@code i} is empty or only whitespace. */
    private static boolean blankLineAfter(String script, int i) {
        int j = i + 1;
        while (j < script.length() && (script.charAt(j) == ' ' || script.charAt(j) == '\t' || script.charAt(j) == '\r')) j++;
        return j < script.length() && script.charAt(j) == '\n';
    }

    private static Statement trimmed(String script, int from, int to) {
        while (from < to && Character.isWhitespace(script.charAt(from))) from++;
        while (to > from && Character.isWhitespace(script.charAt(to - 1))) to--;
        return new Statement(from, to, script.substring(from, to));
    }

    /**
     * The statement the caret is in (or right after, before its ';'), as DBeaver's Ctrl+Enter picks it:
     * on a line where a statement starts, that statement; on a blank line between statements, the one
     * above. Null if the script has none.
     */
    public static Statement statementAt(String script, int caret, DbType type) {
        List<Statement> all = statements(script, type);
        for (Statement s : all) {
            int lineStart = script.lastIndexOf('\n', s.start() - 1) + 1;
            if (caret >= lineStart && caret <= s.end() + 1) return s;
        }
        Statement above = null;
        for (Statement s : all) {
            if (s.end() <= caret) above = s;
        }
        return above != null ? above : all.isEmpty() ? null : all.get(0);
    }

    private static final Pattern QUERY_START = Pattern.compile(
            "(?:\\s|--[^\\n]*\\n?|/\\*(?s:.*?)\\*/)*(select|with|values|table|show|explain|describe|desc|pragma)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITES = Pattern.compile(
            "\\b(insert|update|delete|merge|create|alter|drop|truncate|grant|revoke|call|do|copy|into"
                    + "|lock|nextval|setval)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * True for a statement that only reads and is therefore safe to run again (to fetch more of its
     * rows): it starts with SELECT, WITH, VALUES, SHOW, EXPLAIN ... and mentions none of the words
     * that write (INSERT, UPDATE, DELETE, SELECT ... INTO, FOR UPDATE, nextval, ...). Errs on the safe side.
     */
    public static boolean isReadOnlyQuery(String sql) {
        return QUERY_START.matcher(sql).lookingAt() && !WRITES.matcher(sql).find();
    }

    private static final Pattern ORDER_BY = Pattern.compile("\\border\\s+by\\b", Pattern.CASE_INSENSITIVE);

    /**
     * True if the statement itself is sorted: ORDER BY outside parentheses, so not one that only sorts
     * a subquery or a window. Without it, a query re-run to fetch its next rows may return them in
     * another order.
     */
    public static boolean hasOrderBy(String sql, DbType type) {
        // the code outside parentheses; everything else (quotes, comments, nested parts) becomes a space
        StringBuilder top = new StringBuilder();
        int depth = 0;
        for (SqlLexer.Token t : SqlLexer.tokens(sql, type)) {
            if (t.kind() != SqlLexer.Kind.CODE) {
                top.append(' ');
                continue;
            }
            for (int i = t.start(); i < t.end(); i++) {
                char c = sql.charAt(i);
                if (c == '(') depth++;
                top.append(depth == 0 ? c : ' ');
                if (c == ')' && depth > 0) depth--;
            }
        }
        return ORDER_BY.matcher(top).find();
    }
}
