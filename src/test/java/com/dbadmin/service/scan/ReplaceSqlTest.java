package com.dbadmin.service.scan;

import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.scan.ReplaceSql.AlsoSet;
import com.dbadmin.service.scan.ReplaceSql.Pair;
import com.dbadmin.service.scan.ReplaceSql.Plan;
import com.dbadmin.service.scan.ReplaceSql.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaceSqlTest {

    private static final TableRef TEMPLATE = new TableRef("shop", "brankas_mobile", "m_notification_template", "TABLE");
    private static final ScanColumn CONTENT = new ScanColumn("message_content", "text", ScanColumn.Kind.TEXT);
    private static final ScanColumn META = new ScanColumn("meta", "jsonb", ScanColumn.Kind.JSON);
    private static final Set<String> COLUMNS = Set.of("id", "message_content", "meta", "modified_date", "modified_by");

    private static Plan plan(List<AlsoSet> also) {
        return new Plan(List.of(new Pair("Facebook", "https://s3/Facebook.png", "https://s3/new/facebook.png"),
                new Pair("", "https://s3/X.png", "https://s3/new/x.png")),
                List.of(new Target(TEMPLATE, List.of(CONTENT, META), COLUMNS)), also);
    }

    @Test
    void postgresIsOneBlockWithVariablesAndACountPerTable() {
        String sql = ReplaceSql.script(DbType.POSTGRESQL, plan(List.of(new AlsoSet("modified_date", "NOW()"),
                new AlsoSet("MODIFIED_BY", "'SYSTEM'"), new AlsoSet("updated_at", "NOW()"))));
        assertTrue(sql.contains("DO $$\nDECLARE\n    -- OLD\n    old_facebook TEXT := 'https://s3/Facebook.png';\n    old_2"), sql);
        assertTrue(sql.contains("\"message_content\" = REPLACE(REPLACE(\"message_content\", old_facebook, new_facebook), old_2, new_2)"), sql);
        assertTrue(sql.contains("\"meta\" = CAST(REPLACE(REPLACE(CAST(\"meta\" AS text), old_facebook, new_facebook), old_2, new_2) AS jsonb)"), sql);
        assertTrue(sql.contains("\"modified_date\" = NOW(),\n        \"modified_by\" = 'SYSTEM'\n"), "set by the table's own spelling; updated_at is not in the table:\n" + sql);
        assertFalse(sql.contains("updated_at"));
        assertTrue(sql.contains("WHERE strpos(\"message_content\", old_facebook) > 0\n       OR strpos(\"message_content\", old_2) > 0"), sql);
        assertTrue(sql.contains("RAISE NOTICE 'brankas_mobile.m_notification_template: updated % row(s)', v_rows;"), sql);
        assertTrue(sql.endsWith("END $$;\n"));
    }

    @Test
    void mysqlAndSqliteWriteTheirOwnStatements() {
        String my = ReplaceSql.script(DbType.MYSQL, new Plan(List.of(new Pair("a", "C:\\old's", "C:\\new")),
                List.of(new Target(new TableRef("shop", null, "t", "TABLE"), List.of(CONTENT), Set.of("message_content"))), List.of()));
        assertTrue(my.contains("SET @old_a := 'C:\\\\old''s';"), my);
        assertTrue(my.contains("INSTR(CAST(`message_content` AS BINARY), CAST(@old_a AS BINARY)) > 0"), my);
        assertTrue(my.startsWith("-- Replace") && my.contains("START TRANSACTION;") && my.endsWith("COMMIT;\n"));
        String lite = ReplaceSql.script(DbType.SQLITE, new Plan(List.of(new Pair("", "a", "b")),
                List.of(new Target(new TableRef(null, null, "t", "TABLE"), List.of(CONTENT), Set.of())), List.of()));
        assertTrue(lite.contains("\"message_content\" = REPLACE(\"message_content\", 'a', 'b')\nWHERE instr(\"message_content\", 'a') > 0;"), lite);
    }

    @Test
    void namesAndOrder() {
        List<Pair> pairs = List.of(new Pair("x", "a", "1"), new Pair("x", "abc", "2"), new Pair("9lives", "q", "3"));
        assertEquals(List.of("abc", "a", "q"), ReplaceSql.ordered(pairs).stream().map(Pair::oldValue).toList());
        assertEquals(List.of("x", "x_2", "3"), ReplaceSql.variableNames(pairs, List.of()));
        // a variable must not be named like a column being changed
        assertEquals(List.of("x_2"), ReplaceSql.variableNames(List.of(new Pair("x", "a", "b")),
                List.of(new Target(TEMPLATE, List.of(CONTENT), Set.of("old_x")))));
        assertEquals("abc 2 q", ReplaceSql.apply("abc 2 q", List.of()));
        assertEquals("2 1", ReplaceSql.apply("abc a", pairs), "the longer one first");
        assertEquals(List.of("The new value \"abc!\" holds \"abc\": running the script again would change it again.",
                "\"a\" is part of \"abc\": the longer one is replaced first.",
                "The new value \"abc!\" holds \"a\": running the script again would change it again."),
                ReplaceSql.checks(List.of(new Pair("", "abc", "abc!"), new Pair("", "a", "b"))));
        assertEquals(30, ReplaceSql.declaredLength("character varying(30)"));
        assertEquals(-1, ReplaceSql.declaredLength("text"));
        assertThrows(IllegalArgumentException.class, () -> ReplaceSql.script(DbType.POSTGRESQL, new Plan(List.of(), plan(List.of()).targets(), List.of())));
        assertTrue(ReplaceSql.script(DbType.POSTGRESQL, new Plan(List.of(new Pair("", "a$$b", "c")), plan(List.of()).targets(), List.of()))
                .startsWith("-- Replace"), "a value holding $$ gets another tag");
        assertTrue(ReplaceSql.script(DbType.POSTGRESQL, new Plan(List.of(new Pair("", "a$$b", "c")), plan(List.of()).targets(), List.of()))
                .contains("DO $replace$"));
        assertEquals(List.of("updated_at is not in brankas_mobile.m_notification_template: not set there"),
                ReplaceSql.alsoSetMissing(plan(List.of(new AlsoSet("updated_at", "NOW()"), new AlsoSet("modified_by", "'x'")))));
    }

    /** The SQLite script runs: values replaced, Also set filled, and a second run changes nothing. */
    @Test
    void theScriptRunsAndASecondRunChangesNothing(@TempDir Path tmp) throws Exception {
        String url = "jdbc:sqlite:" + tmp.resolve("t.db");
        try (var c = DriverManager.getConnection(url); var st = c.createStatement()) {
            st.execute("create table t (id integer primary key, body text, modified_by text)");
            st.execute("insert into t (body) values ('<img src=\"https://s3/X.png\"> https://s3/X.png'), ('nothing'), ('https://s3/x.png')");
        }
        TableRef t = new TableRef(null, null, "t", "TABLE");
        Plan p = new Plan(List.of(new Pair("x", "https://s3/X.png", "https://s3/new/x.png")),
                List.of(new Target(t, List.of(new ScanColumn("body", "text", ScanColumn.Kind.TEXT)), Set.of("id", "body", "modified_by"))),
                List.of(new AlsoSet("modified_by", "'SYSTEM'")));
        String script = ReplaceSql.script(DbType.SQLITE, p);
        List<String> counts = new ArrayList<>();
        List<Integer> updated = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            try (var c = DriverManager.getConnection(url); var st = c.createStatement()) {
                for (String statement : com.dbadmin.service.SqlSplitter.split(script, DbType.SQLITE)) {
                    if (st.execute(statement)) counts.add("a result");
                    else if (statement.contains("\nSET ")) updated.add(st.getUpdateCount());
                }
            }
        }
        assertEquals(List.of(1, 0), updated, "one row changed, then none");
        assertEquals(List.of(), counts, "no SELECT: the script tab shows each UPDATE's count");
        try (var c = DriverManager.getConnection(url); var st = c.createStatement(); var rs = st.executeQuery("select body, modified_by from t order by id")) {
            rs.next();
            assertEquals("<img src=\"https://s3/new/x.png\"> https://s3/new/x.png", rs.getString(1));
            assertEquals("SYSTEM", rs.getString(2));
            rs.next();
            assertEquals(null, rs.getString(2), "a row without the value is not touched");
            rs.next();
            assertEquals("https://s3/x.png", rs.getString(1), "another case is not replaced");
        }
    }
}
