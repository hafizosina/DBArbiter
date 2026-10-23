package com.dbadmin.service.scan;

import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.SchemaChange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The script that replaces found values (see docs/scan-plan.md): per table one UPDATE that replaces every value in
 * each chosen column with nested REPLACE, sets the "also set" columns the table has, and touches only rows that hold
 * an old value (exact, as REPLACE is), so running it again changes nothing. PostgreSQL gets one DO block with the
 * values as variables and a row count per table (RAISE NOTICE, shown by the script tab); MySQL user variables and
 * SQLite plain statements in a transaction, whose row counts the script tab shows per UPDATE. JSON columns are replaced as text and cast back, so the database refuses a result that is not JSON.
 */
public final class ReplaceSql {

    /** One value and what it becomes; {@code name} names its variables ("facebook": old_facebook / new_facebook), or blank. */
    public record Pair(String name, String oldValue, String newValue) {
    }

    /** A column to set in every changed row of a table that has it, to an SQL expression (e.g. modified_by = 'SYSTEM'). */
    public record AlsoSet(String column, String expression) {
    }

    /** A table and the columns of it to replace in; {@code tableColumns}: every column the table has (for Also set). */
    public record Target(TableRef table, List<ScanColumn> columns, Set<String> tableColumns) {
    }

    public record Plan(List<Pair> pairs, List<Target> targets, List<AlsoSet> alsoSet) {
    }

    private ReplaceSql() {
    }

    /** The pairs in the order they are applied: a longer old value first, so one inside it is not replaced first. */
    static List<Pair> ordered(List<Pair> pairs) {
        List<Pair> sorted = new ArrayList<>(pairs);
        sorted.sort(Comparator.comparingInt((Pair p) -> p.oldValue().length()).reversed());
        return sorted;
    }

    public static String script(DbType type, Plan plan) {
        List<Pair> pairs = ordered(plan.pairs().stream().filter(p -> !p.oldValue().isEmpty()).toList());
        if (pairs.isEmpty()) throw new IllegalArgumentException("Nothing to replace.");
        if (plan.targets().isEmpty()) throw new IllegalArgumentException("Tick a column to change.");
        List<String> names = variableNames(pairs, plan.targets());
        StringBuilder sb = new StringBuilder();
        sb.append("-- Replace found values · ").append(plan.targets().size()).append(" table(s)\n");
        for (Pair p : pairs) sb.append("--   ").append(oneLine(p.oldValue())).append("  →  ").append(oneLine(p.newValue())).append('\n');
        switch (type) {
            case POSTGRESQL -> postgres(sb, pairs, names, plan);
            case MYSQL -> mysql(sb, pairs, names, plan);
            case SQLITE -> sqlite(sb, pairs, plan);
        }
        return sb.toString();
    }

    private static void postgres(StringBuilder sb, List<Pair> pairs, List<String> names, Plan plan) {
        DbType type = DbType.POSTGRESQL;
        boolean dollarsInside = pairs.stream().anyMatch(p -> p.oldValue().contains("$$") || p.newValue().contains("$$"));
        String tag = dollarsInside ? "$replace$" : "$$";
        sb.append("DO ").append(tag).append("\nDECLARE\n    -- OLD\n");
        int width = names.stream().mapToInt(String::length).max().orElse(0) + 4;
        for (int i = 0; i < pairs.size(); i++) {
            sb.append("    ").append(pad("old_" + names.get(i), width)).append(" TEXT := ").append(literal(type, pairs.get(i).oldValue())).append(";\n");
        }
        sb.append("    -- NEW\n");
        for (int i = 0; i < pairs.size(); i++) {
            sb.append("    ").append(pad("new_" + names.get(i), width)).append(" TEXT := ").append(literal(type, pairs.get(i).newValue())).append(";\n");
        }
        sb.append("    v_rows INT;\nBEGIN\n");
        for (Target t : plan.targets()) {
            String table = SchemaChange.table(type, t.table());
            sb.append("    UPDATE ").append(table).append('\n');
            List<String> sets = new ArrayList<>();
            List<String> where = new ArrayList<>();
            for (ScanColumn c : t.columns()) {
                String col = type.quote(c.name());
                boolean json = c.kind() == ScanColumn.Kind.JSON;
                String text = json ? "CAST(" + col + " AS text)" : col;
                String replaced = nested(text, names, i -> "old_" + names.get(i), i -> "new_" + names.get(i));
                sets.add(col + " = " + (json ? "CAST(" + replaced + " AS " + jsonType(c) + ")" : replaced));
                for (int i = 0; i < names.size(); i++) where.add("strpos(" + text + ", old_" + names.get(i) + ") > 0");
            }
            alsoSet(type, t, plan.alsoSet(), sets);
            sb.append("    SET ").append(String.join(",\n        ", sets)).append('\n');
            sb.append("    WHERE ").append(String.join("\n       OR ", where)).append(";\n");
            sb.append("    GET DIAGNOSTICS v_rows = ROW_COUNT;\n");
            sb.append("    RAISE NOTICE '").append(t.table().displayName().replace("'", "''")).append(": updated % row(s)', v_rows;\n");
        }
        sb.append("END ").append(tag).append(";\n");
    }

