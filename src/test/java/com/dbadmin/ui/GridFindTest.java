package com.dbadmin.ui;

import com.dbadmin.model.TableData;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ctrl+F over a grid: matches in the order shown, Enter / Shift+Enter, Match case, compared values. */
class GridFindTest {

    private static void key(GridFind bar, KeyCode code, boolean shift) {
        bar.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
    }

    private static String focused(DataGrid grid) {
        var p = grid.getFocusModel().getFocusedCell();
        return p.getRow() + ":" + p.getTableColumn().getText();
    }

    @Test
    void findsCellsAndMovesBetweenThem() throws Exception {
        Fx.run(() -> {
            DataGrid grid = new DataGrid();
            List<List<Object>> rows = new ArrayList<>();
            rows.add(new ArrayList<>(List.of("a", "Facebook.png", "x")));
            rows.add(new ArrayList<>(List.of("b", "none", "facebook")));
            rows.add(new ArrayList<>(List.of("c", new ComparedValue("old.png", "FACEBOOK.png", true, true, "uat", "prod", true), "y")));
            grid.show(new TableData(List.of("code", "icon", "note"), rows));
            GridFind bar = new GridFind(grid);
            new javafx.scene.layout.VBox(bar, grid);
            bar.open();
            assertTrue(bar.isVisible());

            bar.field().setText("facebook");
            assertEquals("1 of 3", bar.countText(), "both sides of a compared value count");
            assertEquals("0:icon", focused(grid));
            key(bar, KeyCode.ENTER, false);
            assertEquals("1:note", focused(grid));
            key(bar, KeyCode.ENTER, false);
            assertEquals("2:icon", focused(grid));
            key(bar, KeyCode.ENTER, false);
            assertEquals("0:icon", focused(grid), "round the end");
            key(bar, KeyCode.ENTER, true);
            assertEquals("2:icon", focused(grid), "Shift+Enter goes back");

            ((javafx.scene.control.CheckBox) bar.getChildren().get(3)).setSelected(true);
            bar.field().setText("Facebook");
            assertEquals("1 of 1", bar.countText(), "Match case");
            bar.field().setText("nothing like it");
            assertEquals("no match", bar.countText());

            key(bar, KeyCode.ESCAPE, false);
            assertFalse(bar.isVisible());
            assertFalse(DataGrid.holds(null, "x", false));
        });
    }
}
