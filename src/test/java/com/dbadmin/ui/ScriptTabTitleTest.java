package com.dbadmin.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptTabTitleTest {

    @Test
    void theTabShowsTheFirstFourCharactersOfTheConnection() {
        assertEquals("03 A", ScriptTab.shortName("03 Antam PreProd"));
        assertEquals("Loca", ScriptTab.shortName("  Local"));
        assertEquals("UAT", ScriptTab.shortName("UAT"));
    }
}
