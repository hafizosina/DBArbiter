package com.dbadmin.service.scan;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scan on a real (SQLite) database, through a Job as the app runs it. */
class DataScanTest {

    private static final Executor NOW = Runnable::run;

    @TempDir
    Path tmp;

    private ConnectionConfig config;
    private DatabaseSession session;

    @BeforeEach
    void open() throws Exception {
        Path file = tmp.resolve("shop.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("create table template (id integer primary key, body text, body_eng varchar(500), meta json, price real)");
            st.execute("insert into template (body, body_eng, meta, price) values "
                    + "('<img src=\"https://cdn/Facebook.png\">', 'https://cdn/facebook.png', '{\"icon\":\"https:\\/\\/cdn\\/Facebook.png\"}', 1.5),"
                    + "('no icon', 'https://cdn/Instagram.png', null, 2),"
                    + "('50% off_now', 'C:\\temp', null, 3)");
            st.execute("create table other (id integer primary key, note text)");
            st.execute("insert into other (note) values ('nothing here')");
            st.execute("create view v_template as select body from template");
        }
        config = new ConnectionConfig();
        config.setName("shop");
        config.setType(DbType.SQLITE);
        config.setDatabase(file.toString());
        session = DatabaseSession.open(config);
    }

    @AfterEach
    void close() {
        session.close();
    }

    private List<ScanTable> candidates() throws Exception {
        return DataScan.candidates(session, session.listNamespaces());
    }

    /** Runs the scan as the app does: in a Job on a session of its own. */
    private List<TableResult> scan(List<String> values, boolean whole, boolean ignoreCase) throws Exception {
        List<ScanRequest.Target> targets = candidates().stream()
                .map(t -> new ScanRequest.Target(t.table(), t.columns())).toList();
        ScanRequest request = new ScanRequest(values, whole, ignoreCase, targets, 30);
        List<TableResult> seen = new ArrayList<>();
        List<Object> outcome = new ArrayList<>();
        Job.start(List.of(config), DatabaseSession::open, NOW, NOW, (sessions, job) -> DataScan.run(sessions.get(0), request, job, seen::add),
                new Job.Listener<List<TableResult>>() {
                    @Override
                    public void done(List<TableResult> result) {
                        outcome.add(result);
                    }

                    @Override
                    public void failed(Throwable error) {
                        outcome.add(error);
                    }
                });
        assertEquals(1, outcome.size());
        if (outcome.get(0) instanceof Throwable t) throw new AssertionError(t);
        assertEquals(seen, outcome.get(0), "each table is reported as it is done");
        return seen;
    }

    private static ScanFinding finding(List<TableResult> results, String table, String column) {
        return results.stream().filter(r -> r.table().name().equals(table)).flatMap(r -> r.findings().stream())
                .filter(f -> f.column().name().equals(column)).findFirst().orElse(null);
    }

    @Test
    void listsTablesNotViewsWithTheirSearchableColumns() throws Exception {
        List<ScanTable> tables = candidates();
        assertEquals(List.of("other", "template"), tables.stream().map(t -> t.table().name()).sorted().toList());
        ScanTable template = tables.stream().filter(t -> t.table().name().equals("template")).findFirst().orElseThrow();
        assertEquals(List.of("body", "body_eng", "meta"), template.searchableColumns().stream().map(ScanColumn::name).toList());
        assertEquals(ScanColumn.Kind.JSON, template.columns().get(3).kind());
        assertNull(template.estimatedRows(), "SQLite keeps no estimate");
        assertFalse(template.isBig(10));
    }

    @Test
    void countsRowsPerColumnAndValueIgnoringCase() throws Exception {
        List<TableResult> results = scan(List.of("https://cdn/Facebook.png", "Instagram.png"), false, true);
        assertTrue(results.stream().allMatch(TableResult::scanned));
        ScanFinding eng = finding(results, "template", "body_eng");
        assertEquals(2, eng.rows());
        assertEquals(List.of(1L, 1L), eng.valueRows());
        assertEquals(List.of(0L, 1L), eng.exactRows(), "facebook.png is there only in another case");
        assertEquals(List.of(1L, 0L), finding(results, "template", "body").valueRows());
        // stored JSON-escaped: found only when typed that way
        assertNull(finding(results, "template", "meta"));
        assertNull(finding(results, "other", "note"), "a column without a value is no finding");
        assertEquals(1, scan(List.of("https:\\/\\/cdn\\/Facebook.png"), false, true).stream()
                .flatMap(r -> r.findings().stream()).filter(f -> f.column().name().equals("meta")).count());
    }

