package com.dbadmin.ui;

import com.dbadmin.model.BlobValue;
import com.dbadmin.model.ColumnType;
import com.dbadmin.model.TableData;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TextField;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A read-only TableView that can show any {@link TableData}. Rows are always one line high;
 * the full value of the focused cell is published as {@link #selectedCellProperty()}.
 */
class DataGrid extends TableView<List<Object>> {

    /** The focused cell: column name, type (null for metadata grids) and raw value. */
    record CellValue(String column, ColumnType type, Object value) {
    }

    private static final int MAX_CELL_CHARS = 500;
    private static final double ROW_HEIGHT = 24;

    /** Receives cell edits (see {@link #setEditing}). */
    interface Editor {
        /** A cell was given a new value (text typed, or null for Set NULL). */
        void edited(List<Object> row, int column, Object value);

        boolean isEdited(List<Object> row, int column);

        void revert(List<Object> row, int column);

        /** The focused cell holds several lines, which a one-line cell editor would lose: edit it elsewhere. */
        void editMultiLine();
    }

    private final ReadOnlyObjectWrapper<CellValue> selectedCell = new ReadOnlyObjectWrapper<>();
    private TableData data;
    private Set<Integer> editableColumns = Set.of();
    private Editor editor;

    DataGrid() {
        setPlaceholder(new Label("No rows"));
        setFixedCellSize(ROW_HEIGHT);
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        getSelectionModel().setCellSelectionEnabled(true);
        getFocusModel().focusedCellProperty().addListener((o, a, pos) -> publishFocusedCell());
    }

    /** Publishes the focused cell, also again after its value was edited. */
    void publishFocusedCell() {
        TablePosition<List<Object>, ?> pos = getFocusModel().getFocusedCell();
        if (data == null || pos == null || pos.getRow() < 0 || pos.getTableColumn() == null
                || pos.getRow() >= getItems().size()) {
            selectedCell.set(null);
            return;
        }
        // the column's original index survives the user reordering columns
        int c = (Integer) pos.getTableColumn().getUserData();
        selectedCell.set(new CellValue(data.columns().get(c), data.type(c), getItems().get(pos.getRow()).get(c)));
    }

    /** A cell that can be edited now: its row and original column index. */
    record EditableCell(List<Object> row, int column) {
    }

    /** The focused cell if it is in an editable column, else null. */
    EditableCell focusedEditableCell() {
        TablePosition<List<Object>, ?> pos = getFocusModel().getFocusedCell();
        if (editor == null || pos == null || pos.getTableColumn() == null || pos.getRow() < 0
                || pos.getRow() >= getItems().size()) {
            return null;
        }
        int c = (Integer) pos.getTableColumn().getUserData();
        return editableColumns.contains(c) ? new EditableCell(getItems().get(pos.getRow()), c) : null;
    }

    /** True if the column (original index) can be edited now. */
    boolean isEditableColumn(int column) {
        return editor != null && editableColumns.contains(column);
    }

    /** Where edits go; null while the rows are read-only. */
    Editor editor() {
        return editor;
    }

    /** The column showing the data's column {@code index}, wherever the user dragged it. */
    TableColumn<List<Object>, ?> column(int index) {
        for (TableColumn<List<Object>, ?> col : getColumns()) if ((Integer) col.getUserData() == index) return col;
        return null;
    }

    /** The type of the data's column {@code index}; null for metadata grids. */
    ColumnType columnType(int index) {
        return data == null ? null : data.type(index);
    }

    ReadOnlyObjectProperty<CellValue> selectedCellProperty() {
        return selectedCell.getReadOnlyProperty();
    }

    /** The column names of the data shown now. */
    List<String> columnNames() {
        return data == null ? List.of() : data.columns();
    }

    /**
     * Lets the user edit the given columns (original indexes): double-click a cell, Enter to set,
     * Esc to cancel; right-click for Set NULL / Revert. No columns: read-only again.
     */
    void setEditing(Set<Integer> columns, Editor editor) {
        this.editableColumns = columns;
        this.editor = editor;
        setEditable(!columns.isEmpty());
        for (TableColumn<List<Object>, ?> col : getColumns()) col.setEditable(columns.contains((Integer) col.getUserData()));
        setContextMenu(columns.isEmpty() ? null : editMenu());
        refresh();
    }

    private ContextMenu editMenu() {
        MenuItem setNull = new MenuItem("Set NULL");
        setNull.setOnAction(e -> forFocusedEditableCell((row, c) -> editor.edited(row, c, null)));
        MenuItem revert = new MenuItem("Revert");
        revert.setOnAction(e -> forFocusedEditableCell((row, c) -> editor.revert(row, c)));
        ContextMenu menu = new ContextMenu(setNull, revert);
        menu.setOnShowing(e -> {
            TablePosition<List<Object>, ?> pos = getFocusModel().getFocusedCell();
            boolean editable = pos != null && pos.getTableColumn() != null && pos.getRow() >= 0
                    && editableColumns.contains((Integer) pos.getTableColumn().getUserData());
            setNull.setDisable(!editable);
            revert.setDisable(!editable || !editor.isEdited(getItems().get(pos.getRow()), (Integer) pos.getTableColumn().getUserData()));
        });
        return menu;
    }

    private void forFocusedEditableCell(java.util.function.BiConsumer<List<Object>, Integer> action) {
        EditableCell cell = focusedEditableCell();
        if (cell == null) return;
        action.accept(cell.row(), cell.column());
        refresh();
        publishFocusedCell();
    }

    void show(TableData data) {
        this.data = data;
        selectedCell.set(null);
        // new data starts read-only; whoever shows it may turn editing on again
        editableColumns = Set.of();
        editor = null;
        setEditable(false);
        setContextMenu(null);
        getColumns().clear();
        for (int i = 0; i < data.columns().size(); i++) {
            final int index = i;
            TableColumn<List<Object>, Object> col = new TableColumn<>(data.columns().get(i));
            col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().get(index)));
            col.setCellFactory(c -> new ValueCell());
            col.setUserData(index);
            col.setEditable(false);
            col.setOnEditCommit(e -> {
                if (editor != null) editor.edited(e.getRowValue(), index, e.getNewValue());
                refresh();
                publishFocusedCell(); // the value panel shows the new value
            });
            col.setPrefWidth(preferredWidth(data, i));
            getColumns().add(col);
        }
        setItems(FXCollections.observableArrayList(data.rows()));
    }

    /** Automatic widths never exceed this; the user can still drag a column wider. */
    static final double MAX_AUTO_WIDTH = 400;
    private static final double MIN_WIDTH = 50;
    private static final double CHAR_WIDTH = 7.5;
    private static final double CELL_PADDING = 20;

    /**
     * Width from the column's type when known, so it is the same on every page;
     * otherwise (metadata grids) from the header and the first rows' values.
     */
    private static double preferredWidth(TableData data, int column) {
        int chars = data.columns().get(column).length() + 2; // headers are bold
        ColumnType type = data.type(column);
        if (type != null) {
            chars = Math.max(chars, typeChars(type));
        } else {
            int sample = Math.min(data.rows().size(), 100);
            for (int r = 0; r < sample; r++) {
                Object v = data.rows().get(r).get(column);
                chars = Math.max(chars, v == null ? 6 : Math.min(v.toString().length(), 80));
            }
        }
        return Math.max(MIN_WIDTH, Math.min(MAX_AUTO_WIDTH, chars * CHAR_WIDTH + CELL_PADDING));
    }

    /**
     * Characters needed for the widest value the type allows: the declared range when the type
     * has one (varchar(12) -> 12, numeric(10,2) -> 12, int4 -> 11, uuid -> 36), otherwise a default.
     */
    private static int typeChars(ColumnType type) {
        int size = type.displaySize();
        if (size > 0 && size < 10_000) return size;
        // unbounded: text, json, xml, plain numeric, binary, ...
        String name = type.name() == null ? "" : type.name().toLowerCase(Locale.ROOT);
        if (name.equals("uuid")) return 36;
        return switch (type.sqlType()) {
            case Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE -> 16;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> 20;
            default -> 40;
        };
    }

    private class ValueCell extends TableCell<List<Object>, Object> {
        private TextField field;
        /** Shows a boolean; on an editable column a click toggles it (as a pending edit). */
        private final CheckBox check = new CheckBox();

        ValueCell() {
            check.setFocusTraversable(false);
            check.setOnAction(e -> {
                List<Object> row = getTableRow() == null ? null : getTableRow().getItem();
                if (row == null || editor == null) return;
                editor.edited(row, (Integer) getTableColumn().getUserData(), check.isSelected());
                getTableView().refresh();
                publishFocusedCell();
            });
        }

        @Override
        public void startEdit() {
            // binary values are not edited as text; booleans are toggled with their checkbox
            if (getItem() instanceof BlobValue || getItem() instanceof Boolean) return;
            // a TextField drops line breaks, so committing it would join the lines (e.g. of HTML)
            if (getItem() != null && getItem().toString().matches("(?s).*[\r\n].*")) {
                if (editor != null && isEditable() && getTableColumn().isEditable()) editor.editMultiLine();
                return;
            }
            super.startEdit();
            if (!isEditing()) return;
            Object value = getItem();
            field = new TextField(value == null ? "" : value.toString());
            field.setOnAction(e -> commit());
            field.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) cancelEdit();
            });
            field.focusedProperty().addListener((o, was, focused) -> {
                if (!focused && isEditing()) commit();
            });
            setText(null);
            setGraphic(field);
            field.requestFocus();
            field.selectAll();
        }

        /** Commits the typed text, except an untouched empty field on a NULL (that would make it ''). */
        private void commit() {
            String text = field.getText();
            if (getItem() == null && text.isEmpty()) {
                cancelEdit();
            } else {
                commitEdit(text);
            }
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setGraphic(null);
            updateItem(getItem(), isEmpty());
        }

        @Override
        public void commitEdit(Object newValue) {
            super.commitEdit(newValue);
            setGraphic(null);
        }

        @Override
        protected void updateItem(Object value, boolean empty) {
            super.updateItem(value, empty);
            getStyleClass().removeAll("null-value", "cmp-cell-changed", "cell-edited");
            setStyle(null);
            if (isEditing()) return;
            if (!empty && editor != null && getTableRow() != null && getTableRow().getItem() != null
                    && editor.isEdited(getTableRow().getItem(), (Integer) getTableColumn().getUserData())) {
                getStyleClass().add("cell-edited");
            }
            setGraphic(null);
            if (empty) {
                setText(null);
            } else if (value instanceof Boolean b) {
                setText(null);
                check.setSelected(b);
                check.setMouseTransparent(!(editor != null && getTableView().isEditable() && getTableColumn().isEditable()));
                setGraphic(check);
            } else if (value instanceof StatusValue sv) {
                String color = Themes.statusColor(sv.status());
                setText(sv.label());
                setStyle("-fx-background-color: " + color + "; -fx-text-fill: " + StatusValue.textColorOn(color)
                        + "; -fx-font-weight: bold;");
            } else if (value instanceof ComparedValue cv) {
                setText(cv.toString().replace('\n', '↵'));
                if (cv.differs()) getStyleClass().add("cmp-cell-changed");
            } else {
                setText(oneLine(value));
                if (value == null || value instanceof BlobValue) getStyleClass().add("null-value");
            }
        }
    }

    /** A value as one line of a cell: line breaks as a symbol, long text cut; the value panel has it all. */
    static String oneLine(Object value) {
        if (value == null) return "[NULL]";
        String s = value.toString();
        if (value instanceof BlobValue) return s;
        if (s.length() > MAX_CELL_CHARS) s = s.substring(0, MAX_CELL_CHARS) + "…";
        return s.replace("\r\n", "↵").replace('\n', '↵').replace('\r', '↵');
    }
}
