package com.dbadmin.ui;

import com.dbadmin.service.DatabaseSession.RowUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingEditsTest {

    private final PendingEdits edits = new PendingEdits();

    private static List<Object> row(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    @Test
    void anEditChangesTheRowAndCanBeReverted() {
        List<Object> r = row(1, "a", "x");
        edits.set(r, 1, "b");
        assertEquals(row(1, "b", "x"), r);
        assertTrue(edits.isEdited(r, 1));
        assertFalse(edits.isEdited(r, 2));
        edits.revert(r, 1);
        assertEquals(row(1, "a", "x"), r);
        assertTrue(edits.isEmpty());
    }

    @Test
    void editingBackToTheOriginalIsNoEdit() {
        List<Object> r = row(1, "a");
        edits.set(r, 1, "b");
        edits.set(r, 1, "c");
        edits.set(r, 1, "a");
        assertTrue(edits.isEmpty());
        assertEquals(row(1, "a"), r);
    }

    @Test
    void theOriginalIsKeptOverSeveralEdits() {
        List<Object> r = row(1, "a");
        edits.set(r, 1, "b");
        edits.set(r, 1, "c");
        edits.revert(r, 1);
        assertEquals("a", r.get(1));
    }

    @Test
    void nullAndTextCompareByValue() {
        List<Object> r = row(1, null, 5);
        edits.set(r, 1, null);
        edits.set(r, 2, "5"); // typed text equal to the number shown
        assertTrue(edits.isEmpty());
        edits.set(r, 1, "");
        assertTrue(edits.isEdited(r, 1)); // '' is not NULL
    }

    @Test
    void rowsWithEqualValuesAreSeparate() {
        List<Object> a = row(1, "same");
        List<Object> b = row(1, "same");
        edits.set(a, 1, "changed");
        assertTrue(edits.isEdited(a, 1));
        assertFalse(edits.isEdited(b, 1));
        assertEquals(1, edits.rowCount());
    }

    @Test
    void countsAndRevertAll() {
        List<Object> a = row(1, "a", "x");
        List<Object> b = row(2, "b", "y");
        edits.set(a, 1, "A");
        edits.set(a, 2, "X");
        edits.set(b, 2, "Y");
        assertEquals(2, edits.rowCount());
        assertEquals(3, edits.cellCount());
        edits.revertAll();
        assertEquals(row(1, "a", "x"), a);
        assertEquals(row(2, "b", "y"), b);
        assertTrue(edits.isEmpty());
    }

    @Test
    void clearKeepsTheNewValues() {
        List<Object> r = row(1, "a");
        edits.set(r, 1, "b");
        edits.clear(); // e.g. after saving
        assertTrue(edits.isEmpty());
        assertEquals("b", r.get(1));
    }

    @Test
    void updatesUseTheKeyAndTheEditedColumnsInOrder() {
        List<Object> r = row("x", 7, "old name", "old mail");
        edits.set(r, 3, "new mail");
        edits.set(r, 2, null);
        List<RowUpdate> updates = edits.updates(List.of("code", "ID", "name", "email"), List.of("id"));
        assertEquals(1, updates.size());
        assertEquals(Map.of("id", 7), updates.get(0).key());
        assertEquals(List.of("name", "email"), List.copyOf(updates.get(0).values().keySet()));
        assertEquals(Arrays.asList(null, "new mail"), new ArrayList<>(updates.get(0).values().values()));
    }

    @Test
    void aMissingKeyColumnIsAnError() {
        List<Object> r = row("a");
        edits.set(r, 0, "b");
        assertThrows(IllegalStateException.class, () -> edits.updates(List.of("name"), List.of("id")));
    }
}
