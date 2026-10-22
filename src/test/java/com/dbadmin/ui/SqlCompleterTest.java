package com.dbadmin.ui;

import com.dbadmin.ui.SqlCompleter.Kind;
import com.dbadmin.ui.SqlCompleter.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlCompleterTest {

    private static final List<Suggestion> ALL = List.of(
            new Suggestion("m_product", Kind.TABLE, "table"),
            new Suggestion("m_product_variant", Kind.TABLE, "table"),
            new Suggestion("t_transaction_order", Kind.TABLE, "table"),
            new Suggestion("bo_user_portal", Kind.TABLE, "table"),
            new Suggestion("bo_user_path", Kind.VIEW, "view"),
            new Suggestion("tto_archive", Kind.TABLE, "table"),
            new Suggestion("BETWEEN", Kind.KEYWORD, null),
            new Suggestion("bup_col", Kind.COLUMN, "int"));

    private static List<String> names(String typed) {
        return SqlCompleter.filter(ALL, typed).stream().map(Suggestion::text).toList();
    }

    @Test
    void findsTablesByTheirInitials() {
        assertEquals(List.of("m_product_variant"), names("mpv"));
        // fewer letters than words: every table whose initials start with them
        assertEquals(List.of("m_product", "m_product_variant"), names("mp"));
    }

    @Test
    void namePrefixComesBeforeInitials() {
        assertEquals(List.of("tto_archive", "t_transaction_order"), names("tto"));
        assertEquals(List.of("bup_col", "bo_user_portal", "bo_user_path"), names("bup"));
    }

    @Test
    void initialsMatchOnlyTablesAndViews() {
        // "bet" are not the initials of anything; BETWEEN matches by name only
        assertEquals(List.of("BETWEEN"), names("bet"));
        assertEquals(List.of(), names("bc"));
    }

    @Test
    void initialsAreThoseOfTheAlias() {
        assertEquals("mpv", SqlCompleter.initials("m_product_variant"));
        assertEquals("to", SqlCompleter.initials("TransactionOrder"));
        assertEquals("mpv", SqlCompleter.aliasFor("m_product_variant", Set.of()));
        assertEquals("mpv2", SqlCompleter.aliasFor("m_product_variant", Set.of("mpv")));
    }
}