    private static void mysql(StringBuilder sb, List<Pair> pairs, List<String> names, Plan plan) {
        DbType type = DbType.MYSQL;
        sb.append("-- one transaction: if a statement fails, run ROLLBACK; the script tab shows the rows each UPDATE changed\nSTART TRANSACTION;\n");
        for (int i = 0; i < pairs.size(); i++) {
            sb.append("SET @old_").append(names.get(i)).append(" := ").append(literal(type, pairs.get(i).oldValue())).append(";\n");
            sb.append("SET @new_").append(names.get(i)).append(" := ").append(literal(type, pairs.get(i).newValue())).append(";\n");
        }
        for (Target t : plan.targets()) {
            List<String> sets = new ArrayList<>();
            List<String> where = new ArrayList<>();
            for (ScanColumn c : t.columns()) {
                String col = type.quote(c.name());
                boolean json = c.kind() == ScanColumn.Kind.JSON;
                String text = json ? "CAST(" + col + " AS CHAR)" : col;
                String replaced = nested(text, names, i -> "@old_" + names.get(i), i -> "@new_" + names.get(i));
                sets.add(col + " = " + (json ? "CAST(" + replaced + " AS JSON)" : replaced));
                // REPLACE is case-sensitive: so is the test
                for (int i = 0; i < names.size(); i++) {
                    where.add("INSTR(CAST(" + text + " AS BINARY), CAST(@old_" + names.get(i) + " AS BINARY)) > 0");
                }
            }
            alsoSet(type, t, plan.alsoSet(), sets);
            sb.append("\nUPDATE ").append(SchemaChange.table(type, t.table())).append('\n');
            sb.append("SET ").append(String.join(",\n    ", sets)).append('\n');
            sb.append("WHERE ").append(String.join("\n   OR ", where)).append(";\n");
        }
        sb.append("\nCOMMIT;\n");
    }

    private static void sqlite(StringBuilder sb, List<Pair> pairs, Plan plan) {
        DbType type = DbType.SQLITE;
        sb.append("-- one transaction: if a statement fails, run ROLLBACK; the script tab shows the rows each UPDATE changed\nBEGIN;\n");
        List<String> olds = pairs.stream().map(p -> literal(type, p.oldValue())).toList();
        List<String> news = pairs.stream().map(p -> literal(type, p.newValue())).toList();
        for (Target t : plan.targets()) {
            List<String> sets = new ArrayList<>();
            List<String> where = new ArrayList<>();
            for (ScanColumn c : t.columns()) {
                String col = type.quote(c.name());
                sets.add(col + " = " + nested(col, olds, olds::get, news::get));
                for (String old : olds) where.add("instr(" + col + ", " + old + ") > 0");
            }
            alsoSet(type, t, plan.alsoSet(), sets);
            sb.append("\nUPDATE ").append(SchemaChange.table(type, t.table())).append('\n');
            sb.append("SET ").append(String.join(",\n    ", sets)).append('\n');
            sb.append("WHERE ").append(String.join("\n   OR ", where)).append(";\n");
        }
        sb.append("\nCOMMIT;\n");
    }

    /** REPLACE(REPLACE(text, old0, new0), old1, new1) … in the given order. */
    private static String nested(String text, List<String> keys, java.util.function.IntFunction<String> old,
                                 java.util.function.IntFunction<String> neu) {
        String e = text;
        for (int i = 0; i < keys.size(); i++) e = "REPLACE(" + e + ", " + old.apply(i) + ", " + neu.apply(i) + ")";
        return e;
    }

    /** The Also set columns this table has (matched ignoring case); the replaced columns themselves are not set twice. */
    private static void alsoSet(DbType type, Target t, List<AlsoSet> alsoSet, List<String> sets) {
        Set<String> replaced = new LinkedHashSet<>();
        t.columns().forEach(c -> replaced.add(c.name().toLowerCase(Locale.ROOT)));
        for (AlsoSet a : alsoSet) {
            String c = a.column().strip();
            if (c.isEmpty() || a.expression().isBlank()) continue;
            String lower = c.toLowerCase(Locale.ROOT);
            String actual = t.tableColumns().stream().filter(x -> x.equalsIgnoreCase(c)).findFirst().orElse(null);
            if (actual == null || replaced.contains(lower)) continue;
            sets.add(type.quote(actual) + " = " + a.expression().strip());
        }
    }

