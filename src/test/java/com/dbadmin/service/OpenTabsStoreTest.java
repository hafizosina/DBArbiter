package com.dbadmin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenTabsStoreTest {

    @TempDir
    Path tmp;

    @Test
    void keepsTheTabsAndForgetsThemWhenNoneAreOpen() {
        Path file = tmp.resolve("open-tabs.json");
        OpenTabsStore store = new OpenTabsStore(file);
        assertEquals(List.of(), store.load());
        List<OpenTabsStore.OpenTab> tabs = List.of(
                new OpenTabsStore.OpenTab("SCRIPT", "/x/a.sql", "c1", null, null, null, null, false),
                new OpenTabsStore.OpenTab("TABLE", null, "c1", null, "public", "m_product", "TABLE", true));
        store.save(tabs);
        assertEquals(tabs, new OpenTabsStore(file).load());
        store.save(List.of());
        assertFalse(Files.exists(file));
    }
}
