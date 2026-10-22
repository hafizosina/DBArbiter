package com.dbadmin.service.compare;

import com.dbadmin.model.TableData;
import com.dbadmin.service.compare.DataCompare.RowStatus;
import com.dbadmin.service.compare.SchemaCompare.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareReportTest {

    private static TableData table(List<String> columns, Object[]... rows) {
        return new TableData(columns, Arrays.stream(rows).map(Arrays::asList).toList());
    }

    /** id 1 same, 2 changed, 3 only in source, 4 only in target; "price" is compared, "note" is not. */
    private static DataCompareReport report() {
        List<String> cols = List.of("id", "name", "price", "note");
        DataCompare.Result r = DataCompare.compare(
                table(cols, new Object[]{1, "a", new BigDecimal("1.50"), "x"}, new Object[]{2, "b", new BigDecimal("2"), "y"},
                        new Object[]{3, "<c>", null, "z"}),
                table(cols, new Object[]{1, "a", new BigDecimal("1.5"), "x"}, new Object[]{2, "b", new BigDecimal("3"), "y"},
                        new Object[]{4, "d", BigDecimal.ONE, "w"}),
                List.of("id"), List.of("name", "price"), List.of(), 100);
        return DataCompareReport.of(r, new DataCompareReport.Side("UAT", "brankas_mobile", "m_product", "UAT"),
                new DataCompareReport.Side("PreProd", "brankas_mobile", "m_product", "PreProd"),
                List.of("id"), "", 10_000, false, "v0.1.0");
    }

    private static DataCompareReport.Row row(DataCompareReport r, String key) {
        return r.rows.stream().filter(x -> x.key.equals(key)).findFirst().orElseThrow();
    }

    @Test
    void verdictsFitTheStatus() {
        assertEquals(List.of(Verdict.INSERT, Verdict.SKIP), Verdict.allowed(RowStatus.ONLY_SOURCE));
        assertEquals(List.of(Verdict.UPDATE, Verdict.SKIP), Verdict.allowed(RowStatus.CHANGED));
        assertEquals(List.of(Verdict.DELETE, Verdict.SKIP), Verdict.allowed(RowStatus.ONLY_TARGET));
        assertTrue(Verdict.allowed(RowStatus.MATCH).isEmpty());
        assertEquals("Insert into PreProd", Verdict.INSERT.label("PreProd"));
    }

    @Test
    void rowsThatCanBeWrittenKeepTheWholeSourceRow() {
        DataCompareReport r = report();
        assertEquals(List.of("id", "name", "price", "note"), r.sourceColumns);
        assertEquals(Arrays.asList("3", "<c>", null, "z"), row(r, "3").sourceRow); // NULL stays null
        assertEquals(List.of("2", "b", "2", "y"), row(r, "2").sourceRow);
        assertNull(row(r, "4").sourceRow);
        assertNull(row(r, "1").sourceRow);
        assertEquals(RowStatus.MATCH, row(r, "1").status); // 1.50 = 1.5
        assertEquals(List.of("1", "a", "1.5"), row(r, "1").source);
        assertEquals(3, r.undecided());
    }

    @Test
    void savedReportsOpenWithTheirVerdicts(@TempDir Path dir) throws Exception {
        DataCompareReport r = report();
        row(r, "2").verdict = Verdict.UPDATE;
        row(r, "3").verdict = Verdict.INSERT;
        Path file = dir.resolve("r.dbcompare");
        CompareReportFiles.save(file, r);

        DataCompareReport back = assertInstanceOf(DataCompareReport.class, CompareReportFiles.open(file));
        assertEquals(Verdict.UPDATE, row(back, "2").verdict);
        assertEquals(Verdict.INSERT, row(back, "3").verdict);
        assertNull(row(back, "4").verdict);
        assertEquals(Arrays.asList("3", "<c>", null, "z"), row(back, "3").sourceRow);
        assertEquals("PreProd", back.target.label());
        assertEquals(1, back.changed);
        assertEquals(1, back.undecided());
    }

    @Test
    void tableReportsRoundTrip(@TempDir Path dir) throws Exception {
        SchemaCompare.TableDiff t = new SchemaCompare.TableDiff("m_product", "TABLE", Status.CHANGED, List.of(
                new SchemaCompare.Item("Column", "price", Status.CHANGED, "numeric(18,2) NULL", "numeric(10,2) NULL")));
        Path file = dir.resolve("t.dbcompare");
        CompareReportFiles.save(file, SchemaCompareReport.of(List.of(t), "UAT", "PreProd", "v0.1.0"));

        SchemaCompareReport back = assertInstanceOf(SchemaCompareReport.class, CompareReportFiles.open(file));
        assertEquals(List.of(t), back.tables);
        assertEquals("UAT", back.sourceLabel);
    }

    @Test
    void settingsOnlyFilesKeepTheirSettingsAndNoRows(@TempDir Path dir) throws Exception {
        DataCompareReport r = new DataCompareReport();
        r.settingsOnly = true;
        r.source = new DataCompareReport.Side("UAT", "brankas_mobile", "m_product_variant", "UAT");
        r.target = new DataCompareReport.Side("PreProd", "brankas_mobile", "m_product_variant", "PreProd");
        r.keyColumns = List.of("product_code");
        r.joins = List.of(new KeyJoins.Join(true, "m_product_category", "mpc", "mpc.id = mpv.category_id"));
        Path file = dir.resolve("s.dbcompare");
        CompareReportFiles.save(file, r);
        DataCompareReport back = assertInstanceOf(DataCompareReport.class, CompareReportFiles.open(file));
        assertTrue(back.settingsOnly);
        assertTrue(back.rows.isEmpty());
        assertEquals(r.joins, back.joins);
        // files saved before: results, not settings only
        assertFalse(assertInstanceOf(DataCompareReport.class, CompareReportFiles.open(save(dir, report()))).settingsOnly);
    }

    private static Path save(Path dir, Object report) throws Exception {
        Path file = dir.resolve("r.dbcompare");
        CompareReportFiles.save(file, report);
        return file;
    }

    @Test
    void otherFilesAreNotReports(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("x.dbcompare");
        java.nio.file.Files.writeString(file, "{\"hello\": 1}");
        assertThrows(java.io.IOException.class, () -> CompareReportFiles.open(file));
    }

    @Test
    void htmlListsDifferencesOnlyAndEscapesValues() {
        DataCompareReport r = report();
        row(r, "3").verdict = Verdict.INSERT;
        String html = CompareHtml.data(r);
        assertTrue(html.contains("&lt;c&gt;"));
        assertFalse(html.contains("<c>"));
        assertTrue(html.contains(">INSERT</span>"));
        assertTrue(html.contains("Differences (3)"));
        assertTrue(html.contains("1 identical rows are not listed"));
        assertTrue(html.contains("<span class=old>2</span><br><span class=new>3</span>"));
    }

    /** A report from a newer app version is refused with a clear message; one without a version opens as version 1. */
    @Test
    void checksTheFileFormatVersion(@TempDir Path dir) throws Exception {
        Path newer = dir.resolve("newer.dbcompare");
        java.nio.file.Files.writeString(newer, "{\"kind\": \"data\", \"version\": " + (DataCompareReport.VERSION + 1) + "}");
        java.io.IOException e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> CompareReportFiles.open(newer));
        assertTrue(e.getMessage().contains("newer version of DBArbiter"), e.getMessage());

        Path unversioned = dir.resolve("old.dbcompare");
        java.nio.file.Files.writeString(unversioned, "{\"kind\": \"table\", \"sourceLabel\": \"UAT\"}");
        SchemaCompareReport r = (SchemaCompareReport) CompareReportFiles.open(unversioned);
        assertEquals("UAT", r.sourceLabel);
        assertEquals(SchemaCompareReport.VERSION, r.version);
    }
}
