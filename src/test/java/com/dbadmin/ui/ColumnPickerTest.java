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

    @SuppressWarnings("unchecked")
    private static List<ColumnPicker.Choice> choices(ColumnPicker picker) throws Exception {
        var f = ColumnPicker.class.getDeclaredField("choices");
        f.setAccessible(true);
        return (List<ColumnPicker.Choice>) f.get(picker);
    }

    private static javafx.scene.control.CheckBox withoutKey(ColumnPicker picker) {
        return (javafx.scene.control.CheckBox) picker.lookupAll(".check-box").stream()
                .filter(n -> ((javafx.scene.control.CheckBox) n).getText().startsWith("Leave out")).findFirst().orElseThrow();
    }

    private static void press(ColumnPicker picker, String button) {
        picker.lookupAll(".button").stream().map(n -> (javafx.scene.control.Button) n)
                .filter(b -> b.getText().equals(button)).findFirst().orElseThrow().fire();
    }

    /** Leave out the primary key: the key is neither Key nor compared, also by Compare all; ticking it by hand turns it off. */
    @Test
    void leavesOutThePrimaryKey() throws Exception {
        Fx.run(() -> {
            try {
                ColumnPicker picker = new ColumnPicker();
                javafx.scene.control.CheckBox without = withoutKey(picker);
                assertTrue(without.isDisabled(), "no table yet");
                picker.setPrimaryKey(List.of("id"));
                picker.setColumns(List.of("id", "code", "name"), List.of("id", "code", "name"));
                assertFalse(without.isDisabled());
                assertEquals("Leave out the primary key (id)", without.getText());
                find(choices(picker), "id").key.set(true);
                press(picker, "Compare all");
                assertEquals(List.of("id"), picker.keys());
                assertEquals(List.of("code", "name"), picker.compared());

                without.setSelected(true);
                assertEquals(List.of(), picker.keys());
                find(choices(picker), "code").key.set(true);
                press(picker, "Compare all");
                assertEquals(List.of("code"), picker.keys());
                assertEquals(List.of("name"), picker.compared(), "Compare all skips the primary key");

                find(choices(picker), "id").compare.set(true);
                assertFalse(without.isSelected(), "the key ticked by hand: the option is off again");

                picker.setPrimaryKey(List.of());
                assertTrue(without.isDisabled(), "a table without a primary key");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

}
