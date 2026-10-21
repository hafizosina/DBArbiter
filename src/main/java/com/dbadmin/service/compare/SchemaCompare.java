package com.dbadmin.service.compare;

import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.DatabaseSession.ColumnDef;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * Compares the structure of two schemas (e.g. UAT against Prod): which tables and views exist on
 * one side only, and for tables on both sides their columns, indexes, constraints and foreign keys.
 * Names are matched case-insensitively. An index, constraint or foreign key with another name but
 * the same definition counts as the same (generated names often differ between databases).
 */
public final class SchemaCompare {

    public enum Status { SAME, CHANGED, ONLY_SOURCE, ONLY_TARGET }

    /** One compared thing: a column, an index, ... with how it looks on each side ("" if absent). */
    public record Item(String category, String name, Status status, String source, String target) {
    }

    public record TableDiff(String name, String kind, Status status, List<Item> items) {
        public long differences() {
            return items.stream().filter(i -> i.status() != Status.SAME).count();
        }
    }

    /** What is compared about one table, as name -> normalized description. */
    public record TableSnapshot(TableRef table, Map<String, String> columns, Map<String, String> indexes,
                                Map<String, String> constraints, Map<String, String> foreignKeys) {
    }

    private SchemaCompare() {
    }

    /**
     * Reads the structure of the tables and views in a schema: all of them, or only those named in
     * {@code only} (lower case; empty = all). Runs on the session's worker; {@code progress} gets
     * (done, total) after each table.
     */
    public static List<TableSnapshot> snapshot(DatabaseSession session, Namespace ns, Set<String> only,
                                               BiConsumer<Integer, Integer> progress) throws SQLException {
        List<TableRef> tables = session.listTables(ns).stream()
                .filter(t -> only.isEmpty() || only.contains(key(t.name())))
                .toList();
        List<TableSnapshot> result = new ArrayList<>();
        for (TableRef t : tables) {
            Set<String> pk = new HashSet<>(session.primaryKey(t));
            Map<String, String> columns = new LinkedHashMap<>();
            for (ColumnDef c : session.columnDefs(t)) {
                columns.put(key(c.name()), describe(c, pk.contains(c.name())));
            }
            Map<String, String> indexes = new TreeMap<>();
            Map<String, String> constraints = new TreeMap<>();
            Map<String, String> foreignKeys = new TreeMap<>();
            if (!t.isView()) {
                for (List<Object> r : session.listIndexes(t).rows()) {
                    indexes.put(key(str(r.get(0))), ("YES".equals(r.get(2)) ? "UNIQUE " : "") + "(" + r.get(1) + ")");
                }
                TableData cons = session.listConstraints(t);
                for (List<Object> r : cons.rows()) {
                    constraints.put(key(str(r.get(0))), r.get(1) + " " + normalizeSpace(str(r.get(3))));
                }
                for (List<Object> r : session.listForeignKeys(t).rows()) {
                    String target = withoutNamespace(str(r.get(2)), ns.label());
                    foreignKeys.put(key(str(r.get(0))), "(" + r.get(1) + ") → " + target + "(" + r.get(3) + ")"
                            + " ON UPDATE " + r.get(4) + " ON DELETE " + r.get(5));
                }
            }
            result.add(new TableSnapshot(t, columns, indexes, constraints, foreignKeys));
            progress.accept(result.size(), tables.size());
        }
        return result;
    }

    /** "numeric(18,2) NOT NULL DEFAULT 0 PK" */
    static String describe(ColumnDef c, boolean primaryKey) {
        StringBuilder sb = new StringBuilder(typeWithSize(c));
        sb.append(c.nullable() ? " NULL" : " NOT NULL");
        if (c.defaultValue() != null && !c.defaultValue().isBlank()) sb.append(" DEFAULT ").append(normalizeSpace(c.defaultValue()));
        if (primaryKey) sb.append(" PK");
        return sb.toString();
    }

