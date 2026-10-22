package com.dbadmin.ui;

import com.dbadmin.ui.SqlCompleter.Kind;
import com.dbadmin.ui.SqlCompleter.Suggestion;
import javafx.geometry.Bounds;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Suggestions while typing a condition in a one-line field (e.g. Data Compare's Where): the given column
 * names and the words of a condition. ↑ ↓ choose, Enter or Tab accept, Esc closes.
 */
final class ConditionCompletion {

    private static final String[] WORDS = {"and", "or", "not", "is", "null", "true", "false", "in", "like", "ilike",
            "between", "exists", "coalesce", "lower", "upper", "trim", "now"};

    private final TextField field;
    private final Supplier<List<String>> columns;
    /** How a column is written in the condition (e.g. quoted when it has a dot). */
    private final Function<String, String> written;
    private final CompletionPopup popup = new CompletionPopup();
    private boolean inserting;

    private ConditionCompletion(TextField field, Supplier<List<String>> columns, Function<String, String> written) {
        this.field = field;
        this.columns = columns;
        this.written = written;
    }

    static void install(TextField field, Supplier<List<String>> columns, Function<String, String> written) {
        ConditionCompletion c = new ConditionCompletion(field, columns, written);
        // after the field has moved its caret for the typed text
        field.textProperty().addListener((o, a, b) -> {
            if (!c.inserting) javafx.application.Platform.runLater(c::suggest);
        });
        field.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) c.popup.hide();
        });
        // before the field's own Enter (which runs the compare): with the list open, keys work the list
        field.addEventFilter(KeyEvent.KEY_PRESSED, c::keys);
    }

    private void keys(KeyEvent e) {
        if (!popup.isShowing()) return;
        switch (e.getCode()) {
            case UP -> popup.move(-1);
            case DOWN -> popup.move(1);
            case ENTER, TAB -> popup.acceptSelected();
            case ESCAPE -> popup.hide();
            default -> {
                return;
            }
        }
        e.consume();
    }

    /** Where the word at the caret starts (letters, digits, _ and .). */
    private int wordStart() {
        String text = field.getText();
        int i = field.getCaretPosition();
        while (i > 0 && (Character.isLetterOrDigit(text.charAt(i - 1)) || text.charAt(i - 1) == '_' || text.charAt(i - 1) == '.')) i--;
        return i;
    }

    private void suggest() {
        if (!field.isFocused()) return;
        String text = field.getText() == null ? "" : field.getText();
        int start = wordStart();
        String prefix = text.substring(start, field.getCaretPosition());
        // nothing typed, a number, or inside a quoted value: no list
        if (prefix.isEmpty() || Character.isDigit(prefix.charAt(0)) || text.substring(0, start).chars().filter(ch -> ch == '\'').count() % 2 == 1) {
            popup.hide();
            return;
        }
        List<Suggestion> all = new ArrayList<>();
        for (String c : columns.get()) all.add(new Suggestion(c, Kind.COLUMN, "column"));
        for (String w : WORDS) all.add(new Suggestion(w, Kind.KEYWORD, null));
        List<Suggestion> items = SqlCompleter.filter(all, prefix);
        if (items.isEmpty() || items.size() == 1 && items.get(0).text().equalsIgnoreCase(prefix)) {
            popup.hide();
            return;
        }
        Text measure = new Text(text.substring(0, start));
        measure.setFont(field.getFont());
        Bounds b = field.localToScreen(field.getBoundsInLocal());
        if (b == null) return;
        popup.show(field, b.getMinX() + 6 + measure.getLayoutBounds().getWidth(), b.getMaxY() + 2, items, this::accept);
    }

    private void accept(Suggestion s) {
        int start = wordStart();
        int caret = field.getCaretPosition();
        String insert = s.kind() == Kind.COLUMN ? written.apply(s.text()) : s.text();
        inserting = true;
        field.replaceText(start, caret, insert);
        inserting = false;
        field.positionCaret(start + insert.length());
        popup.hide();
    }
}