    /** The tables that do not have an Also set column, as "column: table, table". */
    public static List<String> alsoSetMissing(Plan plan) {
        List<String> notes = new ArrayList<>();
        for (AlsoSet a : plan.alsoSet()) {
            if (a.column().isBlank()) continue;
            List<String> missing = plan.targets().stream()
                    .filter(t -> t.tableColumns().stream().noneMatch(x -> x.equalsIgnoreCase(a.column().strip())))
                    .map(t -> t.table().displayName()).toList();
            if (!missing.isEmpty()) notes.add(a.column().strip() + " is not in " + String.join(", ", missing) + ": not set there");
        }
        return notes;
    }

    /**
     * Variable names from the pairs' names ("Facebook" → facebook) or numbers, unique, and never the name of a column
     * being changed (PL/pgSQL would not know which one is meant).
     */
    static List<String> variableNames(List<Pair> pairs, List<Target> targets) {
        Set<String> columns = new LinkedHashSet<>();
        targets.forEach(t -> t.tableColumns().forEach(c -> columns.add(c.toLowerCase(Locale.ROOT))));
        Set<String> used = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            String base = pairs.get(i).name() == null ? "" : pairs.get(i).name().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
            if (base.isEmpty() || Character.isDigit(base.charAt(0))) base = String.valueOf(i + 1);
            String name = base;
            for (int n = 2; used.contains(name) || columns.contains("old_" + name) || columns.contains("new_" + name); n++) name = base + "_" + n;
            used.add(name);
            names.add(name);
        }
        return names;
    }

    // ---- checks shown before the script is made ----

    /** What to know before running: values inside others, new values that hold an old one. Empty: nothing to say. */
    public static List<String> checks(List<Pair> pairs) {
        List<String> notes = new ArrayList<>();
        for (Pair a : pairs) {
            for (Pair b : pairs) {
                if (a != b && !b.oldValue().isEmpty() && a.oldValue().contains(b.oldValue()) && !a.oldValue().equals(b.oldValue())) {
                    notes.add("\"" + shortly(b.oldValue()) + "\" is part of \"" + shortly(a.oldValue()) + "\": the longer one is replaced first.");
                }
                if (!b.oldValue().isEmpty() && a.newValue().contains(b.oldValue())) {
                    notes.add("The new value \"" + shortly(a.newValue()) + "\" holds \"" + shortly(b.oldValue())
                            + "\": running the script again would change it again.");
                }
            }
            if (a.newValue().isEmpty()) notes.add("\"" + shortly(a.oldValue()) + "\" is replaced by nothing: it is removed.");
        }
        return notes;
    }

    /** {@code text} after every pair is applied, as the script applies them (exact, longer old values first). */
    public static String apply(String text, List<Pair> pairs) {
        if (text == null) return null;
        String s = text;
        for (Pair p : ordered(pairs)) if (!p.oldValue().isEmpty()) s = s.replace(p.oldValue(), p.newValue());
        return s;
    }

    /** The longest a column's value becomes, as an SQL query returning one number (to compare with varchar(n)). */
    public static String longestAfterQuery(DbType type, TableRef table, ScanColumn c, List<Pair> pairs, List<String> params) {
        String col = type.quote(c.name());
        String e = col;
        for (Pair p : ordered(pairs)) {
            if (p.oldValue().isEmpty()) continue;
            e = "REPLACE(" + e + ", ?, ?)";
            params.add(p.oldValue());
            params.add(p.newValue());
        }
        String length = type == DbType.MYSQL ? "CHAR_LENGTH" : "length";
        return "SELECT COALESCE(MAX(" + length + "(" + e + ")), 0) FROM " + SchemaChange.table(type, table);
    }

    /** The declared length of a varchar(n) / char(n) type, else -1. */
    public static int declaredLength(String type) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i).*char[a-z ]*\\((\\d+)\\).*").matcher(type == null ? "" : type);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String jsonType(ScanColumn c) {
        return c.type() != null && c.type().toLowerCase(Locale.ROOT).equals("json") ? "json" : "jsonb";
    }

    private static String literal(DbType type, String s) {
        String v = type.backslashEscapes() ? s.replace("\\", "\\\\") : s;
        return "'" + v.replace("'", "''") + "'";
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private static String oneLine(String s) {
        return s.replace("\r\n", "↵").replace('\n', '↵').replace('\r', '↵');
    }

    private static String shortly(String s) {
        String one = oneLine(s);
        return one.length() > 50 ? "…" + one.substring(one.length() - 49) : one;
    }
}
