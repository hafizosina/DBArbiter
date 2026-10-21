package com.dbadmin.service;

import com.dbadmin.model.DbType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What is being typed at the caret: the word prefix, an optional "qualifier." before it,
 * whether a table name is expected there, and the tables the current statement mentions.
 */
public record CompletionContext(int start, String prefix, String qualifier, boolean tablePosition,
                                List<TableMention> mentions) {

    /** A table referenced in the statement, e.g. "FROM todo.tasks t" -> (todo, tasks, t). */
    public record TableMention(String namespace, String table, String alias) {
    }

    private static final String IDENT = "(?:\"[^\"]+\"|`[^`]+`|[\\w$]+)";
    private static final String NOT_ALIAS = "(?:where|join|on|left|right|inner|outer|full|cross|natural|group|order"
            + "|limit|offset|set|values|using|union|intersect|except|having|window|for|returning|select|from|as)\\b";
    private static final Pattern MENTION = Pattern.compile(
            "\\b(?:from|join|update|into|table)\\s+(" + IDENT + ")(?:\\s*\\.\\s*(" + IDENT + "))?"
                    + "(?:\\s+(?:as\\s+)?(?!" + NOT_ALIAS + ")([\\w$]+))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAUSE = Pattern.compile(
            "\\b(select|from|join|where|on|and|or|group|order|having|set|values|into|update|table|limit|returning)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> TABLE_CLAUSES = Set.of("from", "join", "into", "update", "table");

    /** Returns null when the caret is inside a string, quoted identifier or comment. */
    public static CompletionContext analyze(String text, int caret, DbType type) {
        boolean backslashEscapes = type == DbType.MYSQL;
        boolean dollarQuotes = type == DbType.POSTGRESQL;
        int stmtStart = 0;
        int stmtEnd = text.length();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            int end;
            boolean closed;
            if (c == '-' && next == '-' || c == '#' && type == DbType.MYSQL) {
                end = text.indexOf('\n', i);
                end = end < 0 ? n : end;
                closed = false; // the caret at the end of a comment line is still in the comment
            } else if (c == '/' && next == '*') {
                int close = text.indexOf("*/", i + 2);
                end = close < 0 ? n : close + 2;
                closed = close >= 0;
            } else if (c == '\'' || c == '"' || c == '`') {
                end = SqlSplitter.closingQuote(text, i, backslashEscapes);
                closed = end - 1 > i && text.charAt(end - 1) == c;
            } else if (c == '$' && dollarQuotes && (end = SqlSplitter.dollarQuoteEnd(text, i)) > 0) {
                closed = end < n || text.lastIndexOf('$', end - 2) > i;
            } else {
                if (c == ';') {
                    if (i < caret) {
                        stmtStart = i + 1;
                    } else {
                        stmtEnd = i;
                        break;
                    }
                }
                i++;
                continue;
            }
            if (i < caret && (caret < end || caret == end && !closed)) return null;
            i = end;
        }

        String stmt = text.substring(stmtStart, stmtEnd);
        int pos = caret - stmtStart;
        int wordStart = pos;
        while (wordStart > 0 && isWordChar(stmt.charAt(wordStart - 1))) wordStart--;
        String prefix = stmt.substring(wordStart, pos);

        String qualifier = null;
        int beforeEnd = wordStart;
        if (wordStart > 0 && stmt.charAt(wordStart - 1) == '.') {
            int dot = wordStart - 1;
            int qStart = dot;
            if (qStart > 0 && (stmt.charAt(qStart - 1) == '"' || stmt.charAt(qStart - 1) == '`')) {
                char q = stmt.charAt(qStart - 1);
                int open = stmt.lastIndexOf(q, qStart - 2);
                if (open >= 0) qStart = open;
            } else {
                while (qStart > 0 && isWordChar(stmt.charAt(qStart - 1))) qStart--;
            }
            if (qStart < dot) {
                qualifier = unquote(stmt.substring(qStart, dot));
                beforeEnd = qStart;
            }
        }

        String lastClause = null;
        Matcher clause = CLAUSE.matcher(stmt.substring(0, beforeEnd));
        while (clause.find()) {
            String kw = clause.group(1).toLowerCase();
            if (!kw.equals("and") && !kw.equals("or")) lastClause = kw;
        }
        boolean tablePosition = lastClause != null && TABLE_CLAUSES.contains(lastClause);

        List<TableMention> mentions = new ArrayList<>();
        Matcher m = MENTION.matcher(stmt);
        while (m.find()) {
            // skip the word being typed itself ("from tas|")
            if (m.start(1) <= wordStart && wordStart <= m.end(m.group(2) != null ? 2 : 1)) continue;
            String first = unquote(m.group(1));
            String second = m.group(2) == null ? null : unquote(m.group(2));
            mentions.add(second == null
                    ? new TableMention(null, first, m.group(3))
                    : new TableMention(first, second, m.group(3)));
        }
        return new CompletionContext(stmtStart + wordStart, prefix, qualifier, tablePosition, mentions);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static String unquote(String ident) {
        if (ident.length() >= 2) {
            char q = ident.charAt(0);
            if ((q == '"' || q == '`') && ident.charAt(ident.length() - 1) == q) {
                return ident.substring(1, ident.length() - 1);
            }
        }
        return ident;
    }
}
