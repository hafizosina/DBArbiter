package com.dbadmin.service.compare;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Setting the verdicts of a data compare's rows: by hand, suggested, or pasted. A verdict only goes on a row whose
 * status it fits (see {@link Verdict#allowed}); other rows are left alone and counted. No JavaFX: the Data Compare tab
 * calls these and says what happened.
 */
public final class Verdicts {

    /** What a change did: rows whose verdict changed, rows left alone (the verdict does not fit their status). */
    public record Applied(int changed, int skipped) {
    }

    private Verdicts() {
    }

    /** Sets {@code v} (null: clears it) on each row it fits. */
    public static Applied apply(Verdict v, Collection<DataCompareReport.Row> rows) {
        int changed = 0;
        int skipped = 0;
        for (DataCompareReport.Row r : rows) {
            if (v != null && !Verdict.allowed(r.status).contains(v)) {
                skipped++;
            } else if (r.verdict != v || r.warning != null) {
                r.verdict = v;
                r.warning = null; // decided again: nothing left to check
                changed++;
            }
        }
        return new Applied(changed, skipped);
    }

    /**
     * Every row without a verdict gets the usual one for its status: only in source INSERT, changed UPDATE, only in
     * target SKIP (DELETE is never suggested). Rows already decided are kept. Returns how many were set.
     */
    public static int suggest(Collection<DataCompareReport.Row> rows) {
        int set = 0;
        for (DataCompareReport.Row r : rows) {
            if (r.verdict != null) continue;
            Verdict v = switch (r.status) {
                case ONLY_SOURCE -> Verdict.INSERT;
                case CHANGED -> Verdict.UPDATE;
                case ONLY_TARGET -> Verdict.SKIP;
                case MATCH, DUPLICATE_KEY -> null;
            };
            if (v == null) continue;
            r.verdict = v;
            r.warning = null;
            set++;
        }
        return set;
    }

    /**
     * Pastes copied text (one verdict per line, e.g. Verdict cells copied with Ctrl+C) onto rows: one word goes on
     * every row it fits; several go one by one and must be as many as the rows. A word that is not a verdict skips its
     * row; "–" (an undecided cell) or CLEAR clears it.
     *
     * @throws IllegalArgumentException with a message for the user when nothing can be pasted
     */
    public static Applied paste(String clipboard, List<DataCompareReport.Row> rows) {
        List<String> words = clipboard == null ? List.of() : clipboard.lines().map(String::strip).filter(w -> !w.isEmpty()).toList();
        if (words.isEmpty() || rows.isEmpty()) return new Applied(0, 0);
        if (words.size() == 1) {
            String word = words.get(0);
            Verdict v = parse(word);
            if (v == null && !isClear(word)) {
                throw new IllegalArgumentException("The clipboard holds no verdict (INSERT, UPDATE, DELETE or SKIP).");
            }
            return apply(v, rows);
        }
        if (words.size() != rows.size()) {
            throw new IllegalArgumentException(words.size() + " verdicts copied but " + rows.size()
                    + " rows selected: select as many rows, or copy one verdict.");
        }
        int changed = 0;
        int skipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            String word = words.get(i);
            Verdict v = parse(word);
            if (v == null && !isClear(word)) {
                skipped++;
                continue;
            }
            Applied one = apply(v, List.of(rows.get(i)));
            changed += one.changed();
            skipped += one.skipped();
        }
        return new Applied(changed, skipped);
    }

    /** What {@link #carryOver} did. */
    public record Carried(int kept, int toCheck, int notFitting, int gone) {
        public boolean any() {
            return kept + toCheck + notFitting + gone > 0;
        }
    }

    /**
     * After comparing again: each row of the new results takes the verdict its row (same key) had in the earlier
     * results. A verdict that still fits the row's status is kept; if the values changed since, it is kept with a
     * warning to check it. One that no longer fits (e.g. INSERT on a row that is now CHANGED, or any verdict on a row
     * that now matches) is not applied: the row gets a warning saying what it was. With several rows of a key
     * (one-to-many), rows are matched by their values first, then in order. Verdicts whose key is gone are counted.
     */
    public static Carried carryOver(DataCompareReport earlier, DataCompareReport fresh) {
        java.util.Map<String, List<DataCompareReport.Row>> decided = new java.util.LinkedHashMap<>();
        for (DataCompareReport.Row r : earlier.rows) {
            if (r.verdict != null) decided.computeIfAbsent(r.key, k -> new ArrayList<>()).add(r);
        }
        int kept = 0;
        int toCheck = 0;
        int notFitting = 0;
        for (DataCompareReport.Row row : fresh.rows) {
            List<DataCompareReport.Row> candidates = decided.get(row.key);
            if (candidates == null || candidates.isEmpty()) continue;
            DataCompareReport.Row old = candidates.stream().filter(o -> sameValues(o, row)).findFirst()
                    .orElse(candidates.stream().filter(o -> o.status == row.status).findFirst().orElse(candidates.get(0)));
            candidates.remove(old);
            if (Verdict.allowed(row.status).contains(old.verdict)) {
                row.verdict = old.verdict;
                if (old.status == row.status && sameValues(old, row)) {
                    row.warning = null;
                    kept++;
                } else {
                    row.warning = old.status != row.status
                            ? "was " + old.verdict.name() + " when " + old.status.name() + "; now " + row.status.name() + ": check it"
                            : "values changed since " + old.verdict.name() + " was set: check it";
                    toCheck++;
                }
            } else {
                row.verdict = null;
                row.warning = "was " + old.verdict.name() + " when " + old.status.name() + "; now " + row.status.name()
                        + (Verdict.allowed(row.status).isEmpty() ? ": nothing to decide" : ": decide again");
                notFitting++;
            }
        }
        int gone = decided.values().stream().mapToInt(List::size).sum();
        return new Carried(kept, toCheck, notFitting, gone);
    }

    private static boolean sameValues(DataCompareReport.Row a, DataCompareReport.Row b) {
        return java.util.Objects.equals(a.source, b.source) && java.util.Objects.equals(a.target, b.target);
    }

    /** "4 insert · 1 update · 12 to decide": the verdicts in use, then what is left to decide. */
    public static String summary(DataCompareReport report) {
        List<String> parts = new ArrayList<>();
        for (Verdict v : Verdict.values()) {
            long n = report.count(v);
            if (n > 0) parts.add(n + " " + v.label().toLowerCase(Locale.ROOT));
        }
        long open = report.undecided();
        if (open > 0 || parts.isEmpty()) parts.add(open + " to decide");
        long check = report.toCheck();
        if (check > 0) parts.add("⚠ " + check + " to check");
        return String.join(" · ", parts);
    }

    /** INSERT / insert / … as a verdict; null for anything else. */
    static Verdict parse(String word) {
        try {
            return Verdict.valueOf(word.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An undecided verdict cell copied ("–") or the word CLEAR: pasting it clears the verdict. */
    static boolean isClear(String word) {
        return word.equals("–") || word.equalsIgnoreCase("CLEAR");
    }
}
