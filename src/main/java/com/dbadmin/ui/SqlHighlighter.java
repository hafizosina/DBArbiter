package com.dbadmin.ui;

import com.dbadmin.model.DbType;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
                    + "|(?<DOLLAR>\\$(?<tag>[A-Za-z_]*)\\$(?s:.*?)(?:\\$\\k<tag>\\$|\\z))"
                    + "|(?<STRING>'(?:[^'\\\\]|''|\\\\.)*(?:'|\\z))"
                    + "|(?<IDENT>\"(?:[^\"]|\"\")*(?:\"|\\z)|`[^`]*(?:`|\\z))"
                    + "|(?<NUMBER>\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)"
                    + "|(?<KEYWORD>\\b(?:" + String.join("|", KEYWORDS) + ")\\b)"
                    + "|(?<TYPE>\\b(?:" + String.join("|", TYPES) + ")\\b)"
                    + "|(?<PARAM>\\$\\d+|(?<![:\\w]):[A-Za-z_]\\w*|\\?)",
            Pattern.CASE_INSENSITIVE);
    }

    private static final String[] GROUPS = {"COMMENT", "DOLLAR", "STRING", "IDENT", "NUMBER", "KEYWORD", "TYPE", "PARAM"};

    private SqlHighlighter() {
    }

    static StyleSpans<Collection<String>> highlight(String text, DbType type) {
        Matcher m = (type == DbType.MYSQL ? MYSQL_PATTERN : PATTERN).matcher(text);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int last = 0;
        while (m.find()) {
            spans.add(Collections.emptyList(), m.start() - last);
            spans.add(List.of("sql-" + styleOf(m)), m.end() - m.start());
            last = m.end();
        }
        spans.add(Collections.emptyList(), text.length() - last);
        return spans.create();
    }

    private static String styleOf(Matcher m) {
        for (String g : GROUPS) {
            if (m.group(g) != null) return g.equals("DOLLAR") ? "string" : g.toLowerCase();
        }
        throw new IllegalStateException("unreachable");
    }
}
