package com.dbadmin.ui;

import com.dbadmin.model.TableRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TableSearchTest {

    private static int rank(String table, String query) {
        return TableSearch.rank(new TableRef(null, "brankas_mobile", table, "TABLE"), query);
    }

    @Test
    void findsTablesByTheirInitials() {
        assertEquals(1, rank("m_product_variant", "mpv"));
        assertEquals(1, rank("bo_user_portal", "bup"));
        assertEquals(TableSearch.NO_MATCH, rank("m_product_tier", "mpv"));
    }

    @Test
    void namesStartingWithTheQueryComeFirst() {
        assertEquals(0, rank("mpv_archive", "mpv"));
        assertEquals(2, rank("m_product_variant", "variant"));
        assertEquals(3, rank("m_product_variant", "brankas_mobile.m_"));
    }
}
