package com.dbadmin.service;

import com.dbadmin.model.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSplitterTest {

    private static List<String> pg(String script) {
        return SqlSplitter.split(script, DbType.POSTGRESQL);
    }

    @Test
    void splitsOnSemicolons() {
        assertEquals(List.of("select 1", "select 2"), pg("select 1;\n  select 2 ;"));
    }

    @Test
    void ignoresSemicolonsInQuotesAndComments() {
        assertEquals(List.of("select ';', \"a;b\"", "select 2"), pg("select ';', \"a;b\"; select 2"));
        assertEquals(List.of("select 'a''b;c'", "select 2"), pg("select 'a''b;c'; select 2"));
        assertEquals(List.of("select /* ; */ 1"), pg("select /* ; */ 1"));
        assertEquals(List.of("-- a; b\nselect 1"), pg("-- a; b\nselect 1;"));
    }

    @Test
    void dropsCommentOnlyFragments() {
        assertEquals(List.of("select 1"), pg("select 1; -- the end"));
        assertEquals(List.of("select 1"), pg("/* header */ ; select 1"));
        assertEquals(List.of(), pg("  ;; -- nothing\n"));
    }

    @Test
    void keepsPostgresDollarQuotedBodiesWhole() {
        String function = "create function f() returns int as $body$ begin return 1; end $body$ language plpgsql";
        assertEquals(List.of(function, "select 2"), pg(function + "; select 2"));
        assertEquals(List.of("do $$ begin perform 1; end $$"), pg("do $$ begin perform 1; end $$;"));
        // MySQL has no dollar quotes: the body's ';' splits it
        assertEquals(3, SqlSplitter.split("do $$ begin perform 1; end $$; select 2", DbType.MYSQL).size());
    }

    @Test
    void mysqlBackslashEscapesAndHashComments() {
        assertEquals(List.of("select 'it\\'s; ok'", "select 2"),
                SqlSplitter.split("select 'it\\'s; ok'; select 2", DbType.MYSQL));
        assertEquals(List.of("# a; b\nselect 1"), SqlSplitter.split("# a; b\nselect 1", DbType.MYSQL));
    }

    @Test
    void statementPositions() {
        List<SqlSplitter.Statement> s = SqlSplitter.statements("select 1;\n  select 2;", DbType.POSTGRESQL);
        assertEquals(new SqlSplitter.Statement(0, 8, "select 1"), s.get(0));
        assertEquals(new SqlSplitter.Statement(12, 20, "select 2"), s.get(1));
    }

    @Test
    void statementAtCaret() {
        String script = "select 1;\n\nselect 2;";
        assertEquals("select 1", SqlSplitter.statementAt(script, 0, DbType.POSTGRESQL).sql());
        assertEquals("select 1", SqlSplitter.statementAt(script, 9, DbType.POSTGRESQL).sql()); // right after ';'
        assertEquals("select 1", SqlSplitter.statementAt(script, 10, DbType.POSTGRESQL).sql()); // blank line: the one above
        assertEquals("select 2", SqlSplitter.statementAt(script, 11, DbType.POSTGRESQL).sql());
        assertEquals("select 2", SqlSplitter.statementAt(script, script.length(), DbType.POSTGRESQL).sql());
        assertNull(SqlSplitter.statementAt("-- only a comment", 3, DbType.POSTGRESQL));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select * from t",
            "SELECT 1",
            "with x as (select 1) select * from x",
            "  -- comment\nselect 1",
            "/* c */ values (1), (2)",
            "explain select * from t",
            "show tables",
    })
    void readOnlyQueries(String sql) {
        assertTrue(SqlSplitter.isReadOnlyQuery(sql), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "insert into t values (1)",
            "update t set a = 1",
            "delete from t",
            "select * into t2 from t",
            "select * from t for update",
            "select nextval('seq')",
            "with d as (delete from t returning *) select * from d",
            "drop table t",
    })
    void queriesThatMayWrite(String sql) {
        assertFalse(SqlSplitter.isReadOnlyQuery(sql), sql);
    }
}
