package com.dbadmin.service.compare;

import com.dbadmin.model.BlobValue;
import com.dbadmin.model.TableData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the rows of one table in two databases, matched by one or more key columns, on the
 * chosen columns only. Every row gets a status (match, changed, only on one side, duplicate key).
 * "Show" columns are carried into the report but never affect a status. A key that matches several
 * rows on a side (a duplicate key) is not silently collapsed: its rows are paired up by identical
 * values and the key is reported.
 */
public final class DataCompare {

    public enum RowStatus { MATCH, CHANGED, ONLY_SOURCE, ONLY_TARGET, DUPLICATE_KEY }

    /**
     * A row of the report. {@code sourceValues} / {@code targetValues} hold the row's values for the
     * result's {@code shownColumns} (null when the row is not on that side; a duplicate key shows
     * its first row). {@code details}: for a changed row "column: source → target" per difference.
     */
    public record RowDiff(RowStatus status, String key, String details, List<Object> sourceValues,
                          List<Object> targetValues) {
    }

    /**
     * Row counts: {@code same} and {@code changed} are rows present on both sides; the only-in counts
     * are rows; {@code duplicateKeys} counts keys that match more than one row on either side.
     */
    public record Result(int sourceRows, int targetRows, int same, int changed, int onlySource, int onlyTarget,
                         int duplicateKeys, List<String> comparedColumns, List<String> shownColumns,
                         List<RowDiff> rows) {
    }

    private DataCompare() {
    }

    /**
     * @param showColumns columns only shown in the report (their differences do not count)
     * @param maxRows     rows listed in the report at most; the counts always cover everything
     */
    public static Result compare(TableData source, TableData target, List<String> keyColumns,
                                 List<String> compareColumns, List<String> showColumns, int maxRows) {
        for (String k : keyColumns) {
            if (index(source, k) < 0 || index(target, k) < 0) {
                throw new IllegalArgumentException("Key column \"" + k + "\" is not in both tables");
            }
        }
        List<String> compared = compareColumns.stream()
                .filter(c -> index(source, c) >= 0 && index(target, c) >= 0)
                .toList();
        int[] srcCols = compared.stream().mapToInt(c -> index(source, c)).toArray();
        int[] dstCols = compared.stream().mapToInt(c -> index(target, c)).toArray();
        List<String> shown = new ArrayList<>(keyColumns);
        shown.addAll(compared);
        for (String c : showColumns) {
            boolean onBoth = index(source, c) >= 0 && index(target, c) >= 0;
            if (onBoth && shown.stream().noneMatch(x -> x.equalsIgnoreCase(c))) shown.add(c);
        }
        int[] srcShown = shown.stream().mapToInt(c -> index(source, c)).toArray();
        int[] dstShown = shown.stream().mapToInt(c -> index(target, c)).toArray();

        Map<String, List<List<Object>>> src = byKey(source, keyColumns);
        Map<String, List<List<Object>>> dst = byKey(target, keyColumns);
        List<RowDiff> diffs = new ArrayList<>();
        int same = 0;
        int changed = 0;
        int onlySource = 0;
        int onlyTarget = 0;
        int duplicateKeys = 0;
        for (Map.Entry<String, List<List<Object>>> e : src.entrySet()) {
            List<List<Object>> srcRows = e.getValue();
            List<List<Object>> dstRows = dst.remove(e.getKey());
            if (dstRows == null) {
                onlySource += srcRows.size();
                if (srcRows.size() > 1) duplicateKeys++;
                add(diffs, maxRows, new RowDiff(RowStatus.ONLY_SOURCE, e.getKey(),
                        srcRows.size() > 1 ? srcRows.size() + " rows with this key" : "", pick(srcRows.get(0), srcShown), null));
                continue;
            }
            if (srcRows.size() == 1 && dstRows.size() == 1) {
                List<String> changes = new ArrayList<>();
                for (int i = 0; i < compared.size(); i++) {
                    Object a = srcRows.get(0).get(srcCols[i]);
                    Object b = dstRows.get(0).get(dstCols[i]);
                    if (!normalize(a).equals(normalize(b))) changes.add(compared.get(i) + ": " + show(a) + " → " + show(b));
                }
                if (changes.isEmpty()) {
                    same++;
                    add(diffs, maxRows, new RowDiff(RowStatus.MATCH, e.getKey(), "",
                            pick(srcRows.get(0), srcShown), pick(dstRows.get(0), dstShown)));
                } else {
                    changed++;
                    add(diffs, maxRows, new RowDiff(RowStatus.CHANGED, e.getKey(), String.join("; ", changes),
                            pick(srcRows.get(0), srcShown), pick(dstRows.get(0), dstShown)));
                }
                continue;
            }
            // duplicate key: pair up rows with identical compared values; what is left over differs
            duplicateKeys++;
            List<String> unmatched = new ArrayList<>();
            for (List<Object> row : dstRows) unmatched.add(signature(row, dstCols));
            int matched = 0;
            for (List<Object> row : srcRows) {
                if (unmatched.remove(signature(row, srcCols))) matched++;
            }
            int leftSource = srcRows.size() - matched;
            int leftTarget = unmatched.size();
            same += matched;
            changed += Math.min(leftSource, leftTarget);
            onlySource += Math.max(0, leftSource - leftTarget);
            onlyTarget += Math.max(0, leftTarget - leftSource);
            add(diffs, maxRows, new RowDiff(RowStatus.DUPLICATE_KEY, e.getKey(), srcRows.size() + "× in source, "
                    + dstRows.size() + "× in target; " + (leftSource == 0 && leftTarget == 0 ? "all rows match"
                    : "unmatched: " + leftSource + " in source, " + leftTarget + " in target"),
                    pick(srcRows.get(0), srcShown), pick(dstRows.get(0), dstShown)));
        }
        for (Map.Entry<String, List<List<Object>>> e : dst.entrySet()) {
            onlyTarget += e.getValue().size();
            if (e.getValue().size() > 1) duplicateKeys++;
            add(diffs, maxRows, new RowDiff(RowStatus.ONLY_TARGET, e.getKey(),
                    e.getValue().size() > 1 ? e.getValue().size() + " rows with this key" : "", null,
                    pick(e.getValue().get(0), dstShown)));
        }
        // the report in key order, whichever side a row came from
        diffs.sort(java.util.Comparator.comparing(RowDiff::key, DataCompare::compareKeys));
        return new Result(source.rows().size(), target.rows().size(), same, changed, onlySource, onlyTarget,
                duplicateKeys, compared, shown, diffs);
    }

