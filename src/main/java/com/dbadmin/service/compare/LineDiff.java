package com.dbadmin.service.compare;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A git-style diff of two texts: lines kept, removed (only in the source) and added (only in the
 * target), with the changed words marked inside a removed / added pair, and long runs of unchanged
 * lines folded away.
 */
public final class LineDiff {

    public enum Kind { SAME, REMOVED, ADDED, FOLDED }

    /**
     * One line of the diff. {@code oldNo} / {@code newNo}: 1-based line numbers (0 when the line is not
     * on that side). {@code changes}: [start, end) character ranges of changed words. A FOLDED line
     * stands for {@code oldNo} unchanged lines.
     */
    public record Line(Kind kind, String text, int oldNo, int newNo, List<int[]> changes) {
    }

    /** Unchanged lines kept around each change when folding. */
    public static final int CONTEXT = 3;
    /** Above this many line pairs the texts are not aligned line by line (all old lines out, all new in). */
    private static final long MAX_CELLS = 4_000_000;
    private static final Pattern TOKEN = Pattern.compile("\\w+|\\s+|[^\\w\\s]");

    private LineDiff() {
    }

    /** The diff of {@code a} (source) and {@code b} (target), with unchanged runs folded. */
    public static List<Line> diff(String a, String b, boolean ignoreSpace) {
        return fold(lines(lines(a), lines(b), ignoreSpace), CONTEXT);
    }

    static List<String> lines(String s) {
        return s == null ? List.of() : List.of(s.split("\\R", -1));
    }

    /** Longest-common-subsequence line diff, with word changes marked in each removed / added pair. */
    static List<Line> lines(List<String> a, List<String> b, boolean ignoreSpace) {
        int n = a.size();
        int m = b.size();
        List<Line> out = new ArrayList<>();
        if ((long) n * m > MAX_CELLS) {
            for (int i = 0; i < n; i++) out.add(new Line(Kind.REMOVED, a.get(i), i + 1, 0, List.of()));
            for (int j = 0; j < m; j++) out.add(new Line(Kind.ADDED, b.get(j), 0, j + 1, List.of()));
            return out;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = same(a.get(i), b.get(j), ignoreSpace) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        List<Integer> removed = new ArrayList<>();
        List<Integer> added = new ArrayList<>();
        while (i < n || j < m) {
            if (i < n && j < m && same(a.get(i), b.get(j), ignoreSpace)) {
                flush(out, a, b, removed, added);
                out.add(new Line(Kind.SAME, a.get(i), i + 1, j + 1, List.of()));
                i++;
                j++;
            } else if (j >= m || i < n && lcs[i + 1][j] >= lcs[i][j + 1]) {
                removed.add(i++);
            } else {
                added.add(j++);
            }
        }
        flush(out, a, b, removed, added);
        return out;
    }

    /** One block of changed lines: removed lines, then added ones; the k-th of each are compared word by word. */
    private static void flush(List<Line> out, List<String> a, List<String> b, List<Integer> removed, List<Integer> added) {
        List<List<int[]>> oldChanges = new ArrayList<>();
        List<List<int[]>> newChanges = new ArrayList<>();
        for (int k = 0; k < removed.size(); k++) {
            if (k < added.size()) {
                List<int[]>[] w = words(a.get(removed.get(k)), b.get(added.get(k)));
                oldChanges.add(w[0]);
                newChanges.add(w[1]);
            } else {
                oldChanges.add(List.of());
            }
        }
        while (newChanges.size() < added.size()) newChanges.add(List.of());
        for (int k = 0; k < removed.size(); k++) {
            out.add(new Line(Kind.REMOVED, a.get(removed.get(k)), removed.get(k) + 1, 0, oldChanges.get(k)));
        }
        for (int k = 0; k < added.size(); k++) {
            out.add(new Line(Kind.ADDED, b.get(added.get(k)), 0, added.get(k) + 1, newChanges.get(k)));
        }
        removed.clear();
        added.clear();
    }

    /** The changed word ranges of each of two lines (whitespace-only differences are not marked). */
    @SuppressWarnings("unchecked")
    static List<int[]>[] words(String x, String y) {
        List<int[]> xs = tokens(x);
        List<int[]> ys = tokens(y);
        int n = xs.size();
        int m = ys.size();
        if ((long) n * m > MAX_CELLS) return new List[]{List.of(new int[]{0, x.length()}), List.of(new int[]{0, y.length()})};
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = token(x, xs.get(i)).equals(token(y, ys.get(j))) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<int[]> xChanged = new ArrayList<>();
        List<int[]> yChanged = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && token(x, xs.get(i)).equals(token(y, ys.get(j)))) {
                i++;
                j++;
            } else if (j >= m || i < n && lcs[i + 1][j] >= lcs[i][j + 1]) {
                mark(xChanged, x, xs.get(i++));
            } else {
                mark(yChanged, y, ys.get(j++));
            }
        }
        return new List[]{xChanged, yChanged};
    }

    /** Adds a changed token's range, merged with the previous one when only spaces lie between them. */
    private static void mark(List<int[]> ranges, String s, int[] token) {
        if (s.substring(token[0], token[1]).isBlank()) return;
        if (!ranges.isEmpty()) {
            int[] last = ranges.get(ranges.size() - 1);
            if (s.substring(last[1], token[0]).isBlank()) {
                last[1] = token[1];
                return;
            }
        }
        ranges.add(new int[]{token[0], token[1]});
    }

    private static List<int[]> tokens(String s) {
        List<int[]> result = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) result.add(new int[]{m.start(), m.end()});
        return result;
    }

    private static String token(String s, int[] range) {
        return s.substring(range[0], range[1]);
    }

    /** Keeps {@code context} unchanged lines around each change; longer unchanged runs become one FOLDED line. */
    static List<Line> fold(List<Line> lines, int context) {
        if (lines.stream().allMatch(l -> l.kind() == Kind.SAME)) return lines; // nothing changed: show it all
        List<Line> out = new ArrayList<>();
        int k = 0;
        while (k < lines.size()) {
            if (lines.get(k).kind() != Kind.SAME) {
                out.add(lines.get(k++));
                continue;
            }
            int end = k;
            while (end < lines.size() && lines.get(end).kind() == Kind.SAME) end++;
            int keepBefore = k == 0 ? 0 : context;              // after a change
            int keepAfter = end == lines.size() ? 0 : context;  // before a change
            int hidden = (end - k) - keepBefore - keepAfter;
            if (hidden <= 1) {
                out.addAll(lines.subList(k, end));
            } else {
                out.addAll(lines.subList(k, k + keepBefore));
                out.add(new Line(Kind.FOLDED, "⋯ " + hidden + " unchanged line" + (hidden == 1 ? "" : "s"), hidden, 0, List.of()));
                out.addAll(lines.subList(end - keepAfter, end));
            }
            k = end;
        }
        return out;
    }

    private static boolean same(String x, String y, boolean ignoreSpace) {
        return ignoreSpace ? x.strip().replaceAll("\\s+", " ").equals(y.strip().replaceAll("\\s+", " ")) : x.equals(y);
    }
}
