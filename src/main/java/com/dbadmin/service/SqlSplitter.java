package com.dbadmin.service;

import com.dbadmin.model.DbType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a script into statements on ';', ignoring semicolons inside quotes,
 * comments and PostgreSQL dollar-quoted bodies. Comment-only fragments are dropped.
 */
public final class SqlSplitter {

    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$[A-Za-z_]*\\$");

    private SqlSplitter() {
    }

    /** One statement: its trimmed text and where it is in the script ([start, end), without the ';'). */
    public record Statement(int start, int end, String sql) {
    }

    public static List<String> split(String script, DbType type) {
        return statements(script, type).stream().map(Statement::sql).toList();
    }

    public static List<Statement> statements(String script, DbType type) {
        boolean backslashEscapes = type == DbType.MYSQL;
        boolean dollarQuotes = type == DbType.POSTGRESQL;
        List<Statement> statements = new ArrayList<>();
        int segmentStart = 0;
        boolean hasCode = false;
        int n = script.length();
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            char next = i + 1 < n ? script.charAt(i + 1) : '\0';
            int end;
            if (c == '-' && next == '-' || c == '#' && type == DbType.MYSQL) {
                end = script.indexOf('\n', i);
                end = end < 0 ? n : end;
            } else if (c == '/' && next == '*') {
                end = script.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (c == '\'' || c == '"' || c == '`') {
                end = closingQuote(script, i, backslashEscapes);
                hasCode = true;
            } else if (c == '$' && dollarQuotes && (end = dollarQuoteEnd(script, i)) > 0) {
                hasCode = true;
            } else if (c == ';') {
                if (hasCode) statements.add(trimmed(script, segmentStart, i));
                segmentStart = i + 1;
                hasCode = false;
                i++;
                continue;
            } else {
                hasCode |= !Character.isWhitespace(c);
                end = i + 1;
            }
            i = end;
        }
        if (hasCode) statements.add(trimmed(script, segmentStart, n));
        return statements;
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
            "\\b(insert|update|delete|merge|upsert|replace|create|alter|drop|truncate|grant|revoke|call|do|copy|into"
                    + "|lock|nextval|setval)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * True for a statement that only reads and is therefore safe to run again (to fetch more of its
     * rows): it starts with SELECT, WITH, VALUES, SHOW, EXPLAIN ... and mentions none of the words
     * that write (INSERT, UPDATE, DELETE, SELECT ... INTO, FOR UPDATE, nextval, ...). Errs on the safe side.
     */
    public static boolean isReadOnlyQuery(String sql) {
        return QUERY_START.matcher(sql).lookingAt() && !WRITES.matcher(sql).find();
    }

    /** Index just past the quote that closes the one at {@code start}; doubled quotes are escapes. */
    static int closingQuote(String s, int start, boolean backslashEscapes) {
        char q = s.charAt(start);
        int j = start + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\' && backslashEscapes && q != '`') {
                j += 2;
            } else if (c == q) {
                if (j + 1 < s.length() && s.charAt(j + 1) == q) j += 2;
                else return j + 1;
            } else {
                j++;
            }
        }
        return s.length();
    }

    /** Index just past a $tag$...$tag$ body starting at {@code start}, or -1 if none starts there. */
    static int dollarQuoteEnd(String s, int start) {
        if (start > 0 && (Character.isLetterOrDigit(s.charAt(start - 1)) || s.charAt(start - 1) == '_')) return -1;
        Matcher m = DOLLAR_TAG.matcher(s).region(start, s.length());
        if (!m.lookingAt()) return -1;
        String tag = m.group();
        int close = s.indexOf(tag, m.end());
        return close < 0 ? s.length() : close + tag.length();
    }
}
