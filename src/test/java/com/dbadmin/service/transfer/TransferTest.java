package com.dbadmin.service.transfer;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferTest {

    private static String csv(List<List<Object>> rows, char delimiter) throws Exception {
        StringWriter out = new StringWriter();
        RowSink sink = Csv.writer(out, delimiter, true);
        sink.begin(List.of("id", "name"), List.of("int4", "text"));
        for (List<Object> r : rows) sink.row(r);
        sink.end();
        return out.toString();
    }

    @Test
    void csvQuotesWhatWouldBreakItAndReadsItBack() throws Exception {
        List<List<Object>> rows = List.of(List.of(1, "plain"), List.of(2, "a,b"), List.of(3, "say \"hi\"\nnext line"),
                Arrays.asList(4, null), List.of(5, new BigDecimal("1E+3")));
        String text = csv(rows, ',');
        assertEquals("id,name\r\n1,plain\r\n2,\"a,b\"\r\n3,\"say \"\"hi\"\"\nnext line\"\r\n4,\r\n5,1000\r\n", text);
        List<List<String>> back = Csv.read(new StringReader("\uFEFF" + text), ',');
        assertEquals(List.of("id", "name"), back.get(0));
        assertEquals(List.of("3", "say \"hi\"\nnext line"), back.get(3));
        assertEquals(List.of("4", ""), back.get(4));
        assertEquals(6, back.size());
    }

    @Test
    void delimiterIsGuessedFromTheHeader() {
        assertEquals(';', Csv.guessDelimiter("id;name;price"));
        assertEquals('\t', Csv.guessDelimiter("id\tname"));
        assertEquals(',', Csv.guessDelimiter("id,name"));
        // any symbol between the names, quoted or not (an export with § as the delimiter)
        assertEquals('§', Csv.guessDelimiter("id§\"created_date\"§\"created_by\"§\"module\""));
        assertEquals(',', Csv.guessDelimiter("\"a§b\",\"c§d\""), "inside quotes it is part of a name");
        assertEquals(',', Csv.guessDelimiter("created_at,modified-by,schema.table"), "_ - . are parts of names");
        assertEquals(',', Csv.guessDelimiter("single"));
    }

    @Test
    void sqlLiteralsPerDatabase() {
        assertEquals("'it''s'", SqlInserts.literal("it's", DbType.POSTGRESQL));
        assertEquals("'C:\\\\tmp'", SqlInserts.literal("C:\\tmp", DbType.MYSQL));
        assertEquals("'C:\\tmp'", SqlInserts.literal("C:\\tmp", DbType.POSTGRESQL));
        assertEquals("'\\x0aff'::bytea", SqlInserts.literal(new byte[]{10, -1}, DbType.POSTGRESQL));
        assertEquals("X'0aff'", SqlInserts.literal(new byte[]{10, -1}, DbType.MYSQL));
        assertEquals("1", SqlInserts.literal(true, DbType.SQLITE));
        assertEquals("TRUE", SqlInserts.literal(true, DbType.POSTGRESQL));
        assertEquals("NULL", SqlInserts.literal(null, DbType.MYSQL));
        assertEquals("1000", SqlInserts.literal(new BigDecimal("1E+3"), DbType.POSTGRESQL));
    }

    @Test
    void insertsCanSkipRowsWhoseKeyExists() {
        List<String> cols = List.of("id", "name");
        List<String> vals = List.of("1", "'a'");
        assertEquals("INSERT INTO t (\"id\", \"name\") VALUES (1, 'a');", SqlInserts.statement(DbType.POSTGRESQL, "t", "\"", cols, vals, List.of()));
        assertEquals("INSERT INTO t (\"id\", \"name\") VALUES (1, 'a') ON CONFLICT DO NOTHING;",
                SqlInserts.statement(DbType.POSTGRESQL, "t", "\"", cols, vals, List.of("id")));
        assertEquals("INSERT INTO t (`id`, `name`) SELECT 1, 'a' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t WHERE `id` = 1);",
                SqlInserts.statement(DbType.MYSQL, "t", "`", cols, vals, List.of("id")));
        assertEquals("INSERT OR IGNORE INTO t (\"id\", \"name\") VALUES (1, 'a');", SqlInserts.statement(DbType.SQLITE, "t", "\"", cols, vals, List.of("id")));
    }

    @Test
    void insertsSkipRowsFoundByChosenColumnsWithNotExists() {
        List<String> cols = List.of("id", "Code", "note");
        List<String> vals = List.of("1", "'A'", "NULL");
        assertEquals("INSERT INTO t (\"id\", \"Code\", \"note\") SELECT 1, 'A', NULL WHERE NOT EXISTS (SELECT 1 FROM t WHERE \"Code\" = 'A');",
                SqlInserts.statementIfAbsent(DbType.POSTGRESQL, "t", "\"", cols, vals, List.of("code")));
        assertEquals("INSERT INTO t (`id`, `Code`, `note`) SELECT 1, 'A', NULL FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t WHERE `Code` = 'A' AND `note` IS NULL);",
                SqlInserts.statementIfAbsent(DbType.MYSQL, "t", "`", cols, vals, List.of("Code", "note")));
        assertTrue(SqlInserts.sameColumns(List.of("ID"), List.of("id")));
        assertFalse(SqlInserts.sameColumns(List.of("id", "code"), List.of("id")));
    }

    @Test
    void theWriterUsesTheConflictFormForThePrimaryKeyAndNotExistsForOtherColumns() throws Exception {
        java.io.StringWriter out = new java.io.StringWriter();
        RowSink byKey = SqlInserts.writer(out, DbType.SQLITE, "t", "\"", List.of("ID"), List.of("id"));
        byKey.begin(List.of("id", "code"), List.of("integer", "text"));
        byKey.row(java.util.Arrays.asList(1, "A"));
        assertEquals("INSERT OR IGNORE INTO t (\"id\", \"code\") VALUES (1, 'A');\n", out.toString());

        java.io.StringWriter out2 = new java.io.StringWriter();
        RowSink byCode = SqlInserts.writer(out2, DbType.SQLITE, "t", "\"", List.of("code"), List.of("id"));
        byCode.begin(List.of("id", "code"), List.of("integer", "text"));
        byCode.row(java.util.Arrays.asList(1, "A"));
        assertEquals("INSERT INTO t (\"id\", \"code\") SELECT 1, 'A' WHERE NOT EXISTS (SELECT 1 FROM t WHERE \"code\" = 'A');\n", out2.toString());

        RowSink missing = SqlInserts.writer(new java.io.StringWriter(), DbType.SQLITE, "t", "\"", List.of("code"), List.of("id"));
        assertThrows(java.io.IOException.class, () -> missing.begin(List.of("id"), List.of("integer")));
    }

    @Test
    void xlsxIsAZipWithTheSheet() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RowSink sink = Xlsx.writer(bytes, "m_product_variant");
        sink.begin(List.of("id", "name", "active"), List.of("int4", "text", "bool"));
        sink.row(List.of(1, "A & <B>", true));
        sink.row(Arrays.asList(2, null, false));
        sink.end();
        List<String> entries = new ArrayList<>();
        String sheet = null;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                entries.add(e.getName());
                if (e.getName().equals("xl/worksheets/sheet1.xml")) sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        assertTrue(entries.containsAll(List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml")));
        assertTrue(sheet.contains("<c r=\"A2\"><v>1</v></c>"));
        assertTrue(sheet.contains("A &amp; &lt;B&gt;"));
        assertTrue(sheet.contains("<c r=\"C2\" t=\"b\"><v>1</v></c>"));
        assertTrue(sheet.contains("<row r=\"3\"><c r=\"A3\"><v>2</v></c><c r=\"C3\" t=\"b\"><v>0</v></c></row>")); // NULL: no cell
        assertEquals("AA", Xlsx.column(26));
    }

    @Test
    void jsonHasOneObjectPerRow() throws Exception {
        StringWriter out = new StringWriter();
        RowSink sink = Json.writer(out);
        sink.begin(List.of("id", "name", "price"), List.of());
        sink.row(Arrays.asList(1, "A", new BigDecimal("1.50")));
        sink.row(Arrays.asList(2, null, null));
        sink.end();
        String json = out.toString().replaceAll("\\s+", "");
        assertEquals("[{\"id\":1,\"name\":\"A\",\"price\":1.50},{\"id\":2,\"name\":null,\"price\":null}]", json);
    }

    /** Export a table to CSV, read it back, insert it into an empty copy; keys already there are found. */
    @Test
    void exportAndImportRoundTrip(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.executeUpdate("create table src (id integer primary key, name text, price numeric, note text)");
            st.executeUpdate("create table dst (id integer primary key, name text, price numeric, note text)");
            st.executeUpdate("insert into src values (1, 'EMAS 1G', 1.5, 'line1\nline2'), (2, 'a,b \"q\"', 2, null), (3, 'C', 3.25, '')");
            st.executeUpdate("insert into dst values (2, 'already here', 0, null)");
        }
        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setId("t");
        cfg.setName("t");
        cfg.setType(DbType.SQLITE);
        cfg.setDatabase(db.toString());
        cfg.setReadOnly(false);
        try (DatabaseSession s = DatabaseSession.open(cfg)) {
            TableRef src = new TableRef(null, null, "src", "TABLE");
            TableRef dst = new TableRef(null, null, "dst", "TABLE");
            Path file = dir.resolve("src.csv");
            try (var out = Files.newBufferedWriter(file)) {
                assertEquals(3, s.streamRows("SELECT * FROM " + s.qualifiedName(src), Csv.writer(out, ',', true), n -> { }));
            }
            List<List<String>> records = Csv.read(Files.newBufferedReader(file), ',');
            List<String> header = records.get(0);
            List<List<String>> rows = new ArrayList<>();
            for (List<String> r : records.subList(1, records.size())) {
                rows.add(r.stream().map(v -> v.isEmpty() ? null : v).toList()); // empty -> NULL
            }

            Set<String> existing = s.keyValues(dst, List.of("id"));
            List<List<String>> fresh = rows.stream().filter(r -> !existing.contains(DatabaseSession.keyText(List.of(r.get(0))))).toList();
            assertEquals(2, fresh.size()); // id 2 is already in dst
            assertEquals(2, s.insertRows(dst, header, fresh, n -> { }));

            var result = s.execute("SELECT id, name, price, note FROM dst ORDER BY id", 100).data().rows();
            assertEquals(3, result.size());
            assertEquals("EMAS 1G", result.get(0).get(1));
            assertEquals("line1\nline2", result.get(0).get(3));
            assertEquals("already here", result.get(1).get(1));
            assertEquals(null, result.get(2).get(3)); // empty CSV field imported as NULL
        }
    }
}
