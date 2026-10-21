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
        StringBuilder out = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
        for (SqlLexer.Token t : SqlLexer.tokens(sql, type)) {
            if (t.kind() != SqlLexer.Kind.CODE) {
                out.append(sql, t.start(), t.end());
                continue;
            }
            int i = t.start();
            while (i < t.end()) {
                char c = sql.charAt(i);
                char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
                if (c == ':' && next == ':') {
                    out.append("::");
                    i += 2;
                } else if (c == ':' && isNameStart(next) && (i == 0 || !isNamePart(sql.charAt(i - 1)))) {
                    int end = i + 2;
                    while (end < t.end() && isNamePart(sql.charAt(end))) end++;
                    names.add(sql.substring(i + 1, end));
                    out.append('?');
                    i = end;
                } else {
                    out.append(c);
                    i++;
                }
            }
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
