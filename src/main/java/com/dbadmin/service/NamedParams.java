package com.dbadmin.service;

import com.dbadmin.model.DbType;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds ":name" parameters in a statement and rewrites them to JDBC '?' placeholders.
 * Text inside quotes, comments and dollar-quoted bodies is left alone, as are
 * PostgreSQL casts ("::int") and MySQL assignments (":=").
 */
public final class NamedParams {

    /** The rewritten SQL and the parameter name for each '?', in order (a name repeats if used twice). */
    public record ParsedSql(String sql, List<String> names) {
    }

    private NamedParams() {
    }

    public static ParsedSql parse(String sql, DbType type) {
        boolean backslashEscapes = type == DbType.MYSQL;
        boolean dollarQuotes = type == DbType.POSTGRESQL;
        StringBuilder out = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            int end;
            if (c == '-' && next == '-' || c == '#' && type == DbType.MYSQL) {
                end = sql.indexOf('\n', i);
                end = end < 0 ? n : end;
            } else if (c == '/' && next == '*') {
                end = sql.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (c == '\'' || c == '"' || c == '`') {
                end = SqlSplitter.closingQuote(sql, i, backslashEscapes);
            } else if (c == '$' && dollarQuotes && (end = SqlSplitter.dollarQuoteEnd(sql, i)) > 0) {
                // body copied unchanged below
            } else if (c == ':' && next == ':') {
                end = i + 2;
            } else if (c == ':' && isNameStart(next) && (i == 0 || !isNamePart(sql.charAt(i - 1)))) {
                end = i + 2;
                while (end < n && isNamePart(sql.charAt(end))) end++;
                names.add(sql.substring(i + 1, end));
                out.append('?');
                i = end;
                continue;
            } else {
                end = i + 1;
            }
            out.append(sql, i, end);
            i = end;
        }
        return new ParsedSql(out.toString(), names);
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
