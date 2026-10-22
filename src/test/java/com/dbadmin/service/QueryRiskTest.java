package com.dbadmin.service;

import com.dbadmin.model.DbType;
import com.dbadmin.service.QueryRisk.Risk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryRiskTest {

    private static Risk risk(String sql) {
        return QueryRisk.assess(sql, DbType.POSTGRESQL).risk();
    }

    @Test
    void readsAndSessionStatementsAreSafe() {
        assertEquals(Risk.NONE, risk("select * from orders"));
        assertEquals(Risk.NONE, risk("select * from t where note = 'please delete this' -- drop table t"));
        assertEquals(Risk.NONE, risk("select updated_at from t"));
        assertEquals(Risk.NONE, risk("with x as (select 1) select * from x"));
        assertEquals(Risk.NONE, risk("set search_path to app"));
        assertEquals(Risk.NONE, risk("begin"));
        assertEquals(Risk.NONE, risk("  "));
    }

    @Test
    void dangerIsWhatHitsEveryRowOrAWholeObject() {
        assertEquals(Risk.DANGER, risk("drop table orders"));
        assertEquals(Risk.DANGER, risk("truncate orders"));
        assertEquals(Risk.DANGER, risk("delete from orders"));
        assertEquals(Risk.DANGER, risk("update orders set paid = true"));
        // a WHERE inside a subquery does not limit the update
        assertEquals(Risk.DANGER, risk("update orders set total = (select sum(x) from lines where lines.o = 1)"));
        assertEquals(Risk.DANGER, risk("alter table orders drop column note"));
        assertEquals("deletes every row (no WHERE)", QueryRisk.assess("DELETE FROM orders", null).reason());
        assertEquals("drops a table", QueryRisk.assess("DROP TABLE orders", null).reason());
    }

    @Test
    void changesAndInserts() {
        assertEquals(Risk.CHANGE, risk("delete from orders where id = 1"));
        assertEquals(Risk.CHANGE, risk("update orders set paid = true where id = 1"));
        assertEquals(Risk.CHANGE, risk("with old as (select id from o) delete from o where id in (select id from old)"));
        assertEquals(Risk.CHANGE, risk("with d as (delete from o returning *) select * from d"));
        assertEquals(Risk.CHANGE, risk("select * into backup from orders"));
        assertEquals(Risk.CHANGE, risk("alter table orders add column note text"));
        assertEquals(Risk.CHANGE, risk("create table t (id int)"));
        assertEquals(Risk.CHANGE, risk("do $$ begin delete from t; end $$"));
        assertEquals(Risk.CHANGE, risk("vacuum orders"), "unknown statements count as a change");
        assertEquals(Risk.INSERT, risk("insert into orders (id) values (1)"));
        assertEquals(Risk.INSERT, risk("insert into orders select * from staging"));
    }

    @Test
    void levelsWarnFromTheMostDangerousDown() {
        assertFalse(QueryRisk.warns(0, Risk.DANGER));
        assertTrue(QueryRisk.warns(1, Risk.DANGER));
        assertFalse(QueryRisk.warns(1, Risk.CHANGE));
        assertTrue(QueryRisk.warns(2, Risk.CHANGE));
        assertFalse(QueryRisk.warns(2, Risk.INSERT));
        assertTrue(QueryRisk.warns(3, Risk.INSERT));
        assertFalse(QueryRisk.warns(3, Risk.NONE));
    }
}
