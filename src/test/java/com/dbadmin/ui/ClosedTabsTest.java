package com.dbadmin.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClosedTabsTest {

    @Test
    void reopensNewestFirstAndForgetsAfterTheTime() {
        long[] now = {0};
        ClosedTabs closed = new ClosedTabs(10 * 60_000, () -> now[0]);
        List<String> opened = new ArrayList<>();
        closed.add("a.sql", () -> opened.add("a.sql"));
        now[0] = 5 * 60_000;
        closed.add("m_product", () -> opened.add("m_product"));
        assertEquals(Optional.of("m_product"), closed.lastTitle());
        assertTrue(closed.reopenLast());
        assertEquals(List.of("m_product"), opened);

        now[0] = 11 * 60_000; // a.sql was closed 11 minutes ago
        assertEquals(Optional.empty(), closed.lastTitle());
        assertFalse(closed.reopenLast());
        assertEquals(List.of("m_product"), opened);
    }
}
