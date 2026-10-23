package com.dbadmin.ui;

import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Screen pieces that need neither a database nor a dialog, on the real JavaFX thread. */
class ScreenPiecesTest {

    /** A tab that keeps itself open when asked to close (as Cancel in "Save changes?" does). */
    private static Tab keepsOpen(String name) {
        Tab t = new Tab(name);
        t.setOnCloseRequest(javafx.event.Event::consume);
        return t;
    }

    private static MenuItem item(Tab tab, String text) {
        return tab.getContextMenu().getItems().stream().filter(i -> text.equals(i.getText())).findFirst().orElseThrow();
    }

    @Test
    void tabMenuClosesOthersAndStopsAtATabThatStaysOpen() throws Exception {
        Fx.run(() -> {
            TabPane pane = new TabPane();
            TabStrip strip = new TabStrip(pane);
            Tab a = new Tab("a");
            Tab b = new Tab("b");
            Tab c = keepsOpen("c");
            Tab d = new Tab("d");
            for (Tab t : List.of(a, b, c, d)) strip.show(t);
            assertEquals(d, pane.getSelectionModel().getSelectedItem());

            item(b, "Close Tabs to the Left").fire();
            assertEquals(List.of(b, c, d), pane.getTabs());
            // Close All stops at c, which stays open; d is left too
            item(b, "Close All").fire();
            assertEquals(List.of(c, d), pane.getTabs());
            strip.close(d);
            assertEquals(List.of(c), pane.getTabs());
        });
    }

    @Test
    void closingTheAppAsksEveryTabWithUnsavedWork() throws Exception {
        Fx.run(() -> {
            TabPane pane = new TabPane();
            TabStrip strip = new TabStrip(pane);
            List<String> asked = new java.util.ArrayList<>();
            class Work extends Tab implements UnsavedWork {
                final boolean keep;

                Work(String name, boolean keep) {
                    super(name);
                    this.keep = keep;
                }

                @Override
                public boolean confirmClose() {
                    asked.add(getText());
                    return !keep;
                }
            }
            strip.show(new Work("saved", false));
            strip.show(new Tab("plain"));
            strip.show(new Work("unsaved", true));
            strip.show(new Work("never asked", false));
            assertFalse(strip.confirmCloseAll(), "Cancel on one keeps the app open");
            assertEquals(List.of("saved", "unsaved"), asked, "stops at the one kept open");
        });
    }

    @Test
    void compareFileKeepsTheTabNameAndSaveButtonInStep() throws Exception {
        Fx.run(() -> {
            Tab tab = new Tab();
            Button save = new Button("Save");
            CompareFile file = new CompareFile(tab, save, "Data Compare");
            assertEquals("Data Compare", tab.getText());
            assertFalse(save.isDisabled(), "no file yet: Save asks where");

            file.at(Path.of("/tmp/x.dbcompare"), false);
            assertEquals("x", tab.getText());
            assertTrue(save.isDisabled());

            file.edited(true);
            assertEquals("*x", tab.getText());
            assertFalse(save.isDisabled());
            assertTrue(file.hasUnsavedEdits());

            file.at(Path.of("/tmp/x.dbcompare"), false); // saved
            file.compared(); // results newer than the results file
            assertEquals("*x", tab.getText());
            assertFalse(file.hasUnsavedEdits());

            file.at(Path.of("/tmp/s.dbcompare"), true); // a settings file
            file.compared();
            assertEquals("s", tab.getText(), "a settings file is not out of date after a compare");
        });
    }

    @Test
    void columnPickerReportsItsTicks() throws Exception {
        Fx.run(() -> {
            ColumnPicker picker = new ColumnPicker();
            picker.restore(List.of("code", "price", "note"), List.of("code"), List.of("price"));
            picker.setColumns(List.of("id", "code", "price", "note", "mpv.name"), List.of("id", "code", "price", "note", "mpv.name"));
            assertEquals(List.of("code"), picker.keys());
            assertEquals(List.of("price"), picker.compared());
            assertEquals(List.of("note"), picker.shownOnly());
            assertTrue(picker.lists(List.of("CODE", "mpv.name")));
            assertFalse(picker.lists(List.of("missing")));
        });
    }

    private static <T extends javafx.scene.Node> List<T> all(javafx.scene.Node root, Class<T> kind) {
        List<T> found = new java.util.ArrayList<>();
        if (kind.isInstance(root)) found.add(kind.cast(root));
        if (root instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) found.addAll(all(sp.getContent(), kind));
        if (root instanceof javafx.scene.Parent p) p.getChildrenUnmodifiable().forEach(n -> found.addAll(all(n, kind)));
        return found;
    }

