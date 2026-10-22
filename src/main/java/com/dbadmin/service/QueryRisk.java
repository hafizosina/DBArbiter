package com.dbadmin.service;

import com.dbadmin.model.DbType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much one statement can change a database, for the warning levels of a read-write connection:
 * level 1 warns before {@link Risk#DANGER}, level 2 before {@link Risk#CHANGE} too, level 3 before
 * {@link Risk#INSERT} too, level 0 never. Looks only at code (not comments or quoted text) and errs on
 * the side of warning: a statement it does not know is a change.
 */
public final class QueryRisk {

    public enum Risk { NONE, INSERT, CHANGE, DANGER }

    /** {@code reason}: what the statement does, e.g. "deletes every row". */
    public record Assessment(Risk risk, String reason) {
    }

    public static final int NO_WARNINGS = 0;
    public static final int HIGHEST_LEVEL = 3;

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    /** Words that, in a query, mean it writes something: a data-changing WITH part, SELECT ... INTO, sequences. */
    private static final Pattern WRITES_IN_QUERY = Pattern.compile(
            "\\b(insert|update|delete|merge|truncate|drop|create|alter|into|nextval|setval)\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> MAIN_VERBS = List.of("select", "insert", "update", "delete", "merge", "values", "table");
    /** Statements about the session or transaction, not the data. */
    private static final List<String> SESSION = List.of("set", "reset", "begin", "start", "commit", "end", "rollback",
            "abort", "savepoint", "release", "use", "show", "describe", "desc", "pragma");

    private QueryRisk() {
    }

    /** True if a connection at {@code level} asks before running a statement of this risk. */
    public static boolean warns(int level, Risk risk) {
        return switch (risk) {
            case NONE -> false;
            case DANGER -> level >= 1;
            case CHANGE -> level >= 2;
            case INSERT -> level >= 3;
        };
    }

    /**
     * One statement (as {@link SqlSplitter} cuts it).
     *
     * @param type the database whose syntax applies; null: plain SQL
     */
    public static Assessment assess(String sql, DbType type) {
        List<String> top = topLevelWords(sql, type);
        if (top.isEmpty()) return new Assessment(Risk.NONE, "");
        String verb = top.get(0);
        if (verb.equals("with")) verb = top.stream().filter(MAIN_VERBS::contains).findFirst().orElse("with");
        return switch (verb) {
            case "select", "values", "table", "explain" -> WRITES_IN_QUERY.matcher(code(sql, type)).find()
                    ? new Assessment(Risk.CHANGE, "may write (a data-changing part, INTO or a sequence)")
                    : new Assessment(Risk.NONE, "");
            case "insert" -> new Assessment(Risk.INSERT, "adds rows");
            case "delete" -> top.contains("where") ? new Assessment(Risk.CHANGE, "deletes rows")
                    : top.contains("limit") ? new Assessment(Risk.CHANGE, "deletes some rows (no WHERE)")
                    : new Assessment(Risk.DANGER, "deletes every row (no WHERE)");
            case "update" -> top.contains("where") ? new Assessment(Risk.CHANGE, "changes rows")
                    : top.contains("limit") ? new Assessment(Risk.CHANGE, "changes some rows (no WHERE)")
                    : new Assessment(Risk.DANGER, "changes every row (no WHERE)");
            case "drop" -> new Assessment(Risk.DANGER, "drops " + (top.size() > 1 ? "a " + top.get(1) : "an object"));
            case "truncate" -> new Assessment(Risk.DANGER, "empties the table");
            case "alter" -> top.contains("drop") ? new Assessment(Risk.DANGER, "drops part of " + (top.size() > 1 ? "a " + top.get(1) : "an object"))
                    : new Assessment(Risk.CHANGE, "changes the schema");
            case "create", "rename", "comment" -> new Assessment(Risk.CHANGE, "changes the schema");
            case "merge", "replace", "upsert" -> new Assessment(Risk.CHANGE, "changes rows");
            case "grant", "revoke" -> new Assessment(Risk.CHANGE, "changes permissions");
            default -> SESSION.contains(verb) ? new Assessment(Risk.NONE, "")
                    : new Assessment(Risk.CHANGE, "may change data (" + verb.toUpperCase(Locale.ROOT) + ")");
        };
    }

    /** The code of the statement, with comments and quoted text as spaces. */
    private static String code(String sql, DbType type) {
        StringBuilder code = new StringBuilder();
        for (SqlLexer.Token t : SqlLexer.tokens(sql, type)) {
            code.append(t.kind() == SqlLexer.Kind.CODE ? sql.substring(t.start(), t.end()) : " ");
        }
        return code.toString();
    }

    /** The words outside parentheses, comments and quoted text, lower case, in order. */
    private static List<String> topLevelWords(String sql, DbType type) {
        String code = code(sql, type);
        StringBuilder top = new StringBuilder();
        int depth = 0;
        for (char c : code.toCharArray()) {
            if (c == '(') depth++;
            if (depth == 0) top.append(c);
            else top.append(' ');
            if (c == ')' && depth > 0) depth--;
        }
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(top);
        while (m.find()) words.add(m.group().toLowerCase(Locale.ROOT));
        return words;
    }
}
