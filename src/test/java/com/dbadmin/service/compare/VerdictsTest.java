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
}
