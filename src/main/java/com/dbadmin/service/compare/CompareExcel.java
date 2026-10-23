package com.dbadmin.service.compare;

import com.dbadmin.service.compare.DataCompare.RowStatus;
import com.dbadmin.service.transfer.RowSink;
import com.dbadmin.service.transfer.Xlsx;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A data compare as an Excel sheet to decide verdicts in (e.g. by someone without the app), and the verdicts read
 * back. The sheet: Status, Verdict (a drop-down of the verdicts that fit the row's status), the key columns, then each
 * other column's source and target value, and the note. Importing reads only the key columns, Status and Verdict: a
 * row is found by its key (and status), and its verdict is set where it fits; everything else in the sheet is ignored,
 * so edited values change nothing.
 */
public final class CompareExcel {

    public static final String STATUS = "Status";
    public static final String VERDICT = "Verdict";

    /** What an import did: verdicts set or cleared, rows already as the sheet says, and what was left alone and why. */
    public record Imported(int changed, int unchanged, List<String> problems) {
    }

    private CompareExcel() {
    }

    /** The sheet's header: Status, Verdict, the keys, "column · source" / "column · target" for the others, Note. */
    static List<String> header(DataCompareReport r) {
        List<String> h = new ArrayList<>(List.of(STATUS, VERDICT));
        h.addAll(r.keyColumns);
        for (String c : others(r)) {
            h.add(c + " · " + r.source.label());
            h.add(c + " · " + r.target.label());
        }
        h.add("Note");
        return h;
    }

    private static List<String> others(DataCompareReport r) {
        return r.shownColumns.subList(Math.min(r.keyColumns.size(), r.shownColumns.size()), r.shownColumns.size());
    }

    public static void write(DataCompareReport r, OutputStream out) throws IOException {
        int keys = r.keyColumns.size();
        // the Verdict cells (column B) of each status, for its drop-down
        Map<RowStatus, List<Integer>> rowsByStatus = new EnumMap<>(RowStatus.class);
        for (int i = 0; i < r.rows.size(); i++) rowsByStatus.computeIfAbsent(r.rows.get(i).status, s -> new ArrayList<>()).add(i + 2);
        List<Xlsx.DropDown> dropDowns = new ArrayList<>();
        rowsByStatus.forEach((status, rows) -> {
            List<String> allowed = Verdict.allowed(status).stream().map(Verdict::name).toList();
            if (!allowed.isEmpty()) {
                dropDowns.add(new Xlsx.DropDown(ranges("B", rows), allowed, statusName(status) + " rows take " + String.join(" or ", allowed)
                        + ", or nothing (undecided)."));
            }
        });
        List<Integer> widths = new ArrayList<>(List.of(14, 10));
        for (int i = 0; i < keys; i++) widths.add(18);
        for (int i = 0; i < others(r).size() * 2; i++) widths.add(24);
        widths.add(40);
        RowSink sheet = Xlsx.writer(out, r.source.table(), widths, dropDowns);
        sheet.begin(header(r), List.of());
        for (DataCompareReport.Row row : r.rows) {
            List<Object> cells = new ArrayList<>();
            cells.add(statusName(row.status));
            cells.add(row.verdict == null ? null : row.verdict.name());
            List<String> keyValues = keyValues(row, keys);
            cells.addAll(keyValues);
            for (int c = keys; c < r.shownColumns.size(); c++) {
                cells.add(row.source == null ? null : row.source.get(c));
                cells.add(row.target == null ? null : row.target.get(c));
            }
            cells.add(row.note == null || row.note.isEmpty() ? null : row.note);
            sheet.row(cells);
        }
        sheet.end();
    }

    /** The status as the sheet writes it: the enum name, readable, and what Import reads back. */
    static String statusName(RowStatus s) {
        return s.name();
    }

    /** "B2:B4 B7" from row numbers. */
    static String ranges(String column, List<Integer> rows) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            int from = rows.get(i);
            int to = from;
            while (i + 1 < rows.size() && rows.get(i + 1) == to + 1) to = rows.get(++i);
            parts.add(from == to ? column + from : column + from + ":" + column + to);
            i++;
        }
        return String.join(" ", parts);
    }

    /** A row's key values: from the source when it has the row, else the target. */
    private static List<String> keyValues(DataCompareReport.Row row, int keys) {
        List<String> side = row.source != null ? row.source : row.target;
        return side == null ? List.of() : new ArrayList<>(side.subList(0, Math.min(keys, side.size())));
    }

    /**
     * Sets the report's verdicts from a sheet (as {@link Xlsx#read} gives it). A sheet row is matched to a compare row by
     * its key and status; when a key has several rows with the same status (one-to-many), in order. An empty Verdict
     * clears it.
     */
    public static Imported read(DataCompareReport r, List<List<String>> sheet) {
        if (sheet.isEmpty()) throw new IllegalArgumentException("The sheet is empty.");
        List<String> head = sheet.get(0).stream().map(String::strip).toList();
        int statusAt = indexOf(head, STATUS);
        int verdictAt = indexOf(head, VERDICT);
        List<Integer> keyAt = r.keyColumns.stream().map(k -> indexOf(head, k)).toList();
        List<String> missing = new ArrayList<>();
        if (verdictAt < 0) missing.add(VERDICT);
        if (statusAt < 0) missing.add(STATUS);
        for (int i = 0; i < keyAt.size(); i++) if (keyAt.get(i) < 0) missing.add(r.keyColumns.get(i));
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The sheet has no column " + String.join(", ", missing)
                    + ". Import reads a sheet exported from this compare (Export Excel…).");
        }
        // the compare's rows by key and status, in order
        Map<String, List<DataCompareReport.Row>> byKey = new HashMap<>();
        for (DataCompareReport.Row row : r.rows) byKey.computeIfAbsent(match(keyValues(row, keyAt.size()), row.status.name()), k -> new ArrayList<>()).add(row);
        Map<String, Integer> used = new HashMap<>();
        int changed = 0;
        int unchanged = 0;
        List<String> problems = new ArrayList<>();
        for (int n = 1; n < sheet.size(); n++) {
            List<String> cells = sheet.get(n);
            if (cells.stream().allMatch(String::isBlank)) continue;
            int line = n + 1;
            List<String> key = keyAt.stream().map(i -> cell(cells, i)).toList();
            String status = cell(cells, statusAt).strip().toUpperCase(Locale.ROOT);
            String word = cell(cells, verdictAt).strip();
            String k = match(key, status);
            List<DataCompareReport.Row> candidates = byKey.getOrDefault(k, List.of());
            int next = used.getOrDefault(k, 0);
            if (next >= candidates.size()) {
                String other = r.rows.stream().filter(x -> match(keyValues(x, keyAt.size()), "").equals(match(key, "")))
                        .map(x -> x.status.name()).findFirst().orElse(null);
                problems.add("Row " + line + " (" + String.join(" | ", key) + "): " + (other != null
                        ? "it is " + other + " in this compare, not " + status + "; left alone (compare again, or export a new sheet)"
                        : "not in this compare"));
                continue;
            }
            used.put(k, next + 1);
            DataCompareReport.Row row = candidates.get(next);
            Verdict v = word.isEmpty() ? null : Verdicts.parse(word);
            if (!word.isEmpty() && v == null) {
                problems.add("Row " + line + ": \"" + word + "\" is not a verdict; left alone");
                continue;
            }
            if (v != null && !Verdict.allowed(row.status).contains(v)) {
                problems.add("Row " + line + ": " + v.name() + " does not fit a " + row.status.name() + " row; left alone");
                continue;
            }
            if (Verdict.allowed(row.status).isEmpty()) continue; // MATCH, duplicate keys: nothing to decide
            if (row.verdict == v) {
                unchanged++;
            } else {
                row.verdict = v;
                changed++;
            }
        }
        return new Imported(changed, unchanged, problems);
    }

    private static int indexOf(List<String> head, String name) {
        for (int i = 0; i < head.size(); i++) if (head.get(i).equalsIgnoreCase(name)) return i;
        return -1;
    }

    private static String cell(List<String> cells, int i) {
        return i >= 0 && i < cells.size() && cells.get(i) != null ? cells.get(i) : "";
    }

    /** Key values and status as one lookup key; numbers compare by value (Excel may turn "12.50" into 12.5). */
    private static String match(List<String> key, String status) {
        StringBuilder sb = new StringBuilder(status).append('\u0001');
        for (String v : key) sb.append(same(v)).append('\u0001');
        return sb.toString();
    }

    private static String same(String v) {
        if (v == null || v.isEmpty()) return "\u0000NULL"; // a NULL key is written as an empty cell
        try {
            return new BigDecimal(v.strip()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return v;
        }
    }
}
