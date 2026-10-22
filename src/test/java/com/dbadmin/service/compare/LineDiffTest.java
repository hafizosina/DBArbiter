package com.dbadmin.service.compare;

import com.dbadmin.service.compare.LineDiff.Kind;
import com.dbadmin.service.compare.LineDiff.Line;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineDiffTest {

    /** "-a", "+b", " c", "~2" (folded): one line per diff line. */
    private static List<String> show(List<Line> lines) {
        return lines.stream().map(l -> switch (l.kind()) {
            case SAME -> " " + l.text();
            case REMOVED -> "-" + l.text();
            case ADDED -> "+" + l.text();
            case FOLDED -> "~" + l.oldNo();
        }).toList();
    }

    private static String marked(Line l) {
        return l.changes().stream().map(c -> l.text().substring(c[0], c[1])).collect(Collectors.joining("|"));
    }

    @Test
    void changedLineIsRemovedThenAddedWithItsWordsMarked() {
        List<Line> d = LineDiff.diff("Dear customer,\nyour gold is ready.\nThanks", "Dear customer,\nyour silver bar is ready.\nThanks", false);
        assertEquals(List.of(" Dear customer,", "-your gold is ready.", "+your silver bar is ready.", " Thanks"), show(d));
        assertEquals("gold", marked(d.get(1)));
        assertEquals("silver bar", marked(d.get(2))); // neighbouring changed words are one range
        assertEquals(2, d.get(1).oldNo());
        assertEquals(2, d.get(2).newNo());
    }

    @Test
    void singleValues() {
        assertEquals(List.of("-14", "+7"), show(LineDiff.diff("14", "7", false)));
        assertEquals(List.of("-a,b", "+a"), show(LineDiff.diff("a,b", "a", false)));
        assertEquals(",b", marked(LineDiff.diff("a,b", "a", false).get(0)));
    }

    @Test
    void addedAndRemovedLines() {
        assertEquals(List.of(" a", "+x", " b", "-c"), show(LineDiff.diff("a\nb\nc", "a\nx\nb", false)));
    }

    @Test
    void aValueWithNothingOnTheOtherSideIsAllNewOrAllDeleted() {
        assertEquals(List.of("+Dear customer,", "+thanks"), show(LineDiff.diff(null, "Dear customer,\nthanks", false)));
        assertEquals(List.of("-old row"), show(LineDiff.diff("old row", null, false)));
    }

    @Test
    void longUnchangedRunsAreFolded() {
        List<String> a = new ArrayList<>();
        for (int i = 1; i <= 20; i++) a.add("line " + i);
        List<String> b = new ArrayList<>(a);
        b.set(9, "line TEN");
        List<String> shown = show(LineDiff.diff(String.join("\n", a), String.join("\n", b), false));
        // 6 folded before (keeping 3), the change, 3 kept then 7 folded after
        assertEquals(List.of("~6", " line 7", " line 8", " line 9", "-line 10", "+line TEN", " line 11", " line 12", " line 13", "~7"), shown);
    }

    @Test
    void whitespaceCanBeIgnored() {
        assertEquals(List.of(" select  *", " from t"), show(LineDiff.diff("select  *\nfrom t", "select *\n  from t", true)));
        assertTrue(show(LineDiff.diff("select  *", "select *", false)).contains("-select  *"));
        // whitespace-only differences are never marked as changed words
        assertEquals("", marked(LineDiff.diff("a  b", "a b", false).get(0)));
    }
}