    /** Orders "12 | A" after "9 | B": numeric parts compare as numbers. */
    private static int compareKeys(String a, String b) {
        String[] x = a.split(" \\| ", -1);
        String[] y = b.split(" \\| ", -1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c;
            try {
                c = new BigDecimal(x[i]).compareTo(new BigDecimal(y[i]));
            } catch (NumberFormatException e) {
                c = x[i].compareTo(y[i]);
            }
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }

    private static void add(List<RowDiff> diffs, int max, RowDiff diff) {
        if (diffs.size() < max) diffs.add(diff);
    }

    /** The values of {@code columns} in a row (an ArrayList: values may be null). */
    private static List<Object> pick(List<Object> row, int[] columns) {
        List<Object> values = new ArrayList<>(columns.length);
        for (int c : columns) values.add(row.get(c));
        return values;
    }

    private static Map<String, List<List<Object>>> byKey(TableData data, List<String> keyColumns) {
        int[] keyIndexes = keyColumns.stream().mapToInt(k -> index(data, k)).toArray();
        Map<String, List<List<Object>>> map = new LinkedHashMap<>();
        for (List<Object> row : data.rows()) {
            StringBuilder key = new StringBuilder();
            for (int i : keyIndexes) {
                if (key.length() > 0) key.append(" | ");
                key.append(show(row.get(i)));
            }
            map.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(row);
        }
        return map;
    }

    private static String signature(List<Object> row, int[] columns) {
        StringBuilder sb = new StringBuilder();
        for (int c : columns) sb.append(normalize(row.get(c))).append('\u0001');
        return sb.toString();
    }

    private static int index(TableData data, String column) {
        for (int i = 0; i < data.columns().size(); i++) {
            if (data.columns().get(i).equalsIgnoreCase(column)) return i;
        }
        return -1;
    }

    /** Equal values from different drivers/types compare equal: 1.50 = 1.5, Integer 7 = Long 7. */
    public static String normalize(Object v) {
        if (v == null) return "\u0000NULL";
        if (v instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) return v.toString();
        if (v instanceof Double || v instanceof Float) return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
        if (v instanceof BlobValue b) return "blob:" + b.length() + ":" + Arrays.hashCode(b.head());
        if (v instanceof Boolean bool) return bool ? "true" : "false";
        return v.toString();
    }

    private static String show(Object v) {
        if (v == null) return "NULL";
        String s = v.toString().replace('\n', ' ');
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
