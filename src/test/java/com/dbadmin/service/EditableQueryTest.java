package com.dbadmin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void sourceTable() {
        assertEquals(new EditableQuery.Source(null, "users"), EditableQuery.source("select * from users").orElseThrow());
        assertEquals(new EditableQuery.Source("shop", "Users"),
                EditableQuery.source("select id from \"shop\".\"Users\" u").orElseThrow());
    }
}
