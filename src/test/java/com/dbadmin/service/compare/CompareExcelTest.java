package com.dbadmin.service.compare;

import com.dbadmin.service.compare.DataCompare.RowStatus;
import com.dbadmin.service.transfer.Xlsx;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareExcelTest {

    private static DataCompareReport.Row row(RowStatus status, String code, List<String> source, List<String> target) {
        DataCompareReport.Row r = new DataCompareReport.Row();
        r.status = status;
        r.key = code;
        r.note = "";
        r.source = source;
        r.target = target;
        return r;
    }

    static DataCompareReport report() {
        DataCompareReport r = new DataCompareReport();
        r.source = new DataCompareReport.Side("uat", "app", "m_product_tier", "UAT");
        r.target = new DataCompareReport.Side("prod", "app", "m_product_tier", "PROD");
        r.keyColumns = List.of("code");
        r.comparedColumns = List.of("price");
        r.shownColumns = List.of("code", "price");
        r.rows.add(row(RowStatus.ONLY_SOURCE, "A", List.of("A", "10.50"), null));
        r.rows.add(row(RowStatus.CHANGED, "B", List.of("B", "2"), List.of("B", "3")));
        r.rows.add(row(RowStatus.ONLY_TARGET, "C", null, List.of("C", "7")));
        r.rows.add(row(RowStatus.MATCH, "D", List.of("D", "1"), List.of("D", "1")));
        r.rows.add(row(RowStatus.CHANGED, "E", List.of("E", "5"), List.of("E", "6")));
        r.rows.get(1).verdict = Verdict.SKIP;
        return r;
    }

    private static byte[] export(DataCompareReport r) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompareExcel.write(r, out);
        return out.toByteArray();
    }

    private static String sheetXml(byte[] xlsx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("xl/worksheets/sheet1.xml")) return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no sheet");
    }

    @Test
    void theVerdictColumnOffersWhatFitsEachStatus() throws Exception {
        String xml = sheetXml(export(report()));
        assertTrue(xml.contains("sqref=\"B2\"><formula1>&quot;INSERT,SKIP&quot;</formula1>"), xml);
        assertTrue(xml.contains("sqref=\"B3 B6\"><formula1>&quot;UPDATE,SKIP&quot;</formula1>"), xml);
        assertTrue(xml.contains("sqref=\"B4\"><formula1>&quot;DELETE,SKIP&quot;</formula1>"), xml);
        assertTrue(!xml.contains("sqref=\"B5\""), "a MATCH row has nothing to decide");
        List<List<String>> sheet = Xlsx.read(new ByteArrayInputStream(export(report())));
        assertEquals(List.of("Status", "Verdict", "code", "price · UAT", "price · PROD", "Note"), sheet.get(0));
        assertEquals(List.of("CHANGED", "SKIP", "B", "2", "3"), sheet.get(2));
        assertEquals("10.50", sheet.get(1).get(3), "values are text: 10.50 stays 10.50");
    }

    @Test
    void importSetsTheVerdictsByKeyAndStatus() throws Exception {
        DataCompareReport r = report();
        List<List<String>> sheet = new ArrayList<>(Xlsx.read(new ByteArrayInputStream(export(r))).stream().map(x -> (List<String>) new ArrayList<>(x)).toList());
        sheet.get(1).set(1, "insert");   // A
        sheet.get(2).set(1, "");         // B: cleared
        sheet.get(3).set(1, "UPDATE");   // C is ONLY_TARGET: does not fit
        sheet.get(4).set(1, "SKIP");     // D is a MATCH: nothing to decide
        sheet.get(5).set(1, "SKIP");     // E
        sheet.get(5).set(3, "999");      // an edited value changes nothing
        sheet.add(new ArrayList<>(List.of("CHANGED", "SKIP", "Z")));
        CompareExcel.Imported done = CompareExcel.read(r, sheet);
        assertEquals(3, done.changed());
        assertEquals(Verdict.INSERT, r.rows.get(0).verdict);
        assertNull(r.rows.get(1).verdict);
        assertNull(r.rows.get(2).verdict);
        assertNull(r.rows.get(3).verdict);
        assertEquals(Verdict.SKIP, r.rows.get(4).verdict);
        assertEquals("5", r.rows.get(4).source.get(1));
        assertEquals(List.of("Row 4: UPDATE does not fit a ONLY_TARGET row; left alone", "Row 5: SKIP does not fit a MATCH row; left alone",
                "Row 7 (Z): not in this compare"), done.problems());
    }

    @Test
    void aStatusThatChangedSinceTheExportIsLeftAlone() throws Exception {
        DataCompareReport r = report();
        List<List<String>> sheet = new ArrayList<>(Xlsx.read(new ByteArrayInputStream(export(r))));
        r.rows.get(0).status = RowStatus.CHANGED; // compared again meanwhile
        CompareExcel.Imported done = CompareExcel.read(r, sheet);
        assertTrue(done.problems().get(0).startsWith("Row 2 (A): it is CHANGED in this compare, not ONLY_SOURCE"), done.problems().toString());
        assertThrows(IllegalArgumentException.class, () -> CompareExcel.read(r, List.of(List.of("code", "price"))));
    }

    @Test
    void rangesOfRows() {
        assertEquals("B2:B4 B7 B9:B10", CompareExcel.ranges("B", List.of(2, 3, 4, 7, 9, 10)));
    }
}
