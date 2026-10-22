package com.dbadmin.ui;

import com.dbadmin.model.DbType;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regex-based SQL syntax highlighting; style classes are defined in app.css. */
final class SqlHighlighter {

    static final String[] KEYWORDS = {
            "select", "from", "where", "and", "or", "not", "in", "is", "null", "like", "ilike", "between",
            "exists", "as", "on", "join", "inner", "left", "right", "full", "outer", "cross", "natural", "using",
            "group", "by", "order", "having", "limit", "offset", "fetch", "first", "next", "rows", "only",
            "distinct", "all", "any", "union", "intersect", "except", "case", "when", "then", "else", "end",
            "insert", "into", "values", "update", "set", "delete", "returning", "merge", "upsert", "conflict", "do",
            "nothing", "create", "alter", "drop", "truncate", "rename", "add", "column", "table", "view",
            "materialized", "index", "unique", "schema", "database", "sequence", "function", "procedure",
            "trigger", "type", "extension", "if", "replace", "cascade", "restrict", "primary", "foreign", "key",
            "references", "constraint", "check", "default", "with", "recursive", "grant", "revoke", "to",
            "begin", "commit", "rollback", "savepoint", "transaction", "explain", "analyze", "asc", "desc",
            "nulls", "last", "true", "false", "over", "partition", "window", "lateral", "returns", "language",
            "declare", "execute", "call", "show", "use", "describe", "vacuum", "owner", "temporary", "temp",
            "auto_increment", "engine", "comment", "collate", "for", "of", "authorization", "role", "user"
    };

    static final String[] TYPES = {
            "int", "integer", "smallint", "bigint", "tinyint", "mediumint", "serial", "bigserial", "smallserial",
            "decimal", "numeric", "real", "float", "double", "precision", "money", "boolean", "bool", "bit",
            "char", "character", "varchar", "varying", "text", "tinytext", "mediumtext", "longtext", "nchar",
            "nvarchar", "clob", "blob", "bytea", "binary", "varbinary", "longblob", "date", "time", "timestamp",
            "timestamptz", "interval", "year", "datetime", "zone", "uuid", "json", "jsonb", "xml", "enum",
            "inet", "cidr", "array"
    };

    private static final Pattern PATTERN = pattern(false);
    /** MySQL also treats '#' as a line comment; elsewhere it is an operator (e.g. PostgreSQL's #>). */
    private static final Pattern MYSQL_PATTERN = pattern(true);

    private static Pattern pattern(boolean hashComments) {
        return Pattern.compile(
            "(?<COMMENT>--[^\\n]*|" + (hashComments ? "#[^\\n]*|" : "") + "/\\*(?s:.*?)(?:\\*/|\\z))"
                    + "|(?<DOLLAR>(?<open>\\$(?<tag>[A-Za-z_]*)\\$)(?<body>(?s:.*?))(?:\\$\\k<tag>\\$|\\z))"
                    + "|(?<STRING>'(?:[^'\\\\]|''|\\\\.)*(?:'|\\z))"
                    + "|(?<IDENT>\"(?:[^\"]|\"\")*(?:\"|\\z)|`[^`]*(?:`|\\z))"
                    + "|(?<NUMBER>\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)"
                    + "|(?<KEYWORD>\\b(?:" + String.join("|", KEYWORDS) + ")\\b)"
                    + "|(?<TYPE>\\b(?:" + String.join("|", TYPES) + ")\\b)"
                    + "|(?<PARAM>\\$\\d+|(?<![:\\w]):[A-Za-z_]\\w*|\\?)"
                    + "|(?<WORD>\\b[A-Za-z_]\\w*)",
            Pattern.CASE_INSENSITIVE);
    }

    private static final String[] GROUPS = {"COMMENT", "DOLLAR", "STRING", "IDENT", "NUMBER", "KEYWORD", "TYPE", "PARAM", "WORD"};

    private static final Pattern DECLARE_SECTION = Pattern.compile("\\bdeclare\\b(.*?)\\bbegin\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROUTINE_ARGS = Pattern.compile("\\b(?:function|procedure)\\s+[\\w.\"]+\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_NAME = Pattern.compile("^(?:(?:in|out|inout|variadic)\\s+)?([A-Za-z_]\\w*)\\s+\\S",
            Pattern.CASE_INSENSITIVE);

    private SqlHighlighter() {
    }

    static StyleSpans<Collection<String>> highlight(String text, DbType type) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        highlight(text, 0, text.length(), type != null && type.hashComments() ? MYSQL_PATTERN : PATTERN, Set.of(), spans);
        return spans.create();
    }

    /**
     * Styles text[from, to). A dollar-quoted block is almost always a function or DO body, so its delimiters get
     * their own style and the body is highlighted as SQL, with the routine's arguments and DECLAREd names
     * styled as variables.
     */
    private static void highlight(String text, int from, int to, Pattern pattern, Set<String> variables,
                                  StyleSpansBuilder<Collection<String>> spans) {
        Matcher m = pattern.matcher(text).region(from, to);
        int last = from;
        while (m.find()) {
            String style;
            if (m.group("DOLLAR") != null) {
                spans.add(Collections.emptyList(), m.start() - last);
                spans.add(List.of("sql-dollar"), m.end("open") - m.start());
                Set<String> inner = new HashSet<>(variables);
                int statement = Math.max(from, text.lastIndexOf(';', m.start()) + 1);
                inner.addAll(routineArgs(text.substring(statement, m.start())));
                inner.addAll(declared(m.group("body")));
                highlight(text, m.start("body"), m.end("body"), pattern, inner, spans);
                spans.add(List.of("sql-dollar"), m.end() - m.end("body"));
                last = m.end();
                continue;
            } else if (m.group("WORD") != null) {
                if (!variables.contains(m.group().toLowerCase(Locale.ROOT))) continue;
                style = "variable";
            } else {
                style = styleOf(m);
            }
            spans.add(Collections.emptyList(), m.start() - last);
            spans.add(List.of("sql-" + style), m.end() - m.start());
            last = m.end();
        }
        spans.add(Collections.emptyList(), to - last);
    }

    /** Names declared in a PL/pgSQL DECLARE section: the first word of each declaration. */
    private static Set<String> declared(String body) {
        Set<String> names = new HashSet<>();
        Matcher d = DECLARE_SECTION.matcher(body.replaceAll("--[^\\n]*", ""));
        while (d.find()) {
            for (String decl : d.group(1).split(";")) addName(decl, names);
        }
        return names;
    }

    /** Argument names of a CREATE FUNCTION/PROCEDURE signature in the text before its body. */
    private static Set<String> routineArgs(String header) {
        Set<String> names = new HashSet<>();
        Matcher r = ROUTINE_ARGS.matcher(header);
        if (!r.find()) return names;
        int depth = 1;
        int start = r.end();
        for (int i = r.end(); i < header.length() && depth > 0; i++) {
            char c = header.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if ((c == ',' && depth == 1) || depth == 0) {
                addName(header.substring(start, i), names);
                start = i + 1;
            }
        }
        return names;
    }

    /** Adds the leading name of "name type ..." (unnamed arguments, a lone type, are skipped). */
    private static void addName(String declaration, Set<String> names) {
        Matcher n = LEADING_NAME.matcher(declaration.strip());
        if (n.find()) names.add(n.group(1).toLowerCase(Locale.ROOT));
    }

    private static String styleOf(Matcher m) {
        for (String g : GROUPS) {
            if (m.group(g) != null) return g.toLowerCase();
        }
        throw new IllegalStateException("unreachable");
    }
}
