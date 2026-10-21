package com.dbadmin.service;

import com.dbadmin.model.DbType;
import com.dbadmin.service.NamedParams.ParsedSql;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NamedParamsTest {

    private static ParsedSql pg(String sql) {
        return NamedParams.parse(sql, DbType.POSTGRESQL);
    }

    @Test
    void rewritesNamesToPlaceholders() {
        ParsedSql p = pg("select * from t where id = :id and name = :user_name2");
        assertEquals("select * from t where id = ? and name = ?", p.sql());
        assertEquals(List.of("id", "user_name2"), p.names());
    }

    @Test
    void aNameUsedTwiceGetsTwoPlaceholders() {
        ParsedSql p = pg("where a = :id or b = :id");
        assertEquals("where a = ? or b = ?", p.sql());
        assertEquals(List.of("id", "id"), p.names());
    }

    @Test
    void leavesPostgresCastsAlone() {
        ParsedSql p = pg("select x::int, :v::text");
        assertEquals("select x::int, ?::text", p.sql());
        assertEquals(List.of("v"), p.names());
    }

    @Test
    void ignoresNamesInQuotesAndComments() {
        ParsedSql p = pg("select ':no', \":nope\" -- :line\n/* :block */ , :yes");
        assertEquals("select ':no', \":nope\" -- :line\n/* :block */ , ?", p.sql());
        assertEquals(List.of("yes"), p.names());
    }

    @Test
    void ignoresNamesInDollarQuotedBodies() {
        ParsedSql p = pg("do $$ begin perform :inside; end $$");
        assertEquals("do $$ begin perform :inside; end $$", p.sql());
        assertEquals(List.of(), p.names());
    }

    @Test
    void aColonAfterAWordIsNotAParameter() {
        assertEquals(List.of(), pg("select arr[1:n], a:b").names());
    }

    @Test
    void leavesMysqlAssignmentsAlone() {
        ParsedSql p = NamedParams.parse("set @a := 1; select @a + :b # :c", DbType.MYSQL);
        assertEquals("set @a := 1; select @a + ? # :c", p.sql());
        assertEquals(List.of("b"), p.names());
    }
}
