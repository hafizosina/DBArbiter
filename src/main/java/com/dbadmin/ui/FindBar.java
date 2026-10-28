package com.dbadmin.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Find / replace bar for a code editor (Ctrl+F / Ctrl+R). Matches are kept up to date
 * as the text changes and reported through {@code onMatchesChanged} for highlighting.
 */
class FindBar extends VBox {

    private final CodeArea editor;
    private final Runnable onMatchesChanged;
    private final TextField find = new TextField();
    private final TextField replace = new TextField();
    private final CheckBox matchCase = new CheckBox("Match case");
    private final Label count = new Label();
    private final HBox replaceRow;
    /** Start offsets of the matches; all have the length of the query. */
    private List<Integer> matches = List.of();

    FindBar(CodeArea editor, Runnable onMatchesChanged) {
        this.editor = editor;
        this.onMatchesChanged = onMatchesChanged;
        getStyleClass().add("find-bar");
        setSpacing(4);
        setPadding(new Insets(4, 6, 4, 6));

        find.setPromptText("Find");
        find.setPrefColumnCount(24);
        replace.setPromptText("Replace with");
        replace.setPrefColumnCount(24);
        count.setMinWidth(80);

        Button prev = new Button("↑");
        prev.setTooltip(new Tooltip("Previous match (Shift+Enter, Shift+F3)"));
        prev.setOnAction(e -> move(false));
        Button next = new Button("↓");
        next.setTooltip(new Tooltip("Next match (Enter, F3)"));
        next.setOnAction(e -> move(true));
        Button close = new Button("✕");
        close.setTooltip(new Tooltip("Close (Esc)"));
        close.setOnAction(e -> close());
        Button replaceOne = new Button("Replace");
        replaceOne.setOnAction(e -> replaceCurrent());
        Button replaceAll = new Button("Replace All");
        replaceAll.setOnAction(e -> replaceAll());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox findRow = new HBox(6, find, count, prev, next, matchCase, spacer, close);
        findRow.setAlignment(Pos.CENTER_LEFT);
        replaceRow = new HBox(6, replace, replaceOne, replaceAll);
        replaceRow.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(findRow, replaceRow);

        find.textProperty().addListener((o, a, b) -> {
            refresh();
            selectFrom(editor.getSelection().getStart(), true);
        });
        matchCase.selectedProperty().addListener((o, a, b) -> refresh());
        editor.textProperty().addListener((o, a, b) -> {
            if (isVisible()) refresh();
        });
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                close();
            } else if (e.getCode() == KeyCode.ENTER && e.getTarget() == find) {
                move(!e.isShiftDown());
            } else if (e.getCode() == KeyCode.ENTER && e.getTarget() == replace) {
                replaceCurrent();
            } else {
                return;
            }
            e.consume();
        });
        hideBar();
    }

    /** Marks a view made by {@link #over}: its find keys are its own (see {@link #isInOwnFind}). */
    private static final String SCOPE = "find-scope";

    /**
     * {@code content} (the area's scroll pane) with a find bar above it: Ctrl+F opens it, F3 / Shift+F3 and
     * Enter / Shift+Enter go between matches. For a text view that colors its own text: the matches are marked
     * on top of those colors, and again after the view restyles new text.
     */
    static VBox over(CodeArea area, javafx.scene.Node content) {
        FindBar[] bar = new FindBar[1];
        bar[0] = new FindBar(area, () -> javafx.application.Platform.runLater(() -> markMatches(area, bar[0].matchRanges())));
        VBox box = new VBox(bar[0], content);
        VBox.setVgrow(content, Priority.ALWAYS);
        box.getProperties().put(SCOPE, true);
        box.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && !e.isShiftDown() && e.getCode() == KeyCode.F) bar[0].open(false);
            else if (e.getCode() == KeyCode.F3) bar[0].move(!e.isShiftDown());
            else return;
            e.consume();
        });
        return box;
    }

    /** Whether a key event comes from inside a view made by {@link #over}, so an enclosing tab leaves it alone. */
    static boolean isInOwnFind(javafx.event.Event e) {
        for (Object n = e.getTarget(); n instanceof javafx.scene.Node node; n = node.getParent()) {
            if (node.getProperties().containsKey(SCOPE)) return true;
        }
        return false;
    }

    /** Puts "search-match" on the ranges, on top of the area's own styles (and takes it off everywhere else). */
    private static void markMatches(CodeArea area, List<int[]> ranges) {
        if (area.getLength() == 0) return;
        org.fxmisc.richtext.model.StyleSpans<java.util.Collection<String>> base = area.getStyleSpans(0, area.getLength())
                .mapStyles(styles -> styles.contains("search-match")
                        ? styles.stream().filter(c -> !c.equals("search-match")).toList() : styles);
        if (!ranges.isEmpty()) {
            org.fxmisc.richtext.model.StyleSpansBuilder<java.util.Collection<String>> marks = new org.fxmisc.richtext.model.StyleSpansBuilder<>();
            int last = 0;
            for (int[] m : ranges) {
                if (m[1] > area.getLength()) break; // the text changed since; the next refresh marks it
                marks.add(List.of(), m[0] - last);
                marks.add(List.of("search-match"), m[1] - m[0]);
                last = m[1];
            }
            marks.add(List.of(), area.getLength() - last);
            base = base.overlay(marks.create(), (styles, mark) -> {
                if (mark.isEmpty()) return styles;
                List<String> both = new ArrayList<>(styles);
                both.addAll(mark);
                return both;
            });
        }
        area.setStyleSpans(0, base);
    }

    /** Shows the bar; the editor's selected text (one line) becomes the search. */
    void open(boolean withReplace) {
        String selected = editor.getSelectedText();
        if (!selected.isEmpty() && !selected.contains("\n")) find.setText(selected);
        setVisible(true);
        setManaged(true);
        replaceRow.setVisible(withReplace);
        replaceRow.setManaged(withReplace);
        refresh();
        (withReplace && !find.getText().isEmpty() ? replace : find).requestFocus();
        find.selectAll();
    }

    void close() {
        hideBar();
        onMatchesChanged.run();
        editor.requestFocus();
    }

    private void hideBar() {
        setVisible(false);
        setManaged(false);
        matches = List.of();
    }

    /** Current matches as [start, end) pairs, empty when the bar is closed. */
    List<int[]> matchRanges() {
        int len = find.getText().length();
        List<int[]> ranges = new ArrayList<>(matches.size());
        for (int start : matches) ranges.add(new int[]{start, start + len});
        return ranges;
    }

    /** Next (or previous) match after the current selection, wrapping around. */
    void move(boolean forward) {
        if (!isVisible()) {
            open(false);
            return;
        }
        if (matches.isEmpty()) return;
        int caret = forward ? editor.getSelection().getEnd() : editor.getSelection().getStart();
        selectFrom(caret, forward);
    }

    private void selectFrom(int position, boolean forward) {
        if (matches.isEmpty()) {
            updateCount(-1);
            return;
        }
        int index = -1;
        if (forward) {
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i) >= position) {
                    index = i;
                    break;
                }
            }
            if (index < 0) index = 0;
        } else {
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i) < position) {
                    index = i;
                    break;
                }
            }
            if (index < 0) index = matches.size() - 1;
        }
        int start = matches.get(index);
        editor.selectRange(start, start + find.getText().length());
        editor.requestFollowCaret();
        updateCount(index);
    }

    private void replaceCurrent() {
        String query = find.getText();
        if (query.isEmpty()) return;
        int start = editor.getSelection().getStart();
        // only replace when the selection is exactly a match; otherwise just go to the next one
        if (matches.contains(start) && editor.getSelection().getLength() == query.length()) {
            editor.replaceText(start, start + query.length(), replace.getText());
            refresh();
            selectFrom(start + replace.getText().length(), true);
        } else {
            move(true);
        }
    }

    private void replaceAll() {
        if (matches.isEmpty()) return;
        int n = matches.size();
        String query = find.getText();
        StringBuilder sb = new StringBuilder(editor.getText());
        for (int i = matches.size() - 1; i >= 0; i--) {
            int start = matches.get(i);
            sb.replace(start, start + query.length(), replace.getText());
        }
        editor.replaceText(sb.toString()); // one undo step
        refresh();
        count.setText(n + " replaced");
    }

    private void refresh() {
        String query = find.getText();
        List<Integer> found = new ArrayList<>();
        if (!query.isEmpty()) {
            String text = editor.getText();
            boolean cs = matchCase.isSelected();
            String haystack = cs ? text : text.toLowerCase(Locale.ROOT);
            String needle = cs ? query : query.toLowerCase(Locale.ROOT);
            // lower-casing can change length for a few characters; fall back to case-sensitive then
            if (haystack.length() != text.length()) {
                haystack = text;
                needle = query;
            }
            for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
                found.add(i);
            }
        }
        matches = found;
        int current = matches.indexOf(editor.getSelection().getStart());
        updateCount(current);
        onMatchesChanged.run();
    }

    private void updateCount(int index) {
        count.getStyleClass().remove("find-none");
        if (find.getText().isEmpty()) {
            count.setText("");
        } else if (matches.isEmpty()) {
            count.setText("No results");
            count.getStyleClass().add("find-none");
        } else {
            count.setText((index >= 0 ? (index + 1) + " / " : "") + matches.size());
        }
    }
}