    @Test
    void exactCaseAndWholeValue() throws Exception {
        assertEquals(List.of(1L), finding(scan(List.of("Facebook"), false, false), "template", "body").valueRows());
        assertNull(finding(scan(List.of("Facebook"), false, false), "template", "body_eng"), "facebook ≠ Facebook");
        assertNull(finding(scan(List.of("https://cdn"), true, true), "template", "body_eng"), "whole value: not a part of one");
        assertEquals(1, finding(scan(List.of("HTTPS://CDN/Instagram.png"), true, true), "template", "body_eng").rows());
    }

    /** % _ ! and \ in a value match only themselves. */
    @Test
    void typedCharactersMatchOnlyThemselves() throws Exception {
        assertEquals(1, finding(scan(List.of("50% off_"), false, true), "template", "body").rows());
        assertNull(finding(scan(List.of("50%_off"), false, true), "template", "body"));
        assertNull(finding(scan(List.of("no_icon"), false, true), "template", "body"), "_ is not a wildcard");
        assertEquals(1, finding(scan(List.of("C:\\temp"), false, true), "template", "body_eng").rows());
        assertEquals(1, finding(scan(List.of("C:\\temp"), false, false), "template", "body_eng").rows());
    }

    @Test
    void aTableThatFailsIsReportedNotScanned() throws Exception {
        TableRef gone = new TableRef(null, null, "gone", "TABLE");
        ScanRequest request = new ScanRequest(List.of("x"), false, true,
                List.of(new ScanRequest.Target(gone, List.of(new ScanColumn("a", "text", ScanColumn.Kind.TEXT)))), 30);
        List<Object> outcome = new ArrayList<>();
        Job.start(List.of(config), DatabaseSession::open, NOW, NOW, (sessions, job) -> DataScan.run(sessions.get(0), request, job, r -> { }),
                new Job.Listener<List<TableResult>>() {
                    @Override
                    public void done(List<TableResult> result) {
                        outcome.addAll(result);
                    }

                    @Override
                    public void failed(Throwable error) {
                        outcome.add(error);
                    }
                });
        TableResult r = (TableResult) outcome.get(0);
        assertFalse(r.scanned());
        assertTrue(r.notScanned().contains("gone"), r.notScanned());
    }

    @Test
    void valuesAreSentAsParametersAndWideTablesSplit() {
        TableRef t = new TableRef("shop", "app", "template", "TABLE");
        List<ScanColumn> cols = List.of(new ScanColumn("body", "text", ScanColumn.Kind.TEXT), new ScanColumn("meta", "jsonb", ScanColumn.Kind.JSON));
        DataScan.CountQuery q = DataScan.countQueries(DbType.POSTGRESQL, t, cols, List.of("it's 100%"), false, true).get(0);
        assertEquals("SELECT count(*) FILTER (WHERE (CAST(\"body\" AS text) ILIKE CAST(? AS text) ESCAPE '!')), "
                + "count(*) FILTER (WHERE CAST(\"body\" AS text) ILIKE CAST(? AS text) ESCAPE '!'), "
                + "count(*) FILTER (WHERE strpos(CAST(\"body\" AS text), CAST(? AS text)) > 0), ", q.sql().substring(0, q.sql().indexOf("count(*) FILTER (WHERE (CAST(\"meta\"")));
        assertFalse(q.sql().contains("it's"), "the value is a parameter");
        assertTrue(q.sql().endsWith("FROM \"app\".\"template\" WHERE (CAST(\"body\" AS text) ILIKE CAST(? AS text) ESCAPE '!') "
                + "OR (CAST(\"meta\" AS text) ILIKE CAST(? AS text) ESCAPE '!')"), q.sql());
        assertEquals("%it's 100!%%", q.params().get(0));
        assertEquals("it's 100%", q.params().get(2), "exact case: the value itself");
        assertEquals(q.sql().chars().filter(ch -> ch == '?').count(), q.params().size());

        List<ScanColumn> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) many.add(new ScanColumn("c" + i, "text", ScanColumn.Kind.TEXT));
        List<DataScan.CountQuery> split = DataScan.countQueries(DbType.MYSQL, t, many, List.of("a", "b"), false, true);
        assertEquals(2, split.size());
        assertEquals(300, split.stream().mapToInt(x -> x.columns().size()).sum());
    }
}
