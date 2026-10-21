package com.dbadmin.ui;

import com.dbadmin.model.TableData;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.util.List;

/** A read-only TableView that can show any {@link TableData}. */
class DataGrid extends TableView<List<Object>> {

    private static final int MAX_CELL_CHARS = 500;

    DataGrid() {
        setPlaceholder(new Label("No rows"));
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        getSelectionModel().setCellSelectionEnabled(true);
    }

    void show(TableData data) {
        getColumns().clear();
        for (int i = 0; i < data.columns().size(); i++) {
            final int index = i;
            TableColumn<List<Object>, Object> col = new TableColumn<>(data.columns().get(i));
            col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().get(index)));
            col.setCellFactory(c -> new ValueCell());
            col.setPrefWidth(Math.max(80, Math.min(260, data.columns().get(i).length() * 10 + 30)));
            getColumns().add(col);
        }
        setItems(FXCollections.observableArrayList(data.rows()));
    }

    private static class ValueCell extends TableCell<List<Object>, Object> {
        @Override
        protected void updateItem(Object value, boolean empty) {
            super.updateItem(value, empty);
            getStyleClass().remove("null-value");
            if (empty) {
                setText(null);
            } else if (value == null) {
                setText("[NULL]");
                getStyleClass().add("null-value");
            } else {
                String s = value.toString();
                setText(s.length() > MAX_CELL_CHARS ? s.substring(0, MAX_CELL_CHARS) + "…" : s);
            }
        }
    }
}
