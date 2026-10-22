package com.dbadmin.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchBoxTest {

    private static final List<String> TABLES = List.of("m_product", "m_product_variant", "m_product_publish",
            "t_transaction_order", "bo_user_portal", "mpv_archive");

    private static List<String> find(String text) {
        return SearchBox.matching(TABLES, Function.identity(), text);
    }

    @Test
    void findsTablesByTheirInitials() {
        assertEquals(List.of("mpv_archive", "m_product_variant"), find("mpv"));
        assertEquals(List.of("t_transaction_order"), find("tto"));
        assertEquals(List.of("mpv_archive", "m_product", "m_product_variant", "m_product_publish"), find("mp"));
    }

    @Test
    void stillFindsByPartOfTheName() {
        assertEquals(List.of("m_product_variant"), find("variant"));
        assertEquals(TABLES, find(""));
    }
}
