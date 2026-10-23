package com.dbadmin.service.compare;

import com.dbadmin.service.compare.DataCompare.RowStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictsTest {

    private static DataCompareReport.Row row(RowStatus status, Verdict verdict) {
        DataCompareReport.Row r = new DataCompareReport.Row();
        r.status = status;
        r.verdict = verdict;
        return r;
    }

    @Test
    void appliesOnlyWhereTheVerdictFits() {
        DataCompareReport.Row added = row(RowStatus.ONLY_SOURCE, null);
        DataCompareReport.Row changed = row(RowStatus.CHANGED, null);
        DataCompareReport.Row same = row(RowStatus.MATCH, null);
        assertEquals(new Verdicts.Applied(1, 2), Verdicts.apply(Verdict.INSERT, List.of(added, changed, same)));
        assertEquals(Verdict.INSERT, added.verdict);
        assertNull(changed.verdict);

        // the same again changes nothing; clearing fits every row
        assertEquals(new Verdicts.Applied(0, 2), Verdicts.apply(Verdict.INSERT, List.of(added, changed, same)));
        assertEquals(new Verdicts.Applied(1, 0), Verdicts.apply(null, List.of(added, changed, same)));
        assertNull(added.verdict);
    }

    @Test
    void suggestsTheUsualVerdictAndKeepsDecidedRows() {
        DataCompareReport.Row added = row(RowStatus.ONLY_SOURCE, null);
        DataCompareReport.Row changed = row(RowStatus.CHANGED, null);
        DataCompareReport.Row removed = row(RowStatus.ONLY_TARGET, null);
        DataCompareReport.Row decided = row(RowStatus.ONLY_TARGET, Verdict.DELETE);
        DataCompareReport.Row same = row(RowStatus.MATCH, null);
        assertEquals(3, Verdicts.suggest(List.of(added, changed, removed, decided, same)));
        assertEquals(Verdict.INSERT, added.verdict);
        assertEquals(Verdict.UPDATE, changed.verdict);
        assertEquals(Verdict.SKIP, removed.verdict, "DELETE is never suggested");
        assertEquals(Verdict.DELETE, decided.verdict);
        assertNull(same.verdict);
    }

    @Test
    void pastesOneVerdictOnEveryRowOrOneByOne() {
        DataCompareReport.Row a = row(RowStatus.ONLY_SOURCE, null);
        DataCompareReport.Row b = row(RowStatus.CHANGED, null);
        assertEquals(new Verdicts.Applied(1, 1), Verdicts.paste("insert\n", List.of(a, b)));
        assertEquals(Verdict.INSERT, a.verdict);

        // one per row, in order; "–" (an undecided cell) clears, a non-verdict skips its row
        assertEquals(new Verdicts.Applied(2, 0), Verdicts.paste("–\nUPDATE", List.of(a, b)));
        assertNull(a.verdict);
        assertEquals(Verdict.UPDATE, b.verdict);
        assertEquals(new Verdicts.Applied(1, 1), Verdicts.paste("SKIP\nhello", List.of(a, b)));
        assertEquals(Verdict.SKIP, a.verdict);

        IllegalArgumentException notAVerdict = assertThrows(IllegalArgumentException.class, () -> Verdicts.paste("hello", List.of(a)));
        assertTrue(notAVerdict.getMessage().contains("no verdict"));
        IllegalArgumentException count = assertThrows(IllegalArgumentException.class, () -> Verdicts.paste("SKIP\nSKIP\nSKIP", List.of(a, b)));
        assertTrue(count.getMessage().contains("3 verdicts copied but 2 rows"));
        assertEquals(new Verdicts.Applied(0, 0), Verdicts.paste(null, List.of(a)));
    }

    @Test
    void summarisesTheVerdictsInUse() {
        DataCompareReport report = new DataCompareReport();
        report.rows.addAll(List.of(row(RowStatus.ONLY_SOURCE, Verdict.INSERT), row(RowStatus.ONLY_SOURCE, Verdict.INSERT),
                row(RowStatus.CHANGED, null), row(RowStatus.MATCH, null)));
        assertEquals("2 insert · 1 to decide", Verdicts.summary(report));
        report.rows.get(2).verdict = Verdict.SKIP;
        assertEquals("2 insert · 1 skip", Verdicts.summary(report));
        assertEquals("0 to decide", Verdicts.summary(new DataCompareReport()));
    }

    private static DataCompareReport.Row keyed(String key, RowStatus status, Verdict verdict, String value) {
        DataCompareReport.Row r = row(status, verdict);
        r.key = key;
        r.source = status == RowStatus.ONLY_TARGET ? null : List.of(key, value);
        r.target = status == RowStatus.ONLY_SOURCE ? null : List.of(key, value + (status == RowStatus.CHANGED ? "-old" : ""));
        return r;
    }

    private static DataCompareReport report(DataCompareReport.Row... rows) {
        DataCompareReport r = new DataCompareReport();
        r.rows.addAll(List.of(rows));
        return r;
    }

    /** Comparing again keeps each row's verdict where it still fits, and says where it needs a look. */
    @Test
    void verdictsCarryOverToTheSameRows() {
        DataCompareReport earlier = report(
                keyed("a", RowStatus.CHANGED, Verdict.UPDATE, "1"),      // same: kept
                keyed("b", RowStatus.CHANGED, Verdict.UPDATE, "2"),      // values changed: kept, to check
                keyed("c", RowStatus.ONLY_SOURCE, Verdict.INSERT, "3"),  // now CHANGED: INSERT does not fit
                keyed("d", RowStatus.CHANGED, Verdict.UPDATE, "4"),      // now MATCH: nothing to decide
                keyed("e", RowStatus.ONLY_SOURCE, Verdict.SKIP, "5"),    // now CHANGED: SKIP still fits, to check
                keyed("f", RowStatus.ONLY_TARGET, Verdict.DELETE, "6"),  // gone
                keyed("g", RowStatus.CHANGED, null, "7"));               // undecided: nothing carried
        DataCompareReport fresh = report(
                keyed("a", RowStatus.CHANGED, null, "1"),
                keyed("b", RowStatus.CHANGED, null, "20"),
                keyed("c", RowStatus.CHANGED, null, "3"),
                keyed("d", RowStatus.MATCH, null, "4"),
                keyed("e", RowStatus.CHANGED, null, "5"),
                keyed("g", RowStatus.CHANGED, null, "7"));
        Verdicts.Carried c = Verdicts.carryOver(earlier, fresh);
        assertEquals(new Verdicts.Carried(1, 2, 2, 1), c);
        List<DataCompareReport.Row> rows = fresh.rows;
        assertEquals(Verdict.UPDATE, rows.get(0).verdict);
        assertNull(rows.get(0).warning);
        assertEquals(Verdict.UPDATE, rows.get(1).verdict);
        assertEquals("values changed since UPDATE was set: check it", rows.get(1).warning);
        assertNull(rows.get(2).verdict);
        assertEquals("was INSERT when ONLY_SOURCE; now CHANGED: decide again", rows.get(2).warning);
        assertEquals("was UPDATE when CHANGED; now MATCH: nothing to decide", rows.get(3).warning);
        assertEquals(Verdict.SKIP, rows.get(4).verdict);
        assertEquals("was SKIP when ONLY_SOURCE; now CHANGED: check it", rows.get(4).warning);
        assertNull(rows.get(5).verdict);
        assertEquals(4, fresh.toCheck());
        assertTrue(Verdicts.summary(fresh).endsWith("⚠ 4 to check"), Verdicts.summary(fresh));

        // deciding a row again clears its warning
        Verdicts.apply(Verdict.UPDATE, List.of(rows.get(2)));
        assertNull(rows.get(2).warning);
        Verdicts.apply(Verdict.UPDATE, List.of(rows.get(1))); // the same verdict, confirmed
        assertNull(rows.get(1).warning);
    }

    /** One-to-many: several rows of a key are matched by their values first. */
    @Test
    void rowsOfOneKeyAreMatchedByTheirValues() {
        DataCompareReport earlier = report(keyed("t1", RowStatus.ONLY_SOURCE, Verdict.INSERT, "img-a"),
                keyed("t1", RowStatus.ONLY_SOURCE, Verdict.SKIP, "img-b"));
        DataCompareReport fresh = report(keyed("t1", RowStatus.ONLY_SOURCE, null, "img-b"),
                keyed("t1", RowStatus.ONLY_SOURCE, null, "img-a"));
        assertEquals(new Verdicts.Carried(2, 0, 0, 0), Verdicts.carryOver(earlier, fresh));
        assertEquals(Verdict.SKIP, fresh.rows.get(0).verdict);
        assertEquals(Verdict.INSERT, fresh.rows.get(1).verdict);
    }
}
