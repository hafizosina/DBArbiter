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

        /** What a row is now; NEW and DELETED rows are not saved yet, SAVED_NEW rows were inserted but not read back. */
        RowState state(List<Object> row);

        /** A new row after {@code after} (null: at the end); a copy of {@code copyOf}, or empty when null. */
        void addRow(List<Object> after, List<Object> copyOf);

        /** Marks the rows to be deleted (new rows simply go). */
        void deleteRows(List<List<Object>> rows);

        /** Keeps rows marked to be deleted after all. */
        void restoreRows(List<List<Object>> rows);
    }

    enum RowState { SHOWN, NEW, DELETED, SAVED_NEW }

    private final ReadOnlyObjectWrapper<CellValue> selectedCell = new ReadOnlyObjectWrapper<>();
    private TableData data;
    private Set<Integer> editableColumns = Set.of();
    /** Columns a new row can be given a value in (the primary key too). */
    private Set<Integer> newRowColumns = Set.of();
    private Editor editor;

    DataGrid() {
        setPlaceholder(new Label("No rows"));
        setFixedCellSize(ROW_HEIGHT);
        setRowFactory(t -> new GridRow());
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        getSelectionModel().setCellSelectionEnabled(true);
        getFocusModel().focusedCellProperty().addListener((o, a, pos) -> {
            publishFocusedCell();
            markCurrentCell();
        });
        // Ctrl+C: the selected cells; Ctrl+Shift+C: with the column names (not while a cell is being edited)
        addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.C && e.isShortcutDown() && !e.isAltDown() && getEditingCell() == null) {
                copySelection(e.isShiftDown());
                e.consume();
            }
        });
    }

    /** Puts the selected cells on the clipboard, as tab-separated rows (what spreadsheets paste as a grid). */
    void copySelection(boolean withHeader) {
        List<TablePosition> cells = getSelectionModel().getSelectedCells();
        if (cells.isEmpty() || data == null) return;
        java.util.TreeSet<Integer> rows = new java.util.TreeSet<>();
        java.util.Set<Integer> picked = new java.util.HashSet<>();
        for (TablePosition<?, ?> p : cells) {
            if (p.getTableColumn() == null) continue;
            rows.add(p.getRow());
            picked.add((Integer) p.getTableColumn().getUserData());
        }
        // columns in the order they are shown (the user may have dragged them)
        List<Integer> columns = getVisibleLeafColumns().stream().map(c -> (Integer) c.getUserData()).filter(picked::contains).toList();
        java.util.Set<String> selected = new java.util.HashSet<>();
        for (TablePosition<?, ?> p : cells) {
            if (p.getTableColumn() != null) selected.add(p.getRow() + ":" + p.getTableColumn().getUserData());
        }
        List<List<Object>> table = new java.util.ArrayList<>();
        for (int r : rows) {
            List<Object> row = new java.util.ArrayList<>();
            for (int c : columns) row.add(selected.contains(r + ":" + c) ? getItems().get(r).get(c) : null);
            table.add(row);
        }
        List<String> header = withHeader ? columns.stream().map(c -> data.columns().get(c)).toList() : null;
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(CopyText.tsv(table, header));
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }


    /** The focused cell, outlined (app.css) even while the focus is elsewhere, e.g. typing in the value panel. */
    private static final javafx.css.PseudoClass CURRENT = javafx.css.PseudoClass.getPseudoClass("current-cell");
    /** The row of the focused cell, tinted (app.css) so the whole row is easy to follow. */
    private static final javafx.css.PseudoClass CURRENT_ROW = javafx.css.PseudoClass.getPseudoClass("current-row");
    /** A cell holding the text of the find bar (GridFind), marked (app.css). */
    private static final javafx.css.PseudoClass FIND_HIT = javafx.css.PseudoClass.getPseudoClass("find-hit");
    private String findText = "";
    private boolean findMatchCase;

    /** Marks the cells that hold {@code text} (empty: none). */
    void setFind(String text, boolean matchCase) {
        findText = text == null ? "" : text;
        findMatchCase = matchCase;
        refresh();
    }

    /** True if the cell value holds {@code text}: its whole value, both sides of a compared value. */
    static boolean holds(Object value, String text, boolean matchCase) {
        if (text.isEmpty() || value == null) return false;
        String s = value instanceof ComparedValue cv ? String.valueOf(cv.source()) + "\n" + cv.target() : value.toString();
        return matchCase ? s.contains(text) : s.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }

    private void markCurrentCell() {
        for (javafx.scene.Node n : lookupAll(".table-cell")) {
            if (n instanceof ValueCell cell) cell.markCurrent();
        }
        for (javafx.scene.Node n : lookupAll(".table-row-cell")) {
            if (n instanceof GridRow row) row.markCurrent();
        }
    }

    /** A row: green when new, red when deleted (app.css), and tinted while it holds the focused cell. */
    private class GridRow extends javafx.scene.control.TableRow<List<Object>> {
        @Override
        protected void updateItem(List<Object> row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeAll("row-new", "row-deleted");
            markCurrent();
            if (empty || row == null || editor == null) return;
            RowState state = editor.state(row);
            if (state == RowState.NEW) getStyleClass().add("row-new");
            else if (state == RowState.DELETED) getStyleClass().add("row-deleted");
        }

        @Override
        public void updateIndex(int i) {
            super.updateIndex(i);
            markCurrent(); // rows are reused for other items while scrolling
        }

        void markCurrent() {
            TablePosition<List<Object>, ?> pos = getFocusModel().getFocusedCell();
            pseudoClassStateChanged(CURRENT_ROW, !isEmpty() && getIndex() >= 0 && pos != null && pos.getRow() == getIndex());
        }
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

    /** The focused cell if it can be edited now, else null. */
    EditableCell focusedEditableCell() {
        TablePosition<List<Object>, ?> pos = getFocusModel().getFocusedCell();
        if (editor == null || pos == null || pos.getTableColumn() == null || pos.getRow() < 0
                || pos.getRow() >= getItems().size()) {
            return null;
        }
        int c = (Integer) pos.getTableColumn().getUserData();
        List<Object> row = getItems().get(pos.getRow());
        return isEditable(row, c) ? new EditableCell(row, c) : null;
    }

    /**
     * True if the cell (original column index) can be edited now: of a shown row, in a column that is not the
     * primary key; of a new row, any table column; never of a row marked to be deleted or one already inserted.
     */
    boolean isEditable(List<Object> row, int column) {
        if (editor == null || row == null) return false;
        return switch (editor.state(row)) {
            case SHOWN -> editableColumns.contains(column);
            case NEW -> newRowColumns.contains(column);
            case DELETED, SAVED_NEW -> false;
        };
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
     * Lets the user edit rows: {@code columns} (original indexes) of the rows shown, {@code newRowColumns} of new
     * rows. Double-click a cell, Enter to set, Esc to cancel; right-click for Set NULL / Revert and to add,
     * duplicate or delete rows. No new-row columns: read-only again.
     */
    void setEditing(Set<Integer> columns, Set<Integer> newRowColumns, Editor editor) {
        boolean on = !newRowColumns.isEmpty();
        this.editableColumns = on ? columns : Set.of();
        this.newRowColumns = on ? newRowColumns : Set.of();
        this.editor = on ? editor : null;
        setEditable(on);
        for (TableColumn<List<Object>, ?> col : getColumns()) col.setEditable(this.newRowColumns.contains((Integer) col.getUserData()));
        updateMenu();
        refresh();
    }

    /** The statements of Copy as SQL: the selected rows as INSERT, UPDATE or DELETE of their table. */
    enum SqlKind { INSERT, UPDATE, DELETE }

    /** Writes rows as SQL for Copy as SQL (see {@link #setSqlCopier}). */
    interface SqlCopier {
        /** One statement per row (without ";"); rows it cannot write (e.g. a new row for UPDATE) are left out. */
        List<String> statements(SqlKind kind, List<List<Object>> rows) throws Exception;

        /** Whether the rows hold the whole primary key, which UPDATE and DELETE need. */
        boolean hasKey();
    }

    private static final java.util.logging.Logger LOG = com.dbadmin.service.AppLog.get(DataGrid.class);
    private SqlCopier copier;

    /** Adds Copy as INSERT / UPDATE / DELETE to the right-click menu (also when the rows cannot be edited); null removes it. */
    void setSqlCopier(SqlCopier copier) {
        this.copier = copier;
        updateMenu();
    }

    private void updateMenu() {
        ContextMenu menu = editor != null ? editMenu() : null;
        if (copier != null) {
            if (menu == null) menu = new ContextMenu();
            else menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
            menu.getItems().add(copySqlMenu());
        }
        setContextMenu(menu);
    }

    private javafx.scene.control.Menu copySqlMenu() {
        javafx.scene.control.Menu copy = new javafx.scene.control.Menu("Copy as SQL");
        for (SqlKind kind : SqlKind.values()) {
            MenuItem item = new MenuItem(kind.name());
            item.setOnAction(e -> copySql(kind));
            copy.getItems().add(item);
        }
        copy.setOnShowing(e -> {
            boolean key = copier != null && copier.hasKey();
            copy.getItems().get(SqlKind.UPDATE.ordinal()).setDisable(!key);
            copy.getItems().get(SqlKind.DELETE.ordinal()).setDisable(!key);
        });
        return copy;
    }

    /** Puts the selected rows on the clipboard as statements, one per line; nothing is run. */
    void copySql(SqlKind kind) {
        List<List<Object>> rows = selectedRows();
        if (copier == null || rows.isEmpty()) return;
        List<String> sql;
        try {
            sql = copier.statements(kind, rows);
        } catch (Exception ex) {
            LOG.log(java.util.logging.Level.WARNING, "Copy as SQL failed", ex);
            return;
        }
        if (sql.isEmpty()) return;
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(String.join(";\n", sql) + ";\n");
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    private ContextMenu editMenu() {
        MenuItem setNull = new MenuItem("Set NULL");
        setNull.setOnAction(e -> forFocusedEditableCell((row, c) -> editor.edited(row, c, null)));
        MenuItem setDefault = new MenuItem("Set DEFAULT");
        setDefault.setOnAction(e -> forFocusedEditableCell((row, c) -> editor.edited(row, c, PendingEdits.Default.VALUE)));
        MenuItem revert = new MenuItem("Revert");
        revert.setOnAction(e -> forFocusedEditableCell((row, c) -> editor.revert(row, c)));
        MenuItem add = new MenuItem("Add Row");
        add.setOnAction(e -> editor.addRow(focusedRow(), null));
        MenuItem duplicate = new MenuItem("Duplicate Row");
        duplicate.setOnAction(e -> {
            List<List<Object>> rows = selectedRows();
            for (int i = rows.size() - 1; i >= 0; i--) editor.addRow(rows.get(rows.size() - 1), rows.get(i));
        });
        MenuItem delete = new MenuItem("Delete Row");
        delete.setOnAction(e -> editor.deleteRows(selectedRows()));
        MenuItem restore = new MenuItem("Keep Row");
        restore.setOnAction(e -> editor.restoreRows(selectedRows()));
        ContextMenu menu = new ContextMenu(setNull, setDefault, revert, new javafx.scene.control.SeparatorMenuItem(),
                add, duplicate, delete, restore);
        menu.setOnShowing(e -> {
            EditableCell cell = focusedEditableCell();
            setNull.setDisable(cell == null);
            setDefault.setVisible(cell != null && editor.state(cell.row()) == RowState.NEW);
            revert.setDisable(cell == null || !editor.isEdited(cell.row(), cell.column()));
            List<List<Object>> rows = selectedRows();
            String n = rows.size() > 1 ? " " + rows.size() + " Rows" : " Row";
            duplicate.setText("Duplicate" + n);
            delete.setText("Delete" + n);
            restore.setText("Keep" + n);
            boolean anyShown = rows.stream().anyMatch(r -> editor.state(r) == RowState.SHOWN || editor.state(r) == RowState.NEW);
            boolean anyDeleted = rows.stream().anyMatch(r -> editor.state(r) == RowState.DELETED);
            duplicate.setDisable(rows.isEmpty());
            delete.setVisible(!anyDeleted || anyShown);
            delete.setDisable(!anyShown);
            restore.setVisible(anyDeleted);
        });
        return menu;
    }

    private List<Object> focusedRow() {
        int r = getFocusModel().getFocusedIndex();
        return r >= 0 && r < getItems().size() ? getItems().get(r) : null;
    }

    /** The rows of the selected cells, top to bottom; the focused row when none is selected. */
    List<List<Object>> selectedRows() {
        java.util.TreeSet<Integer> indexes = new java.util.TreeSet<>();
        for (TablePosition<?, ?> p : getSelectionModel().getSelectedCells()) indexes.add(p.getRow());
        if (indexes.isEmpty() && focusedRow() != null) indexes.add(getFocusModel().getFocusedIndex());
        return indexes.stream().filter(i -> i >= 0 && i < getItems().size()).map(getItems()::get).toList();
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
        newRowColumns = Set.of();
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
            if (getTableRow() == null || !DataGrid.this.isEditable(getTableRow().getItem(), (Integer) getTableColumn().getUserData())) return;
            // a TextField drops line breaks, so committing it would join the lines (e.g. of HTML)
            if (getItem() != null && getItem().toString().matches("(?s).*[\r\n].*")) {
                if (editor != null && isEditable() && getTableColumn().isEditable()) editor.editMultiLine();
                return;
            }
            super.startEdit();
            if (!isEditing()) return;
            Object value = getItem();
            field = new TextField(value == null || value == PendingEdits.Default.VALUE ? "" : value.toString());
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
            if ((getItem() == null || getItem() == PendingEdits.Default.VALUE) && text.isEmpty()) {
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

        void markCurrent() {
            TableView<List<Object>> table = getTableView();
            pseudoClassStateChanged(CURRENT, !isEmpty() && table != null && getIndex() >= 0
                    && table.getFocusModel().isFocused(getIndex(), getTableColumn()));
        }

        @Override
        public void updateIndex(int i) {
            super.updateIndex(i);
            markCurrent(); // cells are reused for other rows while scrolling
        }

        @Override
        protected void updateItem(Object value, boolean empty) {
            super.updateItem(value, empty);
            markCurrent();
            pseudoClassStateChanged(FIND_HIT, !empty && holds(value, findText, findMatchCase));
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
                check.setMouseTransparent(!DataGrid.this.isEditable(getTableRow() == null ? null : getTableRow().getItem(), (Integer) getTableColumn().getUserData()));
                setGraphic(check);
            } else if (value instanceof StatusValue sv) {
                String color = Themes.statusColor(sv.status());
                setText(sv.label());
                setStyle("-fx-background-color: " + color + "; -fx-text-fill: " + StatusValue.textColorOn(color)
                        + "; -fx-font-weight: bold;");
            } else if (value instanceof ComparedValue cv) {
                javafx.scene.Node checks = comparedChecks(cv);
                setText(checks != null ? null : cv.toString().replace('\n', '↵'));
                setGraphic(checks);
                if (cv.differs()) getStyleClass().add("cmp-cell-changed");
            } else {
                setText(oneLine(value));
                if (value == null || value instanceof BlobValue || value == PendingEdits.Default.VALUE) getStyleClass().add("null-value");
            }
        }
    }

    /**
     * A compared value of a boolean column as check boxes (read-only): one, or "☐ → ☑" when the two sides differ;
     * null for a value that is not boolean.
     */
    static javafx.scene.Node comparedChecks(ComparedValue cv) {
        if (!(cv.source() instanceof Boolean) && !(cv.target() instanceof Boolean)) return null;
        if (!cv.inTarget()) return readOnlyCheck(cv.source());
        if (!cv.inSource() || !cv.valuesDiffer()) return readOnlyCheck(cv.inSource() ? cv.source() : cv.target());
        javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(4, readOnlyCheck(cv.source()), new Label("→"), readOnlyCheck(cv.target()));
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return box;
    }

    private static javafx.scene.Node readOnlyCheck(Object value) {
        if (!(value instanceof Boolean b)) return new Label(oneLine(value)); // NULL
        CheckBox check = new CheckBox();
        check.setSelected(b);
        check.setMouseTransparent(true);
        check.setFocusTraversable(false);
        return check;
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
