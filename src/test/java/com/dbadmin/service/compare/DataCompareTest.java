package com.dbadmin.service.compare;

import com.dbadmin.model.TableData;
import com.dbadmin.service.compare.DataCompare.Result;
import com.dbadmin.service.compare.DataCompare.RowDiff;
import com.dbadmin.service.compare.DataCompare.RowStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataCompareTest {

    /** A table from column names and rows (values may be null). */
    private static TableData table(List<String> columns, Object[]... rows) {
        return new TableData(columns, Arrays.stream(rows).map(Arrays::asList).toList());
    }

    private static Result compare(TableData source, TableData target, List<String> compare) {
        return DataCompare.compare(source, target, List.of("id"), compare, List.of(), 100);
    }

    @Test
    void everyRowGetsAStatus() {
        List<String> cols = List.of("id", "name");
        Result r = compare(
                table(cols, new Object[]{1, "a"}, new Object[]{2, "b"}, new Object[]{3, "c"}),
                table(cols, new Object[]{1, "a"}, new Object[]{2, "B"}, new Object[]{4, "d"}),
                List.of("name"));

        assertEquals(3, r.sourceRows());
        assertEquals(3, r.targetRows());
        assertEquals(1, r.same());
        assertEquals(1, r.changed());
        assertEquals(1, r.onlySource());
        assertEquals(1, r.onlyTarget());
        assertEquals(0, r.duplicateKeys());
        assertEquals(List.of(RowStatus.MATCH, RowStatus.CHANGED, RowStatus.ONLY_SOURCE, RowStatus.ONLY_TARGET),
                r.rows().stream().map(RowDiff::status).toList());
        assertEquals("name: b → B", r.rows().get(1).details());
    }

    @Test
    void equalValuesOfDifferentTypesMatch() {
        List<String> cols = List.of("id", "amount");
        Result r = compare(
                table(cols, new Object[]{7, new BigDecimal("1.50")}, new Object[]{8, 2}),
                table(cols, new Object[]{7L, 1.5d}, new Object[]{8L, 2L}),
                List.of("amount"));
        assertEquals(2, r.same());
        assertEquals(0, r.changed());
    }

    @Test
    void nullIsNotAnEmptyString() {
        List<String> cols = List.of("id", "note");
        Result r = compare(
                table(cols, new Object[]{1, null}, new Object[]{2, null}),
                table(cols, new Object[]{1, null}, new Object[]{2, ""}),
                List.of("note"));
        assertEquals(1, r.same());
        assertEquals(1, r.changed());
    }

    @Test
    void duplicateKeysPairRowsByValue() {
        List<String> cols = List.of("id", "name");
        Result r = compare(
                table(cols, new Object[]{1, "x"}, new Object[]{1, "y"}),
                table(cols, new Object[]{1, "x"}, new Object[]{1, "z"}),
                List.of("name"));
        assertEquals(1, r.duplicateKeys());
        assertEquals(1, r.same()); // the two "x" rows
        assertEquals(1, r.changed()); // "y" against "z"
        assertEquals(0, r.onlySource());
        assertEquals(0, r.onlyTarget());
        assertEquals(RowStatus.DUPLICATE_KEY, r.rows().get(0).status());
    }

    @Test
    void reportIsInNumericKeyOrder() {
        List<String> cols = List.of("id");
        Result r = compare(table(cols, new Object[]{10}, new Object[]{9}), table(cols, new Object[]{2}), List.of());
        assertEquals(List.of("2", "9", "10"), r.rows().stream().map(RowDiff::key).toList());
    }

    @Test
    void showColumnsNeverChangeAStatus() {
        List<String> cols = List.of("id", "name", "updated_at");
        Result r = DataCompare.compare(
                table(cols, new Object[]{1, "a", "monday"}),
                table(cols, new Object[]{1, "a", "tuesday"}),
                List.of("id"), List.of("name"), List.of("updated_at"), 100);
        assertEquals(RowStatus.MATCH, r.rows().get(0).status());
        assertEquals(List.of("id", "name", "updated_at"), r.shownColumns());
        assertEquals(List.of(1, "a", "tuesday"), r.rows().get(0).targetValues());
    }

    @Test
    void columnsMatchCaseInsensitively() {
        Result r = compare(
                table(List.of("id", "name"), new Object[]{1, "a"}),
                table(List.of("ID", "NAME"), new Object[]{1, "b"}),
                List.of("name"));
        assertEquals(1, r.changed());
    }

    @Test
    void countsCoverAllRowsWhenTheReportIsCapped() {
        List<String> cols = List.of("id");
        TableData rows = table(cols, new Object[]{1}, new Object[]{2}, new Object[]{3});
        Result r = DataCompare.compare(rows, rows, List.of("id"), List.of(), List.of(), 1);
        assertEquals(3, r.same());
        assertEquals(1, r.rows().size());
    }

    @Test
    void aKeyColumnMissingOnOneSideIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> compare(
                table(List.of("id")), table(List.of("code")), List.of()));
    }

    /** A tier code with several images: matched image by image, new ones to add and old ones to remove. */
    @Test
    void oneToManyMatchesTheRowsOfAKey() {
        List<String> cols = List.of("code", "image_path");
        Result r = DataCompare.compare(
                table(cols, new Object[]{"A02", "x.jpg"}, new Object[]{"A02", "y.jpg"}, new Object[]{"A05", "p.jpg"}, new Object[]{"A05", "q.jpg"}),
                table(cols, new Object[]{"A02", "y.jpg"}, new Object[]{"A02", "z.jpg"}, new Object[]{"A09", "old.jpg"}),
                List.of("code"), List.of("image_path"), List.of(), 100, true);
        List<String> rows = r.rows().stream().map(d -> d.status() + " " + d.key() + " "
                + (d.sourceValues() != null ? d.sourceValues().get(1) : d.targetValues().get(1))).toList();
        assertEquals(List.of("ONLY_SOURCE A02 x.jpg", "MATCH A02 y.jpg", "ONLY_TARGET A02 z.jpg",
                "ONLY_SOURCE A05 p.jpg", "ONLY_SOURCE A05 q.jpg", "ONLY_TARGET A09 old.jpg"), rows);
        assertEquals(1, r.same());
        assertEquals(3, r.onlySource());
        assertEquals(2, r.onlyTarget());
        assertEquals(0, r.duplicateKeys());
        // without the mode, the same keys are duplicates
        assertEquals(2, DataCompare.compare(
                table(cols, new Object[]{"A02", "x.jpg"}, new Object[]{"A02", "y.jpg"}, new Object[]{"A05", "p.jpg"}, new Object[]{"A05", "q.jpg"}),
                table(cols, new Object[]{"A02", "y.jpg"}, new Object[]{"A02", "z.jpg"}), List.of("code"), List.of("image_path"), List.of(), 100)
                .duplicateKeys());
    }
}
