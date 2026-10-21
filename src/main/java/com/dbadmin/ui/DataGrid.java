package com.dbadmin.ui;

import com.dbadmin.model.BlobValue;
import com.dbadmin.model.ColumnType;
import com.dbadmin.model.TableData;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.sql.Types;
import java.util.List;
import java.util.Locale;

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

    private final ReadOnlyObjectWrapper<CellValue> selectedCell = new ReadOnlyObjectWrapper<>();
    private TableData data;

    DataGrid() {
        setPlaceholder(new Label("No rows"));
        setFixedCellSize(ROW_HEIGHT);
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        getSelectionModel().setCellSelectionEnabled(true);
        getFocusModel().focusedCellProperty().addListener((o, a, pos) -> {
            if (data == null || pos == null || pos.getRow() < 0 || pos.getTableColumn() == null
                    || pos.getRow() >= getItems().size()) {
                selectedCell.set(null);
                return;
            }
            // the column's original index survives the user reordering columns
            int c = (Integer) pos.getTableColumn().getUserData();
            selectedCell.set(new CellValue(data.columns().get(c), data.type(c), getItems().get(pos.getRow()).get(c)));
        });
    }

    ReadOnlyObjectProperty<CellValue> selectedCellProperty() {
        return selectedCell.getReadOnlyProperty();
    }

    void show(TableData data) {
        this.data = data;
        selectedCell.set(null);
        getColumns().clear();
        for (int i = 0; i < data.columns().size(); i++) {
            final int index = i;
            TableColumn<List<Object>, Object> col = new TableColumn<>(data.columns().get(i));
            col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().get(index)));
            col.setCellFactory(c -> new ValueCell());
            col.setUserData(index);
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

    private static class ValueCell extends TableCell<List<Object>, Object> {
        @Override
        protected void updateItem(Object value, boolean empty) {
            super.updateItem(value, empty);
            getStyleClass().remove("null-value");
            if (empty) {
                setText(null);
            } else if (value == null || value instanceof BlobValue) {
                setText(value == null ? "[NULL]" : value.toString());
                getStyleClass().add("null-value");
            } else {
                // one line per row: show line breaks as a symbol, full text is in the value panel
                String s = value.toString();
                if (s.length() > MAX_CELL_CHARS) s = s.substring(0, MAX_CELL_CHARS) + "…";
                setText(s.replace("\r\n", "↵").replace('\n', '↵').replace('\r', '↵'));
            }
        }
    }
}
