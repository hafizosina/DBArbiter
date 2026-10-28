package com.dbadmin.service.compare;

import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.compare.DataCompare.RowStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generate SQL: the verdicts of a data compare as a PostgreSQL script for the target, in one transaction.
 * INSERT copies the source row, UPDATE sets the compared columns that differ, DELETE removes the target row
 * (or sets is_deleted). Ids that differ between databases are not copied: a column the compare joins to
 * another table (mpp.product_id via {@code join m_product_variant mpv on mpv.id = mpp.product_id}) is found
 * again in the target by the joined columns (mpv.product_code), so the joins of the compare are its relations.
 * A table joined to itself (a tier's parent by code) has its new rows inserted parents first. A block at the
 * start stops the script when a row it points to is missing on the target, and a row is only inserted when its
 * key is not there yet, so the script can run again.
 */
public final class MigrationSql {

    /**
     * A target column: its type as the database names it (int8, varchar, timestamp…); generated = serial / identity;
     * primaryKey = part of the table's primary key.
     */
    public record Column(String name, String type, boolean generated, boolean primaryKey) {
        public Column(String name, String type, boolean generated) {
            this(name, type, generated, false);
        }
    }

    /**
     * @param audit      created_date / created_by = now() / user on insert, modified_* NULL; modified_* = now() / user on update
     * @param softDelete DELETE sets is_deleted = true (when the table has it) instead of deleting the row
     * @param withoutPrimaryKey INSERT leaves out the primary key, so the target gives the rows their own (its default);
     *                          a serial / identity key is always left out
     */
    public record Options(boolean audit, String auditUser, boolean softDelete, boolean withoutPrimaryKey) {
        public Options(boolean audit, String auditUser, boolean softDelete) {
            this(audit, auditUser, softDelete, false);
        }
    }

    /** Words written in double quotes when used as a name, as in the hand-written scripts ("name", "key"). */
    private static final Set<String> QUOTED = Set.of("all", "and", "as", "asc", "both", "case", "check", "column", "default",
            "desc", "do", "else", "end", "false", "for", "from", "grant", "group", "having", "in", "is", "key", "limit",
            "name", "not", "null", "offset", "on", "or", "order", "position", "primary", "references", "select", "table",
            "then", "to", "true", "type", "union", "user", "value", "when", "where", "window", "with");
    private static final Pattern SIMPLE = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    private static final Pattern AND = Pattern.compile("\\s+and\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern REF = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*\"?[A-Za-z_]");
    private static final Pattern EQUALS = Pattern.compile(
            "\\(?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*\\)?");
    private static final Set<String> AUDIT_INSERT = Set.of("created_date", "created_by", "modified_date", "modified_by");

    /**
     * How a main-table column pointing to another table is found again in the target: {@code mainColumn} =
     * {@code (SELECT alias.joinColumn FROM <aliases> WHERE <joined columns> = <values> ...)}.
     */
    private record Fk(String mainColumn, String alias, String joinColumn, List<String> aliases, List<String> conditions,
                      List<String> idColumns) {
    }

    /** A row whose new values point to other rows: all its FK columns (inserted) or only those it sets (updated). */
    private record Pointing(Map<String, String> values, Set<String> fkColumns) {
    }

    private final DataCompareReport r;
    private final Map<String, List<Column>> tables;
    private final Options o;
    private final String schema;
    private final String main;
    private final String alias;
    private final Map<String, Column> mainColumns = new LinkedHashMap<>();
    private final Map<String, Integer> sourceIndex = new HashMap<>();
    private final Map<String, KeyJoins.Join> joins = new LinkedHashMap<>();
    /** Per join alias: the other aliases its ON condition names (the main alias included). */
    private final Map<String, Set<String>> refs = new HashMap<>();
    private final Map<String, Fk> fkByColumn = new LinkedHashMap<>();
    private final Set<String> review = new LinkedHashSet<>();

    private MigrationSql(DataCompareReport r, Map<String, List<Column>> tables, Options o) {
        this.r = r;
        this.tables = new HashMap<>();
        tables.forEach((k, v) -> this.tables.put(k.toLowerCase(Locale.ROOT), v));
        this.o = o;
        this.schema = r.target.schema();
        this.main = r.target.table();
        this.alias = r.mainAlias != null && !r.mainAlias.isBlank() ? r.mainAlias : "t";
        for (Column c : table(main)) mainColumns.put(lower(c.name()), c);
        for (int i = 0; i < r.sourceColumns.size(); i++) sourceIndex.put(lower(r.sourceColumns.get(i)), i);
        if (r.joins != null) for (KeyJoins.Join j : r.joins) joins.put(lower(j.alias()), j);
        Set<String> known = new HashSet<>(joins.keySet());
        known.add(lower(alias));
        for (KeyJoins.Join j : joins.values()) {
            Set<String> named = new LinkedHashSet<>();
            Matcher m = REF.matcher(j.on());
            while (m.find()) {
                String a = lower(m.group(1));
                if (known.contains(a) && !a.equals(lower(j.alias()))) named.add(a);
            }
            refs.put(lower(j.alias()), named);
        }
        for (KeyJoins.Join j : joins.values()) {
            if (refs.get(lower(j.alias())).contains(lower(alias))) foreignKeys(j);
        }
    }

    /**
     * The script for the rows with an INSERT, UPDATE or DELETE verdict.
     *
     * @param tables the target's columns of the compared table and of every joined table, by table name
     * @param file   the compare's file name for the header, or null
     */
    public static String generate(DataCompareReport report, Map<String, List<Column>> tables, Options options, String file) {
        return new MigrationSql(report, tables, options).script(file);
    }

    /**
     * The target's columns of the compared table and of every joined table, read from the target's session
     * (on its thread). A table that is not on the target is left out (its columns are then copied as text).
     */
    public static Map<String, List<Column>> targetColumns(DatabaseSession s, DataCompareReport r) throws java.sql.SQLException {
        Map<String, List<Column>> result = new HashMap<>();
        List<String> names = new ArrayList<>(List.of(r.target.table()));
        if (r.joins != null) r.joins.forEach(j -> names.add(j.table()));
        for (String n : names) {
            if (result.containsKey(lower(n))) continue;
            com.dbadmin.model.TableRef t = s.resolveTable(r.target.schema(), n);
            if (t == null) continue;
            Set<String> generated = s.generatedColumns(t);
            Set<String> key = new HashSet<>();
            s.primaryKey(t).forEach(k -> key.add(lower(k)));
            List<Column> columns = new ArrayList<>();
            for (DatabaseSession.ColumnDef c : s.columnDefs(t)) {
                columns.add(new Column(c.name(), c.type(), generated.contains(c.name()), key.contains(lower(c.name()))));
            }
            result.put(lower(n), columns);
        }
        return result;
    }

    /** The table has a boolean is_deleted column: DELETE can set it instead. */
    public static boolean hasSoftDelete(List<Column> columns) {
        return columns.stream().anyMatch(MigrationSql::isDeletedFlag);
    }

    // ---- relations ---------------------------------------------------------

    /**
     * A join on the main table: each {@code alias.x = main.y} of its ON makes main.y a column found by the
     * joined columns of this alias and of the joins hanging off it (mpe → mpt: an etalase found by its tier's code).
     */
    private void foreignKeys(KeyJoins.Join j) {
        String a = lower(j.alias());
        List<String[]> pairs = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        for (String part : AND.split(j.on().strip())) {
            Matcher m = EQUALS.matcher(part.strip());
            if (m.matches() && lower(m.group(1)).equals(a) && lower(m.group(3)).equals(lower(alias))) {
                pairs.add(new String[]{m.group(2), m.group(4)});
            } else if (m.matches() && lower(m.group(3)).equals(a) && lower(m.group(1)).equals(lower(alias))) {
                pairs.add(new String[]{m.group(4), m.group(2)});
            } else if (aliasesIn(part).contains(lower(alias))) {
                review.add("join " + j.alias() + ": its ON condition is not only alias.column = " + alias
                        + ".column; the columns it joins on are copied as they are in the source");
                return;
            } else {
                conditions.add("(" + part.strip() + ")");
            }
        }
        // the joins hanging off this one (they name only aliases already in the lookup)
        List<String> lookup = new ArrayList<>(List.of(a));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (KeyJoins.Join other : joins.values()) {
                String b = lower(other.alias());
                if (!lookup.contains(b) && !refs.get(b).isEmpty() && lookup.containsAll(refs.get(b))) {
                    lookup.add(b);
                    conditions.add("(" + other.on().strip() + ")");
                    grew = true;
                }
            }
        }
        List<String> ids = r.sourceColumns.stream().filter(KeyJoins::isJoinedColumn)
                .filter(c -> lookup.contains(lower(c.substring(0, c.indexOf('.'))))).toList();
        for (String[] p : pairs) {
            if (ids.isEmpty()) {
                review.add(alias + "." + p[1] + ": no column of " + j.table() + " (" + j.alias()
                        + ") is in the compare, so it is copied as it is in the source");
                continue;
            }
            fkByColumn.putIfAbsent(lower(p[1]), new Fk(p[1], a, p[0], lookup, conditions, ids));
        }
    }

    private Set<String> aliasesIn(String sql) {
        Set<String> result = new HashSet<>();
        Matcher m = REF.matcher(sql);
        while (m.find()) result.add(lower(m.group(1)));
        return result;
    }

    /** The lookup of an FK column for a source row; NULL when the source has none. */
    private String lookup(Fk fk, Map<String, String> row) {
        String raw = row.get(lower(fk.mainColumn()));
        Column target = mainColumns.get(lower(fk.mainColumn()));
        if (raw == null) return literal(null, target);
        boolean any = fk.idColumns().stream().anyMatch(c -> row.get(lower(c)) != null);
        if (!any) {
            review.add(fk.mainColumn() + " = " + raw + " points to no row of " + joins.get(fk.alias()).table()
                    + " in the source: copied as it is");
            return literal(raw, target);
        }
        StringBuilder sql = new StringBuilder("(SELECT ").append(fk.alias()).append('.').append(name(fk.joinColumn())).append(" FROM ");
        sql.append(from(fk.aliases())).append(" WHERE ");
        List<String> where = new ArrayList<>(fk.conditions());
        for (String c : fk.idColumns()) where.add(equals(c, row.get(lower(c))));
        for (String a : fk.aliases()) {
            if (hasSoftDelete(table(joins.get(a).table()))) where.add(a + ".is_deleted = false");
        }
        sql.append(String.join(" AND ", where));
        sql.append(" ORDER BY ").append(fk.alias()).append('.').append(name(fk.joinColumn())).append(" LIMIT 1)");
        return sql.toString();
    }

    /** "schema.t1 a, schema.t2 b" */
    private String from(List<String> aliases) {
        List<String> parts = new ArrayList<>();
        for (String a : aliases) parts.add(qualified(joins.get(a).table()) + " " + joins.get(a).alias());
        return String.join(", ", parts);
    }

    /** {@code mpv.product_code = 'AA00647'} for a joined column, {@code mpp.code = 'A01'} for one of the main table. */
    private String equals(String column, String value) {
        String c;
        Column type;
        if (KeyJoins.isJoinedColumn(column)) {
            int dot = column.indexOf('.');
            String a = column.substring(0, dot);
            c = a + "." + name(column.substring(dot + 1));
            type = columnOf(joins.get(lower(a)).table(), column.substring(dot + 1));
        } else {
            c = alias + "." + name(column);
            type = mainColumns.get(lower(column));
        }
        return value == null ? c + " IS NULL" : c + " = " + literal(value, type);
    }

    /**
     * The target row of a key: the main table's key columns, and for joined ones an
     * {@code EXISTS (SELECT 1 FROM <joins> WHERE <their ON> AND <values>)} correlated to the main alias.
     */
    private String keyMatch(List<String> columns, Map<String, String> row) {
        List<String> parts = new ArrayList<>();
        List<String> joined = new ArrayList<>();
        for (String c : columns) {
            if (KeyJoins.isJoinedColumn(c)) joined.add(c);
            else parts.add(equals(c, row.get(lower(c))));
        }
        if (!joined.isEmpty()) {
            Set<String> needed = new LinkedHashSet<>();
            Deque<String> todo = new ArrayDeque<>();
            for (String c : joined) todo.add(lower(c.substring(0, c.indexOf('.'))));
            while (!todo.isEmpty()) {
                String a = todo.poll();
                if (a.equals(lower(alias)) || !joins.containsKey(a) || !needed.add(a)) continue;
                todo.addAll(refs.get(a));
            }
            // in the joins' order, so they read as in the compare
            List<String> ordered = joins.keySet().stream().filter(needed::contains).toList();
            List<String> where = new ArrayList<>();
            for (String a : ordered) where.add("(" + joins.get(a).on().strip() + ")");
            for (String c : joined) where.add(equals(c, row.get(lower(c))));
            parts.add("EXISTS (SELECT 1 FROM " + from(ordered) + " WHERE " + String.join(" AND ", where) + ")");
        }
        return String.join(" AND ", parts);
    }

    // ---- the script ----------------------------------------------------------

    private String script(String file) {
        List<DataCompareReport.Row> inserts = new ArrayList<>();
        List<DataCompareReport.Row> updates = new ArrayList<>();
        List<DataCompareReport.Row> deletes = new ArrayList<>();
        for (DataCompareReport.Row row : r.rows) {
            if (row.verdict == Verdict.INSERT && row.status == RowStatus.ONLY_SOURCE) inserts.add(row);
            else if (row.verdict == Verdict.UPDATE && row.status == RowStatus.CHANGED) updates.add(row);
            else if (row.verdict == Verdict.DELETE && row.status == RowStatus.ONLY_TARGET) deletes.add(row);
        }
        inserts = parentsFirst(inserts);
        boolean soft = o.softDelete() && mainColumns.values().stream().anyMatch(MigrationSql::isDeletedFlag);

        StringBuilder body = new StringBuilder();
        List<Pointing> pointing = new ArrayList<>();
        if (!inserts.isEmpty()) {
            body.append("\n-- INSERT: ").append(inserts.size()).append(inserts.size() == 1 ? " row" : " rows")
                    .append(hasSelfReference() ? ", parents first" : "").append('\n');
            for (DataCompareReport.Row row : inserts) {
                Map<String, String> values = sourceRow(row);
                if (values == null) {
                    review.add(row.key + ": the report has no source row for it (compare again and save with results); not inserted");
                    continue;
                }
                insert(body, row, values);
                pointing.add(new Pointing(values, fkByColumn.keySet()));
            }
        }
        if (!updates.isEmpty()) {
            body.append("\n-- UPDATE: ").append(updates.size()).append(updates.size() == 1 ? " row" : " rows").append('\n');
            for (DataCompareReport.Row row : updates) {
                Map<String, String> values = sourceRow(row);
                if (values == null) values = shown(row, row.source);
                Set<String> setFks = update(body, row, values);
                if (!setFks.isEmpty()) pointing.add(new Pointing(values, setFks));
            }
        }
        if (!deletes.isEmpty()) {
            body.append("\n-- DELETE: ").append(deletes.size()).append(deletes.size() == 1 ? " row" : " rows")
                    .append(soft ? " (is_deleted = true)" : "").append('\n');
            for (DataCompareReport.Row row : deletes) delete(body, row, soft);
        }

        StringBuilder sql = new StringBuilder();
        header(sql, file, inserts.size(), updates.size(), deletes.size(), soft);
        sql.append("\nBEGIN;\n");
        guard(sql, pointing, inserts);
        sql.append(body);
        check(sql, inserts.size(), deletes.size());
        sql.append("\nCOMMIT;\n");
        if (!review.isEmpty()) {
            // the header was written before the rows: put what they found under "review" there
            StringBuilder notes = new StringBuilder();
            for (String n : review) notes.append("--   review: ").append(n.replace("\n", " ")).append('\n');
            int at = sql.indexOf("\nBEGIN;\n");
            sql.insert(at, "\n" + notes.toString().stripTrailing());
        }
        return sql.toString();
    }

    private void header(StringBuilder sql, String file, int inserts, int updates, int deletes, boolean soft) {
        sql.append("-- ").append(main).append(": ").append(r.source.label()).append(" -> ").append(r.target.label())
                .append(", from the data compare").append(file != null ? " " + file : "").append(" (").append(r.created).append(")\n");
        sql.append("-- key: ").append(String.join(", ", r.keyColumns));
        if (!r.comparedColumns.isEmpty()) sql.append("; compared: ").append(String.join(", ", r.comparedColumns));
        if (r.where != null && !r.where.isBlank()) sql.append("; where ").append(r.where.strip());
        sql.append('\n');
        if (r.joins != null) for (KeyJoins.Join j : r.joins) sql.append("--   ").append(KeyJoins.describe(j)).append('\n');
        sql.append("-- verdicts: ").append(inserts).append(" INSERT, ").append(updates).append(" UPDATE, ").append(deletes)
                .append(" DELETE").append(soft && deletes > 0 ? " (sets is_deleted = true)" : "")
                .append("; rows without one or with SKIP are left as they are\n");
        for (Fk fk : fkByColumn.values()) {
            KeyJoins.Join j = joins.get(fk.alias());
            sql.append("-- ").append(fk.mainColumn()).append(" = ").append(j.table()).append(" found by ")
                    .append(String.join(", ", fk.idColumns())).append(" on ").append(r.target.label())
                    .append(isSelf(fk) ? " (parents inserted first)" : "").append('\n');
        }
        List<String> generated = mainColumns.values().stream().filter(Column::generated).map(Column::name)
                .filter(c -> sourceIndex.containsKey(lower(c))).toList();
        if (!generated.isEmpty()) {
            sql.append("-- new ").append(String.join(", ", generated)).append(" from the target's sequence");
            if (generated.stream().anyMatch(c -> r.keyColumns.stream().anyMatch(k -> k.equalsIgnoreCase(c)))) {
                review.add("the key " + String.join(", ", generated) + " is filled by the target's sequence: inserted rows get new values");
            }
            sql.append('\n');
        }
        List<String> leftOut = o.withoutPrimaryKey() ? mainColumns.values().stream()
                .filter(c -> c.primaryKey() && !c.generated()).map(Column::name).filter(c -> sourceIndex.containsKey(lower(c))).toList() : List.of();
        if (!leftOut.isEmpty()) {
            sql.append("-- new ").append(String.join(", ", leftOut)).append(": left out of the INSERTs, the target's default gives it\n");
            if (leftOut.stream().anyMatch(c -> r.keyColumns.stream().anyMatch(k -> k.equalsIgnoreCase(c)))) {
                review.add("the key " + String.join(", ", leftOut) + " is left out of the INSERTs: inserted rows get new values,"
                        + " so running the script again inserts them again");
            }
        }
        if (o.audit()) {
            sql.append("-- audit columns: created_date = now(), created_by = ").append(text(o.auditUser()))
                    .append(", modified_* = NULL; updated rows: modified_date = now(), modified_by = ").append(text(o.auditUser())).append('\n');
        }
        sql.append(r.oneToMany ? "-- a row is only inserted when the target has no row with its key and compared values, so the script can run again\n"
                : "-- a row is only inserted when its key is not on the target yet, so the script can run again\n");
        for (String c : r.sourceColumns) {
            if (!KeyJoins.isJoinedColumn(c) && !mainColumns.containsKey(lower(c))) {
                review.add(c + " is not a column of " + main + " on " + r.target.label() + ": left out");
            }
        }
        if (r.where != null && r.where.contains("\"") && joins.keySet().stream().anyMatch(a -> r.where.toLowerCase(Locale.ROOT).contains("\"" + a + "."))) {
            review.add("the Where filter names a joined column: the statements use it as written, check them");
        }
        if (r.truncated) review.add("the compare read only the first " + r.rowsPerSide + " rows of a side");
    }

    private void insert(StringBuilder sql, DataCompareReport.Row row, Map<String, String> values) {
        List<String> columns = new ArrayList<>();
        List<String> exprs = new ArrayList<>();
        for (String c : r.sourceColumns) {
            if (KeyJoins.isJoinedColumn(c)) continue;
            Column target = mainColumns.get(lower(c));
            if (target == null || target.generated() || o.withoutPrimaryKey() && target.primaryKey()) continue;
            if (isBinary(target)) {
                review.add(c + " (" + target.type() + ") is not copied: binary values are not in the report");
                continue;
            }
            columns.add(name(target.name()));
            exprs.add(insertValue(target, values) + " AS " + name(target.name()));
        }
        sql.append("--").append(row.key).append('\n');
        sql.append("INSERT INTO ").append(qualified(main)).append(" (").append(String.join(", ", columns)).append(")\nSELECT\n  ")
                .append(String.join(",\n  ", exprs)).append('\n');
        List<String> identity = new ArrayList<>(r.keyColumns);
        if (r.oneToMany) identity.addAll(r.comparedColumns);
        sql.append("WHERE NOT EXISTS (SELECT 1 FROM ").append(qualified(main)).append(' ').append(alias).append(" WHERE ")
                .append(whereAnd()).append(keyMatch(identity, values)).append(");\n\n");
    }

    private String insertValue(Column c, Map<String, String> values) {
        String n = lower(c.name());
        if (o.audit() && AUDIT_INSERT.contains(n)) {
            return switch (n) {
                case "created_date" -> "now()";
                case "created_by" -> text(o.auditUser());
                default -> literal(null, c);
            };
        }
        Fk fk = fkByColumn.get(n);
        return fk != null ? lookup(fk, values) : literal(values.get(n), c);
    }

    /** Returns the FK columns it sets (their rows must exist on the target). */
    private Set<String> update(StringBuilder sql, DataCompareReport.Row row, Map<String, String> values) {
        Map<String, String> sets = new LinkedHashMap<>();
        Set<String> fks = new LinkedHashSet<>();
        List<String> changed = new ArrayList<>();
        for (String c : r.comparedColumns) {
            int i = r.shownColumns.indexOf(c);
            if (i < 0 || row.source == null || row.target == null || Objects.equals(row.source.get(i), row.target.get(i))) continue;
            changed.add(c);
            if (KeyJoins.isJoinedColumn(c)) {
                String a = lower(c.substring(0, c.indexOf('.')));
                List<Fk> found = fkByColumn.values().stream().filter(fk -> fk.aliases().contains(a)).toList();
                if (found.isEmpty()) review.add(row.key + ": " + c + " differs, but no join of the main table leads to it; not updated");
                for (Fk fk : found) {
                    sets.put(name(fk.mainColumn()), lookup(fk, values));
                    fks.add(lower(fk.mainColumn()));
                }
            } else {
                Column target = mainColumns.get(lower(c));
                if (target == null || target.generated() || isBinary(target)) continue;
                sets.put(name(target.name()), literal(values.containsKey(lower(c)) ? values.get(lower(c)) : row.source.get(i), target));
            }
        }
        if (sets.isEmpty()) {
            sql.append("--").append(row.key).append(": nothing to update\n\n");
            return fks;
        }
        if (o.audit()) {
            if (mainColumns.containsKey("modified_date")) sets.put("modified_date", "now()");
            if (mainColumns.containsKey("modified_by")) sets.put("modified_by", text(o.auditUser()));
        }
        Map<String, String> key = shown(row, row.target);
        sql.append("--").append(row.key).append(": ").append(String.join(", ", changed)).append('\n');
        sql.append("UPDATE ").append(qualified(main)).append(' ').append(alias).append(" SET\n  ");
        List<String> assignments = new ArrayList<>();
        sets.forEach((c, v) -> assignments.add(c + " = " + v));
        sql.append(String.join(",\n  ", assignments)).append("\nWHERE ").append(whereAnd()).append(keyMatch(r.keyColumns, key)).append(";\n\n");
        return fks;
    }

    private void delete(StringBuilder sql, DataCompareReport.Row row, boolean soft) {
        Map<String, String> key = shown(row, row.target);
        List<String> identity = new ArrayList<>(r.keyColumns);
        if (r.oneToMany) identity.addAll(r.comparedColumns);
        sql.append("--").append(row.key).append('\n');
        if (soft) {
            List<String> sets = new ArrayList<>(List.of("is_deleted = true"));
            if (o.audit() && mainColumns.containsKey("modified_date")) sets.add("modified_date = now()");
            if (o.audit() && mainColumns.containsKey("modified_by")) sets.add("modified_by = " + text(o.auditUser()));
            sql.append("UPDATE ").append(qualified(main)).append(' ').append(alias).append(" SET ").append(String.join(", ", sets));
        } else {
            sql.append("DELETE FROM ").append(qualified(main)).append(' ').append(alias);
        }
        sql.append("\nWHERE ").append(whereAnd()).append(keyMatch(identity, key)).append(";\n\n");
    }

    /**
     * Stops the script before it changes anything when a row the new values point to is not on the target
     * (rows inserted by this script itself are not looked for).
     */
    private void guard(StringBuilder sql, List<Pointing> rows, List<DataCompareReport.Row> inserts) {
        List<String> checks = new ArrayList<>();
        for (Fk fk : fkByColumn.values()) {
            Set<List<String>> tuples = new LinkedHashSet<>();
            Set<List<String>> inserted = new HashSet<>();
            if (isSelf(fk)) {
                for (DataCompareReport.Row row : inserts) {
                    Map<String, String> v = sourceRow(row);
                    if (v != null) inserted.add(fk.idColumns().stream().map(c -> v.get(lower(c.substring(c.indexOf('.') + 1)))).toList());
                }
            }
            for (Pointing p : rows) {
                if (!p.fkColumns().contains(lower(fk.mainColumn()))) continue;
                Map<String, String> v = p.values();
                if (v.get(lower(fk.mainColumn())) == null) continue;
                List<String> tuple = fk.idColumns().stream().map(c -> v.get(lower(c))).toList();
                if (tuple.contains(null) || inserted.contains(tuple)) continue;
                tuples.add(tuple);
            }
            if (tuples.isEmpty()) continue;
            List<String> names = new ArrayList<>();
            for (String c : fk.idColumns()) {
                String n = lower(c.substring(c.indexOf('.') + 1));
                while (names.contains(n)) n = n + "_";
                names.add(n);
            }
            List<String> valueRows = new ArrayList<>();
            for (List<String> t : tuples) {
                List<String> lits = new ArrayList<>();
                for (int i = 0; i < t.size(); i++) lits.add(literal(t.get(i), idColumnType(fk.idColumns().get(i))));
                valueRows.add("(" + String.join(", ", lits) + ")");
            }
            List<String> where = new ArrayList<>(fk.conditions());
            for (int i = 0; i < names.size(); i++) {
                String c = fk.idColumns().get(i);
                int dot = c.indexOf('.');
                where.add(c.substring(0, dot) + "." + name(c.substring(dot + 1)) + " = v." + name(names.get(i)));
            }
            for (String a : fk.aliases()) {
                if (hasSoftDelete(table(joins.get(a).table()))) where.add(a + ".is_deleted = false");
            }
            String shown = names.size() == 1 ? "v." + name(names.get(0)) + "::text"
                    : "concat_ws(' | ', " + String.join(", ", names.stream().map(n -> "v." + name(n)).toList()) + ")";
            // % is RAISE's placeholder: doubled in the names
            String message = (joins.get(fk.alias()).table() + " rows missing on " + r.target.label() + " (" + fk.mainColumn()
                    + " by " + String.join(", ", fk.idColumns()) + ")").replace("%", "%%") + ": %";
            checks.add("  SELECT string_agg(" + shown + ", ', ') INTO missing FROM (VALUES " + String.join(", ", valueRows)
                    + ") AS v(" + String.join(", ", names.stream().map(MigrationSql::name).toList()) + ")\n"
                    + "  WHERE NOT EXISTS (SELECT 1 FROM " + from(fk.aliases()) + " WHERE " + String.join(" AND ", where) + ");\n"
                    + "  IF missing IS NOT NULL THEN\n"
                    + "    RAISE EXCEPTION " + text(message) + ", missing;\n"
                    + "  END IF;\n");
        }
        if (checks.isEmpty()) return;
        sql.append("\n-- stop if a row the new values point to is missing on ").append(r.target.label()).append('\n');
        sql.append("DO $$\nDECLARE missing text;\nBEGIN\n").append(String.join("", checks)).append("END $$;\n");
    }

    private void check(StringBuilder sql, int inserted, int deleted) {
        int expected = r.targetRows + inserted - deleted;
        sql.append("-- check: expect ").append(expected).append(" rows (").append(r.targetRows).append(" at the compare");
        if (inserted > 0) sql.append(", +").append(inserted).append(" inserted");
        if (deleted > 0) sql.append(", -").append(deleted).append(" deleted");
        sql.append(")\n");
        String where = r.where != null && !r.where.isBlank() ? " WHERE (" + r.where.strip() + ")" : "";
        if (r.joins == null || r.joins.isEmpty()) {
            sql.append("SELECT count(*) AS rows FROM ").append(qualified(main)).append(where).append(";\n");
        } else {
            List<String> joinTables = r.joins.stream().map(j -> qualified(j.table())).toList();
            List<String> joined = r.sourceColumns.stream().filter(KeyJoins::isJoinedColumn).toList();
            sql.append("SELECT count(*) AS rows FROM (")
                    .append(KeyJoins.select(qualified(main), alias, r.joins, joinTables, joined, r.where, List.of(), "\""))
                    .append(") c;\n");
        }
    }

    // ---- ordering ----------------------------------------------------------

    /** A join of the main table to itself, found by its own columns only (a tier's parent by code). */
    private boolean isSelf(Fk fk) {
        return fk.aliases().size() == 1 && joins.get(fk.alias()).table().equalsIgnoreCase(main)
                && fk.idColumns().stream().allMatch(c -> mainColumns.containsKey(lower(c.substring(c.indexOf('.') + 1))));
    }

    private boolean hasSelfReference() {
        return fkByColumn.values().stream().anyMatch(this::isSelf);
    }

    /** New rows ordered so a row comes after the new row it points to (its parent); otherwise as listed. */
    private List<DataCompareReport.Row> parentsFirst(List<DataCompareReport.Row> rows) {
        List<Fk> self = fkByColumn.values().stream().filter(this::isSelf).toList();
        if (self.isEmpty() || rows.size() < 2) return rows;
        int n = rows.size();
        List<Map<String, String>> values = rows.stream().map(this::sourceRow).toList();
        List<List<Integer>> children = new ArrayList<>();
        int[] parents = new int[n];
        for (int i = 0; i < n; i++) children.add(new ArrayList<>());
        for (Fk fk : self) {
            Map<List<String>, Integer> own = new HashMap<>();
            for (int i = 0; i < n; i++) {
                Map<String, String> v = values.get(i);
                if (v != null) own.putIfAbsent(fk.idColumns().stream().map(c -> v.get(lower(c.substring(c.indexOf('.') + 1)))).toList(), i);
            }
            for (int i = 0; i < n; i++) {
                Map<String, String> v = values.get(i);
                if (v == null) continue;
                Integer p = own.get(fk.idColumns().stream().map(c -> v.get(lower(c))).toList());
                if (p != null && p != i) {
                    children.get(p).add(i);
                    parents[i]++;
                }
            }
        }
        // Kahn, always taking the first row that is ready, so the listed order is kept where it can be
        List<DataCompareReport.Row> result = new ArrayList<>();
        boolean[] done = new boolean[n];
        java.util.PriorityQueue<Integer> ready = new java.util.PriorityQueue<>();
        for (int i = 0; i < n; i++) if (parents[i] == 0) ready.add(i);
        while (!ready.isEmpty()) {
            int i = ready.poll();
            done[i] = true;
            result.add(rows.get(i));
            for (int c : children.get(i)) if (--parents[c] == 0) ready.add(c);
        }
        for (int i = 0; i < n; i++) if (!done[i]) result.add(rows.get(i)); // a cycle: as listed
        return result;
    }

    // ---- values ------------------------------------------------------------

    /** The whole source row by lower-case column name (joined ones as "alias.column"); null if not in the report. */
    private Map<String, String> sourceRow(DataCompareReport.Row row) {
        if (row.sourceRow == null) return null;
        Map<String, String> v = new HashMap<>();
        for (int i = 0; i < r.sourceColumns.size() && i < row.sourceRow.size(); i++) v.put(lower(r.sourceColumns.get(i)), row.sourceRow.get(i));
        return v;
    }

    private Map<String, String> shown(DataCompareReport.Row row, List<String> side) {
        Map<String, String> v = new HashMap<>();
        if (side == null) return v;
        for (int i = 0; i < r.shownColumns.size() && i < side.size(); i++) v.put(lower(r.shownColumns.get(i)), side.get(i));
        return v;
    }

    private Column idColumnType(String joinedColumn) {
        int dot = joinedColumn.indexOf('.');
        return columnOf(joins.get(lower(joinedColumn.substring(0, dot))).table(), joinedColumn.substring(dot + 1));
    }

    /** A value as an SQL literal of the column's type: numbers and booleans bare, other non-text types cast. */
    static String literal(String value, Column column) {
        String type = column == null || column.type() == null ? "text" : lower(column.type());
        boolean textual = isText(type);
        if (value == null) return textual ? "NULL" : "NULL::" + castType(type);
        if (isNumber(type) && NUMBER.matcher(value).matches()) return value;
        if (type.equals("bool") || type.equals("boolean")) {
            String v = lower(value);
            if (v.equals("true") || v.equals("t")) return "true";
            if (v.equals("false") || v.equals("f")) return "false";
        }
        return textual ? text(value) : text(value) + "::" + castType(type);
    }

    private static String castType(String type) {
        return DatabaseSession.postgresCastType(type);
    }

    private static boolean isText(String type) {
        return type.equals("text") || type.equals("varchar") || type.equals("bpchar") || type.equals("char") || type.equals("name")
                || type.equals("citext") || type.startsWith("character");
    }

    private static boolean isNumber(String type) {
        return switch (type) {
            case "int2", "int4", "int8", "smallint", "integer", "bigint", "numeric", "decimal", "float4", "float8", "real",
                 "double precision", "serial", "bigserial", "smallserial", "serial2", "serial4", "serial8", "oid" -> true;
            default -> false;
        };
    }

    private static boolean isBinary(Column c) {
        return c.type() != null && lower(c.type()).equals("bytea");
    }

    private static boolean isDeletedFlag(Column c) {
        return c.name().equalsIgnoreCase("is_deleted") && c.type() != null && lower(c.type()).startsWith("bool");
    }

    static String text(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** A name as written: bare when lower-case and not a keyword, else in double quotes. */
    static String name(String n) {
        return SIMPLE.matcher(n).matches() && !QUOTED.contains(n) ? n : "\"" + n.replace("\"", "\"\"") + "\"";
    }

    private String qualified(String table) {
        return schema == null || schema.isBlank() ? name(table) : name(schema) + "." + name(table);
    }

    private String whereAnd() {
        return r.where != null && !r.where.isBlank() ? "(" + r.where.strip() + ") AND " : "";
    }

    private List<Column> table(String name) {
        return tables.getOrDefault(lower(name), List.of());
    }

    private Column columnOf(String table, String column) {
        return table(table).stream().filter(c -> c.name().equalsIgnoreCase(column)).findFirst().orElse(null);
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
