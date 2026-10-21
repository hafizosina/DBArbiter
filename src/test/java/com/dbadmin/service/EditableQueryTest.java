package com.dbadmin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditableQueryTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "select * from users",
            "select u.* from shop.users u where id > 3 order by id",
            "select id, name, email from users",
            "select u.id, u.\"Name\" from users u",
            "select `id`, `name` from users",
            "SELECT id, name FROM users WHERE id IN (select user_id from orders) LIMIT 10;",
            "select *\nfrom users\nwhere id = 1",
            "select\n  id,\n  name\nfrom\n  users",
    })
    void editable(String sql) {
        assertTrue(EditableQuery.source(sql).isPresent(), sql);
    }

    /** Each of these would send an edit to the wrong column or row, or the result has no single table. */
    @ParameterizedTest
    @ValueSource(strings = {
            "select id, name as email from users",
            "select id, name email from users",
            "select id + 1 as id, name from users",
            "select id, upper(name) from users",
            "select id, id from users",
            "select id, ID from users",
            "select *, name from users",
            "select id, current_date from users",
            "select id, (select count(*) from orders) from users",
            "select distinct id from users",
            "select u.id from users u join orders o on o.user_id = u.id",
            "select a.id from a, b",
            "select status, count(*) from users group by status",
            "update users set name = 'x'",
    })
    void notEditable(String sql) {
        assertTrue(EditableQuery.source(sql).isEmpty(), sql);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "select id, name as email from users          | \"name as email\" renames a column",
            "select id, name email from users             | \"name email\" renames a column",
            "select id + 1 as id from users               | \"id + 1 as id\" is not a plain column",
            "select count(*) from users                   | \"count(*)\" is not a plain column",
            "select id, ID from users                     | column \"ID\" is listed twice",
            "select *, name from users                    | * is combined with other columns",
            "select id, current_date from users           | \"current_date\" is not a column",
            "select distinct id from users                | the query uses DISTINCT",
            "select a.id from a join b on a.id = b.a_id   | the query uses JOIN",
            "select status from users group  by status    | the query uses GROUP BY",
            "select a.id from a, b                        | the query reads from more than one table",
            "select * from generate_series(1, 3)          | only a plain SELECT from one table (with WHERE, ORDER BY or LIMIT) can be edited",
            "select * from \"my schema\".t, b              | the query reads from more than one table",
    })
    void saysWhyNot(String sql, String reason) {
        EditableQuery.Check check = EditableQuery.check(sql);
        assertNull(check.source());
        assertEquals(reason, check.reason());
    }

    /** Nothing is read from a table, so there is nothing to explain. */
    @ParameterizedTest
    @ValueSource(strings = {"select 1", "select now()", "show tables", "update users set a = 1 from b", "explain select * from t"})
    void noReasonWithoutATable(String sql) {
        assertEquals(new EditableQuery.Check(null, null), EditableQuery.check(sql));
    }

    @Test
    void sourceTable() {
        assertEquals(new EditableQuery.Source(null, "users"), EditableQuery.source("select * from users").orElseThrow());
        assertEquals(new EditableQuery.Source("shop", "Users"),
                EditableQuery.source("select id from \"shop\".\"Users\" u").orElseThrow());
    }
}
