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

    public static List<String> split(String script, DbType type) {
        boolean backslashEscapes = type == DbType.MYSQL;
        boolean dollarQuotes = type == DbType.POSTGRESQL;
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
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
                if (hasCode) statements.add(current.toString().trim());
                current.setLength(0);
                hasCode = false;
                i++;
                continue;
            } else {
                hasCode |= !Character.isWhitespace(c);
                end = i + 1;
            }
            current.append(script, i, end);
            i = end;
        }
        if (hasCode) statements.add(current.toString().trim());
        return statements;
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
