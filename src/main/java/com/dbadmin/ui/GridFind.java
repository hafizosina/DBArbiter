package com.dbadmin.ui;

import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Find in a grid (Ctrl+F): the cells holding the text are marked, and Enter / F3 (Shift: back) moves the focused
 * cell from one to the next, in the order the grid shows them. Hidden until opened; Esc closes it.
 */
class GridFind extends HBox {

    /** A matching cell: its row and its column as the grid shows it. */
    record Hit(int row, TableColumn<List<Object>, ?> column) {
    }

    private final DataGrid grid;
    private final TextField text = new TextField();
    private final CheckBox matchCase = new CheckBox("Match case");
    private final Label count = new Label();
    private List<Hit> hits = List.of();

    GridFind(DataGrid grid) {
        super(6);
        this.grid = grid;
        getStyleClass().add("find-bar");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(4, 6, 4, 6));
        text.setPromptText("Find in the rows");
        text.setPrefColumnCount(24);
        count.getStyleClass().add("hint-small");
        count.setMinWidth(90);
        Button prev = new Button("↑");
        prev.setTooltip(new Tooltip("Previous (Shift+Enter, Shift+F3)"));
        prev.setOnAction(e -> move(false));
        Button next = new Button("↓");
        next.setTooltip(new Tooltip("Next (Enter, F3)"));
        next.setOnAction(e -> move(true));
        Button close = new Button("✕");
        close.setTooltip(new Tooltip("Close (Esc)"));
        close.setOnAction(e -> close());
        getChildren().addAll(text, prev, next, matchCase, count, close);
        text.textProperty().addListener((o, a, b) -> search(true));
        matchCase.selectedProperty().addListener((o, a, b) -> search(true));
        // other rows shown (a new compare, a status filter): find again, without jumping
        grid.itemsProperty().addListener((o, a, items) -> {
            if (isVisible()) search(false);
            if (items != null) items.addListener((ListChangeListener<List<Object>>) c -> {
                if (isVisible()) search(false);
            });
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                close();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.F3) {
                move(!e.isShiftDown());
                e.consume();
            }
        });
        setVisible(false);
        setManaged(false);
    }

    /** Shows the bar with the focus in it; the grid's selected cell's text is not taken over (it may be long). */
    void open() {
        setVisible(true);
        setManaged(true);
        text.requestFocus();
        text.selectAll();
        search(false);
    }

    void close() {
        setVisible(false);
        setManaged(false);
        grid.setFind("", false);
        grid.requestFocus();
    }

    /** Finds the cells again; {@code jump}: to the first one from the focused cell on. */
    private void search(boolean jump) {
        String q = text.getText();
        grid.setFind(q, matchCase.isSelected());
        List<Hit> found = new ArrayList<>();
        if (!q.isEmpty()) {
            List<TableColumn<List<Object>, ?>> columns = grid.getVisibleLeafColumns();
            for (int r = 0; r < grid.getItems().size(); r++) {
                List<Object> row = grid.getItems().get(r);
                for (TableColumn<List<Object>, ?> c : columns) {
                    if (DataGrid.holds(row.get((Integer) c.getUserData()), q, matchCase.isSelected())) found.add(new Hit(r, c));
                }
            }
        }
        hits = found;
        if (jump && !hits.isEmpty()) {
            int at = index(true, true);
            select(at);
        } else {
            showCount(-1);
        }
    }

    /** The next (or previous) match from the focused cell, round the end. */
    private void move(boolean forward) {
        if (hits.isEmpty()) return;
        select(index(forward, false));
    }

    /** {@code inclusive}: the focused cell itself counts (a new query starting where the user is). */
    private int index(boolean forward, boolean inclusive) {
        var pos = grid.getFocusModel().getFocusedCell();
        int row = pos == null ? -1 : pos.getRow();
        int col = pos == null || pos.getTableColumn() == null ? -1 : grid.getVisibleLeafColumns().indexOf(pos.getTableColumn());
        for (int i = 0; i < hits.size(); i++) {
            int k = forward ? i : hits.size() - 1 - i;
            Hit h = hits.get(k);
            int c = grid.getVisibleLeafColumns().indexOf(h.column());
            int cmp = h.row() != row ? Integer.compare(h.row(), row) : Integer.compare(c, col);
            if (forward ? (inclusive ? cmp >= 0 : cmp > 0) : cmp < 0) return k;
        }
        return forward ? 0 : hits.size() - 1;
    }

    private void select(int k) {
        Hit h = hits.get(k);
        grid.getSelectionModel().clearAndSelect(h.row(), h.column());
        grid.getFocusModel().focus(h.row(), h.column());
        grid.scrollTo(Math.max(0, h.row() - 3));
        grid.scrollToColumn(h.column());
        showCount(k);
    }

    private void showCount(int k) {
        count.setText(text.getText().isEmpty() ? "" : hits.isEmpty() ? "no match" : k < 0 ? hits.size() + " match(es)" : (k + 1) + " of " + hits.size());
    }

    /** The query field (for tests). */
    TextField field() {
        return text;
    }

    String countText() {
        return count.getText();
    }
}