    /** The warning level is offered only while Read-only is off, and saved with the connection. */
    @Test
    void connectionFormOffersTheWarningLevelWhenWritable() throws Exception {
        Fx.run(() -> {
            com.dbadmin.model.ConnectionConfig c = new com.dbadmin.model.ConnectionConfig();
            c.setName("prod");
            c.setDatabase("shop");
            c.setReadOnly(false);
            c.setWarningLevel(2);
            List<com.dbadmin.model.ConnectionConfig> saved = new java.util.ArrayList<>();
            ConnectionTab tab = new ConnectionTab(c, saved::add);
            javafx.scene.control.CheckBox readOnly = all(tab.getContent(), javafx.scene.control.CheckBox.class).stream()
                    .filter(b -> b.getText().startsWith("Read-only")).findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            javafx.scene.control.ComboBox<String> level = all(tab.getContent(), javafx.scene.control.ComboBox.class).stream()
                    .filter(b -> b.getItems().contains("Any change")).findFirst().orElseThrow();
            assertEquals("Any change", level.getValue());
            assertFalse(level.isDisabled());
            readOnly.setSelected(true);
            assertTrue(level.isDisabled());
            readOnly.setSelected(false);
            level.getSelectionModel().select(3);
            all(tab.getContent(), Button.class).stream().filter(b -> "Save".equals(b.getText())).findFirst().orElseThrow().fire();
            assertEquals(3, saved.get(0).getWarningLevel());
            assertFalse(saved.get(0).isReadOnly());
        });
    }

    /** Rows added, duplicated and deleted in a table view before saving. */
    @Test
    void tableViewAddsDuplicatesAndDeletesRows() throws Exception {
        Fx.run(() -> {
            DataView view = new DataView();
            DataGrid grid = view.grid();
            List<Object> first = new java.util.ArrayList<>(List.of(1, "a", "x"));
            List<Object> second = new java.util.ArrayList<>(List.of(2, "b", "y"));
            grid.show(new com.dbadmin.model.TableData(List.of("id", "name", "total"), List.of(first, second)));
            // "total" is not a table column (e.g. computed in the query)
            view.enableEditing(new TableEditing.Target(null, null, List.of("id"), java.util.Set.of("id", "name"),
                    java.util.Set.of("id"), false), () -> { }, false);
            DataGrid.Editor editor = grid.editor();
            Object def = PendingEdits.Default.VALUE;

            editor.addRow(first, second); // a copy of the second row, after the first
            List<Object> copy = grid.getItems().get(1);
            assertEquals(List.of(def, "b", def), copy, "the key and generated values are left to the database");
            assertEquals(DataGrid.RowState.NEW, editor.state(copy));
            assertTrue(grid.isEditable(copy, 0), "a new row's key can be typed");
            assertFalse(grid.isEditable(copy, 2));
            assertFalse(grid.isEditable(first, 0), "a shown row's key finds it");

            editor.addRow(null, null);
            assertEquals(List.of(def, def, def), grid.getItems().get(3));

            editor.deleteRows(List.of(second, grid.getItems().get(3)));
            assertEquals(3, grid.getItems().size(), "the new row went; the shown one stays, marked");
            assertEquals(DataGrid.RowState.DELETED, editor.state(second));
            assertFalse(grid.isEditable(second, 1));
            editor.restoreRows(List.of(second));
            assertEquals(DataGrid.RowState.SHOWN, editor.state(second));
            assertTrue(view.hasEdits());
        });
    }

    /** The shared checkbox list: a range is taken among the rows shown, and the search starts a new one. */
    @Test
    void checkListRangesFollowTheSearch() throws Exception {
        Fx.run(() -> {
            record Item(String name, javafx.beans.property.BooleanProperty on) {
            }
            List<Item> items = new java.util.ArrayList<>();
            for (String n : List.of("orders", "order_lines", "shops", "users", "order_log")) {
                items.add(new Item(n, new javafx.beans.property.SimpleBooleanProperty()));
            }
            CheckList<Item> list = new CheckList<>(Item::on, Item::name);
            list.setItems(items);
            list.searchField().setText("order");
            assertEquals(List.of("orders", "order_lines", "order_log"), list.shown().stream().map(Item::name).toList());
            list.click(0, false);
            list.click(2, true);
            assertEquals(List.of(true, true, false, false, true), items.stream().map(i -> i.on().get()).toList(),
                    "shops and users were not shown, so not in the range");
            list.searchField().setText("");
            list.click(3, true); // the search changed: a plain click
            assertEquals(List.of(true, true, false, true, true), items.stream().map(i -> i.on().get()).toList());
            list.setVisible(i -> !i.name().equals("users"));
            assertEquals(4, list.shown().size());
        });
    }
}
