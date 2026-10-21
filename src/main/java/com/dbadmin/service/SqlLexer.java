package com.dbadmin.service;

import com.dbadmin.model.DbType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts SQL into runs of code and the parts where SQL syntax does not apply: comments, quoted text
 * or identifiers, and PostgreSQL dollar-quoted bodies. Shared by everything that looks at SQL text
 * (splitting, parameters, autocomplete), so they all agree on where code is.
 */
final class SqlLexer {

    enum Kind { CODE, LINE_COMMENT, BLOCK_COMMENT, QUOTED, DOLLAR_QUOTED }

    /**
     * A part of the text, [start, end). {@code closed}: the part ends with its own terminator (a
     * line comment never counts as closed: a caret at its end is still in it).
     */
    record Token(Kind kind, int start, int end, boolean closed) {
    }

    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$[A-Za-z_]*\\$");

    private SqlLexer() {
    }

    /** The tokens covering all of {@code sql}, in order; code characters next to each other form one token. */
    static List<Token> tokens(String sql, DbType type) {
        boolean backslashEscapes = type == DbType.MYSQL;
        boolean dollarQuotes = type == DbType.POSTGRESQL;
        List<Token> tokens = new ArrayList<>();
        int n = sql.length();
        int codeStart = 0;
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            Token token = null;
            if (c == '-' && next == '-' || c == '#' && type == DbType.MYSQL) {
                int end = sql.indexOf('\n', i);
                token = new Token(Kind.LINE_COMMENT, i, end < 0 ? n : end, false);
            } else if (c == '/' && next == '*') {
                int close = sql.indexOf("*/", i + 2);
                token = new Token(Kind.BLOCK_COMMENT, i, close < 0 ? n : close + 2, close >= 0);
            } else if (c == '\'' || c == '"' || c == '`') {
                int end = closingQuote(sql, i, backslashEscapes);
                token = new Token(Kind.QUOTED, i, end, end - 1 > i && sql.charAt(end - 1) == c);
            } else if (c == '$' && dollarQuotes) {
                int end = dollarQuoteEnd(sql, i);
                if (end > 0) token = new Token(Kind.DOLLAR_QUOTED, i, end, end < n || sql.lastIndexOf('$', end - 2) > i);
            }
            if (token == null) {
                i++;
                continue;
            }
            if (codeStart < i) tokens.add(new Token(Kind.CODE, codeStart, i, true));
            tokens.add(token);
            i = token.end();
            codeStart = i;
        }
        if (codeStart < n) tokens.add(new Token(Kind.CODE, codeStart, n, true));
        return tokens;
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
