package com.dbadmin.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnPickerTest {

    private static ColumnPicker.Choice find(List<ColumnPicker.Choice> list, String name) {
        return list.stream().filter(c -> c.name.equals(name)).findFirst().orElseThrow();
    }

    /** The source's columns in order, then those only in the target; nothing ticked for a new table. */
    @Test
    void listsBothSides() {
        List<ColumnPicker.Choice> list = ColumnPicker.merged(List.of(), List.of("id", "name", "old"), List.of("ID", "name", "new"));
        assertEquals(List.of("id", "name", "old", "new"), list.stream().map(c -> c.name).toList());
        assertTrue(find(list, "id").onBothSides(), "names match without case");
        assertFalse(find(list, "old").onBothSides());
        assertFalse(find(list, "new").inSource);
        assertTrue(list.stream().noneMatch(c -> c.key.get() || c.compare.get() || c.show.get()));
    }

    /** Ticks for the same names are kept (a join added, a compare reopened); a column on one side only is never ticked. */
    @Test
    void keepsTicksByName() {
        List<ColumnPicker.Choice> before = ColumnPicker.merged(List.of(), List.of("id", "price", "note"), List.of("id", "price", "note"));
        find(before, "id").key.set(true);
        find(before, "price").compare.set(true);
        find(before, "note").show.set(true);

        List<ColumnPicker.Choice> after = ColumnPicker.merged(before, List.of("id", "price", "note", "mpv.code"),
                List.of("ID", "price", "mpv.code"));
        assertTrue(find(after, "id").key.get());
        assertTrue(find(after, "price").compare.get());
        assertFalse(find(after, "note").show.get(), "now only in the source");
        assertTrue(find(after, "mpv.code").joined);
        assertFalse(find(after, "mpv.code").key.get());
    }
}
