package com.dbadmin.ui;

import com.dbadmin.model.BlobValue;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * One row of a {@link DataGrid} turned on its side, as DBeaver's record mode: a line per column with
 * its value, and buttons to step through the rows. It shows and edits the grid's own rows: picking a
 * field focuses that cell in the grid, so the value panel, Set NULL / Revert and Save work as there.
 */
class RecordView extends BorderPane {

    private final DataGrid grid;
    /** The data's column indexes, in the grid's column order. */
    private final TableView<Integer> fields = new TableView<>();
    private final Label position = new Label();
    private final Button prev = new Button("◀");
    private final Button next = new Button("▶");
    private final BooleanSupplier moreAvailable;
    private final Runnable fetchMore;
    /** The row shown: an index into the grid's items, -1 when there are none. */
    private int row = -1;

    /**
     * @param moreAvailable true if rows beyond the grid's can be fetched
     * @param fetchMore     fetches them; "next" on the last row asks for it
     */
    RecordView(DataGrid grid, BooleanSupplier moreAvailable, Runnable fetchMore) {
        this.grid = grid;
        this.moreAvailable = moreAvailable;
        this.fetchMore = fetchMore;

        TableColumn<Integer, String> name = new TableColumn<>("Column");
        name.setCellValueFactory(c -> new ReadOnlyStringWrapper(grid.columnNames().get(c.getValue())));
        name.setPrefWidth(180);
        name.setSortable(false);
        TableColumn<Integer, Object> value = new TableColumn<>("Value");
        value.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(valueOf(c.getValue())));
        value.setCellFactory(c -> new FieldCell());
        value.setSortable(false);
        value.prefWidthProperty().bind(fields.widthProperty().subtract(name.widthProperty()).subtract(20));
        fields.getColumns().addAll(List.of(name, value));
        fields.setFixedCellSize(24);
        fields.setPlaceholder(new Label("No rows"));
        fields.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> focusGridCell());
        // Set NULL / Revert act on the grid's focused cell, which is the picked field
        fields.setOnContextMenuRequested(e -> {
            ContextMenu menu = grid.getContextMenu();
            if (menu != null) menu.show(fields, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        // a filter: the table's own keys move the focus on Ctrl+arrows
        fields.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.RIGHT) step(1);
            else if (e.isControlDown() && e.getCode() == KeyCode.LEFT) step(-1);
            else return;
            e.consume();
        });

        prev.setTooltip(new Tooltip("Previous row (Ctrl+Left)"));
        prev.setOnAction(e -> step(-1));
        next.setTooltip(new Tooltip("Next row (Ctrl+Right)"));
        next.setOnAction(e -> step(1));
        position.getStyleClass().add("hint-small");
        HBox bar = new HBox(6, prev, next, position);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 8, 3, 8));
        setTop(bar);
        setCenter(fields);

        grid.getColumns().addListener((ListChangeListener<TableColumn<List<Object>, ?>>) c -> listFields());
        grid.itemsProperty().addListener((o, a, b) -> {
            b.addListener((ListChangeListener<List<Object>>) c -> updatePosition()); // more rows fetched
            show(row); // a refreshed page keeps its place
        });
        grid.getItems().addListener((ListChangeListener<List<Object>>) c -> updatePosition());
        // the shown values change with edits made in the grid's menu or the value panel
        grid.selectedCellProperty().addListener(o -> fields.refresh());
    }

    /** Shows the grid's row {@code index} (kept within the rows), keeping the picked column. */
    void show(int index) {
        int size = grid.getItems().size();
        row = size == 0 ? -1 : Math.max(0, Math.min(index, size - 1));
        listFields();
        updatePosition();
    }

    /** Starts at the grid's focused cell: its row (or the first) and its column. */
    void showFocusedRow() {
        var focused = grid.getFocusModel().getFocusedCell();
        show(focused != null && focused.getRow() >= 0 ? focused.getRow() : 0);
        if (focused != null && focused.getTableColumn() != null) {
            fields.getSelectionModel().select((Integer) focused.getTableColumn().getUserData());
        }
        fields.requestFocus();
    }

    void refresh() {
        fields.refresh();
    }

    private void step(int by) {
        if (by > 0 && row == grid.getItems().size() - 1) {
            if (moreAvailable.getAsBoolean()) fetchMore.run();
            return;
        }
        show(row + by);
    }

    private void listFields() {
        Integer picked = fields.getSelectionModel().getSelectedItem();
        List<Integer> columns = new ArrayList<>();
        if (row >= 0) grid.getColumns().forEach(col -> columns.add((Integer) col.getUserData()));
        fields.setItems(FXCollections.observableArrayList(columns));
        if (columns.isEmpty()) return;
        fields.getSelectionModel().select(picked != null && columns.contains(picked) ? picked : columns.get(0));
        focusGridCell();
    }

    private void updatePosition() {
        int size = grid.getItems().size();
        if (row >= size) row = size - 1;
        boolean more = moreAvailable.getAsBoolean();
        position.setText(row < 0 ? "No rows" : "Row " + (row + 1) + " of " + size + (more ? "+" : ""));
        prev.setDisable(row <= 0);
        next.setDisable(row < 0 || row == size - 1 && !more);
    }

    private Object valueOf(int column) {
        return row < 0 || row >= grid.getItems().size() ? null : grid.getItems().get(row).get(column);
    }

    /** Focuses the picked field's cell in the grid, so the value panel and the edit menu act on it. */
    private void focusGridCell() {
        Integer column = fields.getSelectionModel().getSelectedItem();
        if (row < 0 || column == null || grid.column(column) == null) return;
        grid.getSelectionModel().clearAndSelect(row, grid.column(column));
        grid.getFocusModel().focus(row, grid.column(column));
    }

    /** A value; editable as in the grid: double-click, Enter to set, Esc to cancel. */
    private class FieldCell extends TableCell<Integer, Object> {
        private TextField field;
        private final CheckBox check = new CheckBox();

        FieldCell() {
            check.setFocusTraversable(false);
            check.setOnAction(e -> edited(check.isSelected()));
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) startTyping();
            });
        }

        private int column() {
            return getTableRow().getItem();
        }

        private boolean editable() {
            return !isEmpty() && getTableRow() != null && getTableRow().getItem() != null && row >= 0
                    && grid.isEditableColumn(column());
        }

        private void startTyping() {
            if (!editable() || getItem() instanceof BlobValue || getItem() instanceof Boolean) return;
            // a TextField drops line breaks: several lines are edited in the value panel
            if (getItem() != null && getItem().toString().matches("(?s).*[\r\n].*")) {
                grid.editor().editMultiLine();
                return;
            }
            field = new TextField(getItem() == null ? "" : getItem().toString());
            field.setOnAction(e -> commit());
            field.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) stopTyping();
            });
            field.focusedProperty().addListener((o, was, focused) -> {
                if (!focused && field != null) commit();
            });
            setText(null);
            setGraphic(field);
            field.requestFocus();
            field.selectAll();
        }

        /** Sets the typed text, except an untouched empty field on a NULL (that would make it ''). */
        private void commit() {
            String text = field.getText();
            boolean untouched = getItem() == null && text.isEmpty() || getItem() != null && text.equals(getItem().toString());
            stopTyping();
            if (!untouched) edited(text);
        }

        private void stopTyping() {
            field = null;
            updateItem(getItem(), isEmpty());
            fields.requestFocus();
        }

        private void edited(Object value) {
            if (!editable()) return;
            grid.editor().edited(grid.getItems().get(row), column(), value);
            grid.refresh();
            grid.publishFocusedCell(); // the value panel shows the new value
            fields.refresh();
        }

        @Override
        protected void updateItem(Object value, boolean empty) {
            super.updateItem(value, empty);
            getStyleClass().removeAll("null-value", "cell-edited");
            if (field != null) return;
            setGraphic(null);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setText(null);
                return;
            }
            if (editable() && grid.editor().isEdited(grid.getItems().get(row), column())) getStyleClass().add("cell-edited");
            if (value instanceof Boolean b) {
                setText(null);
                check.setSelected(b);
                check.setMouseTransparent(!editable());
                setGraphic(check);
            } else {
                setText(DataGrid.oneLine(value));
                if (value == null || value instanceof BlobValue) getStyleClass().add("null-value");
            }
        }
    }
}
