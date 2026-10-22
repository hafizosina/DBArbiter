package com.dbadmin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckTest {

    @Test
    void comparesNumberByNumber() {
        assertTrue(UpdateCheck.isNewer("0.1.20", "0.1.9"));
        assertTrue(UpdateCheck.isNewer("0.2.0", "0.1.99"));
        assertTrue(UpdateCheck.isNewer("1.0", "0.9.9"));
        assertFalse(UpdateCheck.isNewer("0.1.18", "0.1.18"));
        assertFalse(UpdateCheck.isNewer("0.1.17", "0.1.18"));
        assertFalse(UpdateCheck.isNewer("0.1", "0.1.0"));
    }

    /** A dev build ("dev") or an odd tag never counts as an update. */
    @Test
    void ignoresWhatIsNotAVersion() {
        assertFalse(UpdateCheck.isNewer("0.1.20", "dev"));
        assertFalse(UpdateCheck.isNewer("latest", "0.1.18"));
        assertFalse(UpdateCheck.isNewer("", "0.1.18"));
        assertFalse(UpdateCheck.isNewer(null, "0.1.18"));
    }
}