    /** Sizes only where they are part of the type: character, binary and numeric types. */
    private static String typeWithSize(ColumnDef c) {
        String type = c.type() == null ? "?" : c.type().toLowerCase(Locale.ROOT);
        boolean sized = type.contains("char") || type.contains("binary") || type.equals("bit");
        boolean numeric = type.equals("numeric") || type.equals("decimal");
        if (numeric && c.size() > 0 && c.size() < 1000) {
            return type + "(" + c.size() + (c.digits() != null && c.digits() > 0 ? "," + c.digits() : "") + ")";
        }
        if (sized && c.size() > 0 && c.size() < 100_000) return type + "(" + c.size() + ")";
        return type;
    }

    public static List<TableDiff> compare(List<TableSnapshot> source, List<TableSnapshot> target) {
        Map<String, TableSnapshot> src = byName(source);
        Map<String, TableSnapshot> dst = byName(target);
        Set<String> names = new java.util.TreeSet<>(src.keySet());
        names.addAll(dst.keySet());
        List<TableDiff> diffs = new ArrayList<>();
        for (String name : names) {
            TableSnapshot s = src.get(name);
            TableSnapshot d = dst.get(name);
            TableSnapshot any = s != null ? s : d;
            String kind = any.table().type() == null ? "TABLE" : any.table().type().toUpperCase(Locale.ROOT);
            if (s == null || d == null) {
                diffs.add(new TableDiff(any.table().name(), kind, s == null ? Status.ONLY_TARGET : Status.ONLY_SOURCE, List.of()));
                continue;
            }
            List<Item> items = new ArrayList<>();
            items.addAll(compareMaps("Column", s.columns(), d.columns(), false));
            items.addAll(compareMaps("Index", s.indexes(), d.indexes(), true));
            items.addAll(compareMaps("Constraint", s.constraints(), d.constraints(), true));
            items.addAll(compareMaps("Foreign key", s.foreignKeys(), d.foreignKeys(), true));
            boolean same = items.stream().allMatch(i -> i.status() == Status.SAME);
            diffs.add(new TableDiff(s.table().name(), kind, same ? Status.SAME : Status.CHANGED, items));
        }
        return diffs;
    }

    /**
     * Compares two name -> description maps. With {@code matchByDefinition}, entries left over on
     * both sides with an identical description are paired up as the same (e.g. renamed indexes).
     */
    static List<Item> compareMaps(String category, Map<String, String> src, Map<String, String> dst, boolean matchByDefinition) {
        List<Item> items = new ArrayList<>();
        List<String> onlySrc = new ArrayList<>();
        Set<String> onlyDst = new java.util.LinkedHashSet<>();
        for (String name : dst.keySet()) {
            if (!src.containsKey(name)) onlyDst.add(name);
        }
        for (Map.Entry<String, String> e : src.entrySet()) {
            String other = dst.get(e.getKey());
            if (other == null) {
                onlySrc.add(e.getKey());
            } else {
                items.add(new Item(category, e.getKey(), e.getValue().equals(other) ? Status.SAME : Status.CHANGED,
                        e.getValue(), other));
            }
        }
        for (String name : onlySrc) {
            String definition = src.get(name);
            String twin = matchByDefinition
                    ? onlyDst.stream().filter(n -> dst.get(n).equals(definition)).findFirst().orElse(null)
                    : null;
            if (twin != null) {
                onlyDst.remove(twin);
                items.add(new Item(category, name + " = " + twin, Status.SAME, definition, dst.get(twin)));
            } else {
                items.add(new Item(category, name, Status.ONLY_SOURCE, definition, ""));
            }
        }
        for (String name : onlyDst) items.add(new Item(category, name, Status.ONLY_TARGET, "", dst.get(name)));
        return items;
    }

    private static Map<String, TableSnapshot> byName(List<TableSnapshot> snapshots) {
        Map<String, TableSnapshot> map = new LinkedHashMap<>();
        for (TableSnapshot s : snapshots) map.put(key(s.table().name()), s);
        return map;
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String normalizeSpace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /** "brankas_mobile.m_faq" -> "m_faq" when comparing schema brankas_mobile (schemas may be named differently). */
    private static String withoutNamespace(String table, String namespace) {
        String prefix = namespace + ".";
        return table.regionMatches(true, 0, prefix, 0, prefix.length()) ? table.substring(prefix.length()) : table;
    }
}
