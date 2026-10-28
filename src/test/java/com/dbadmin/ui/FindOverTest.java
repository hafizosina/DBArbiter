package com.dbadmin.ui;

import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The find bar over a read-only text view (value panel, diff, SQL previews): keys, matches, marks over its colors. */
class FindOverTest {

    private static void press(javafx.scene.Node target, KeyCode code, boolean shortcut, boolean shift) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !isMac(), false, shortcut && isMac()));
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** Runs pending Platform.runLater work (the marks are put on after the view restyles). */
    private static void settle() throws Exception {
        Fx.call(() -> null);
    }

    private static Collection<String> styleAt(CodeArea area, int position) {
        return area.getStyleAtPosition(position + 1);
    }

    @Test
    void ctrlFFindsAndMarksMatchesOnTopOfTheViewsColorsAndCloseTakesTheMarksOff() throws Exception {
        CodeArea area = Fx.call(CodeArea::new);
        VBox[] box = new VBox[1];
        FindBar[] bar = new FindBar[1];
        Fx.run(() -> {
            area.setEditable(false);
            area.replaceText("select id from t where id = 1");
            area.setStyle(0, 6, List.of("keyword")); // the view's own color
            box[0] = FindBar.over(area, new VirtualizedScrollPane<>(area));
            bar[0] = (FindBar) box[0].getChildren().get(0);
            assertFalse(bar[0].isVisible());
            press(area, KeyCode.F, true, false);
            assertTrue(bar[0].isVisible());
            TextField find = (TextField) bar[0].lookup(".text-field");
            find.setText("ID");
            assertEquals(7, area.getSelection().getStart()); // the first match, case ignored
        });
        settle();
        Fx.run(() -> {
            assertTrue(styleAt(area, 7).contains("search-match"));
            assertTrue(styleAt(area, 23).contains("search-match"));
            assertEquals(List.of("keyword"), List.copyOf(styleAt(area, 0)));
            press(area, KeyCode.F3, false, false);
            assertEquals(23, area.getSelection().getStart());
            press(area, KeyCode.F3, false, true);
            assertEquals(7, area.getSelection().getStart());
            bar[0].close();
        });
        settle();
        Fx.run(() -> {
            assertFalse(styleAt(area, 7).contains("search-match"));
            assertEquals(List.of("keyword"), List.copyOf(styleAt(area, 0)));
        });
    }

    @Test
    void newTextIsSearchedAgainAndMarkedAfterTheViewRestylesIt() throws Exception {
        CodeArea area = Fx.call(CodeArea::new);
        Fx.run(() -> {
            VBox box = FindBar.over(area, new VirtualizedScrollPane<>(area));
            area.replaceText("abc");
            press(area, KeyCode.F, true, false);
            ((TextField) box.lookup(".text-field")).setText("x");
            // a value panel showing another cell: new text, then its own colors
            area.replaceText("x1 x2");
            area.setStyle(0, 5, List.of("json-key"));
        });
        settle();
        Fx.run(() -> {
            assertTrue(styleAt(area, 0).containsAll(List.of("json-key", "search-match")));
            assertTrue(styleAt(area, 3).contains("search-match"));
            assertFalse(styleAt(area, 1).contains("search-match"));
        });
    }

    @Test
    void anEnclosingTabLeavesTheViewsFindKeysAlone() throws Exception {
        Fx.run(() -> {
            CodeArea inside = new CodeArea("text");
            CodeArea outside = new CodeArea("text");
            VBox box = FindBar.over(inside, new VirtualizedScrollPane<>(inside));
            int[] tabFinds = {0};
            StackPane tab = new StackPane(new VBox(box, outside));
            // like a script tab sending Ctrl+F from its results to its SQL editor
            tab.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (!FindBar.isInOwnFind(e) && e.getCode() == KeyCode.F) {
                    tabFinds[0]++;
                    e.consume();
                }
            });
            press(inside, KeyCode.F, true, false);
            assertEquals(0, tabFinds[0]);
            assertTrue(box.getChildren().get(0).isVisible());
            press(outside, KeyCode.F, true, false);
            assertEquals(1, tabFinds[0]);
        });
    }
}
