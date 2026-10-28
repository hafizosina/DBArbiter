package com.dbadmin.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A bracket or quote typed over selected text wraps it (typed as the keyboard would, through the text area). */
class SqlEditorWrapTest {

    private static SqlEditor editor(String text) {
        SqlEditor e = new SqlEditor(new SqlCompleter(), null, () -> null, () -> { });
        e.setText(text);
        return e;
    }

    private static void type(CodeArea area, String ch) {
        area.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, false, false, false));
    }

    @Test
    void bracketsAndQuotesWrapTheSelectionAndKeepItSelected() throws Exception {
        Fx.run(() -> {
            SqlEditor e = editor("select a + b from t");
            CodeArea area = e.area();
            area.selectRange(7, 12); // a + b
            type(area, "(");
            assertEquals("select (a + b) from t", area.getText());
            assertEquals("a + b", area.getSelectedText());
            type(area, "[");
            assertEquals("select ([a + b]) from t", area.getText());
            area.selectRange(22, 23); // t
            type(area, "\"");
            assertEquals("select ([a + b]) from \"t\"", area.getText());
            type(area, "'");
            type(area, "`");
            type(area, "{");
            assertEquals("select ([a + b]) from \"'`{t}`'\"", area.getText());
        });
    }

    @Test
    void wrappingIsOneUndoStep() throws Exception {
        Fx.run(() -> {
            SqlEditor e = editor("where id = 1");
            CodeArea area = e.area();
            area.selectRange(6, 12);
            type(area, "(");
            assertEquals("where (id = 1)", area.getText());
            area.undo();
            assertEquals("where id = 1", area.getText());
        });
    }

    @Test
    void withoutASelectionOrForOtherKeysTypingIsUnchanged() throws Exception {
        Fx.run(() -> {
            SqlEditor e = editor("select 1");
            CodeArea area = e.area();
            area.moveTo(8);
            type(area, "(");
            assertEquals("select 1(", area.getText());
            area.selectRange(7, 8); // 1
            type(area, ")"); // a closing bracket replaces the selection, as any other key
            assertEquals("select )(", area.getText());
            area.selectRange(0, 6);
            type(area, "x");
            assertEquals("x )(", area.getText());
        });
    }
}
