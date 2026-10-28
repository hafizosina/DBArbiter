package com.dbadmin.ui;

import com.dbadmin.model.TableData;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

/**
 * Under the value panel, for a cell of a foreign key column: the row it points to, as column / value pairs
 * ("→ m_product (id = 7)"). Hidden for other cells.
 */
class ReferencedRowView extends VBox {

    private final Label header = new Label();
    private final TableView<Integer> fields = new TableView<>();
    private TableData row = new TableData(java.util.List.of(), java.util.List.of());

    ReferencedRowView() {
        super(4);
        header.getStyleClass().add("form-title");
        header.setWrapText(true);
        TableColumn<Integer, String> name = new TableColumn<>("Column");
        name.setCellValueFactory(c -> new ReadOnlyStringWrapper(row.columns().get(c.getValue())));
        name.setPrefWidth(140);
        name.setSortable(false);
        TableColumn<Integer, Object> value = new TableColumn<>("Value");
        value.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(row.rows().isEmpty() ? null : row.rows().get(0).get(c.getValue())));
        value.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Object v, boolean empty) {
                super.updateItem(v, empty);
                getStyleClass().remove("null-value");
                setText(empty ? null : DataGrid.oneLine(v));
                if (!empty && v == null) getStyleClass().add("null-value");
            }
        });
        value.setSortable(false);
        value.prefWidthProperty().bind(fields.widthProperty().subtract(name.widthProperty()).subtract(20));
        fields.getColumns().addAll(java.util.List.of(name, value));
        fields.setFixedCellSize(24);
        fields.setPrefHeight(200);
        setPadding(new Insets(6, 0, 0, 0));
        getChildren().addAll(header, fields);
        hide();
    }

    /** Shows the row (or says there is none) under {@code title}; {@code data} with no rows: nothing found. */
    void show(String title, TableData data) {
        show(title, data, data.rows().isEmpty() ? "no such row" : null);
    }

    /** No row to look up (e.g. the key is NULL): the title and why. */
    void showNothing(String title, String why) {
        show(title, new TableData(java.util.List.of(), java.util.List.of()), why);
    }

    private void show(String title, TableData data, String note) {
        row = data;
        header.setText(title + (note == null ? "" : "   · " + note));
        fields.setItems(FXCollections.observableArrayList(java.util.stream.IntStream.range(0, data.rows().isEmpty() ? 0 : data.columns().size()).boxed().toList()));
        fields.setPlaceholder(new Label(data.rows().isEmpty() ? "No row has these values" : ""));
        fields.refresh();
        setVisible(true);
        setManaged(true);
    }

    void hide() {
        setVisible(false);
        setManaged(false);
    }

    String title() {
        return header.getText();
    }

    TableData shown() {
        return row;
    }
}
