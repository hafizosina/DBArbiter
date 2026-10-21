package com.dbadmin.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Asks for the values of ":name" parameters. The result maps each name to its value, or null for SQL NULL. */
class ParamDialog extends Dialog<Map<String, String>> {

    ParamDialog(Window owner, Collection<String> names, Map<String, String> previous) {
        initOwner(owner);
        setTitle("Parameters");
        setHeaderText("Enter parameter values");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        field.setMinWidth(260);
        grid.getColumnConstraints().addAll(new ColumnConstraints(), field, new ColumnConstraints());

        Map<String, TextField> fields = new LinkedHashMap<>();
        Map<String, CheckBox> nulls = new LinkedHashMap<>();
        int row = 0;
        for (String name : names) {
            TextField value = new TextField();
            CheckBox isNull = new CheckBox("NULL");
            value.disableProperty().bind(isNull.selectedProperty());
            if (previous.containsKey(name)) {
                String v = previous.get(name);
                if (v == null) isNull.setSelected(true);
                else value.setText(v);
            }
            Label label = new Label(":" + name);
            label.getStyleClass().add("param-name");
            grid.addRow(row++, label, value, isNull);
            fields.put(name, value);
            nulls.put(name, isNull);
        }
        Label hint = new Label("Values are converted to the column's type by the database. Text needs no quotes.");
        hint.getStyleClass().add("hint-small");
        hint.setWrapText(true);
        grid.add(hint, 0, row, 3, 1);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            Map<String, String> values = new LinkedHashMap<>();
            fields.forEach((name, f) -> values.put(name, nulls.get(name).isSelected() ? null : f.getText()));
            return values;
        });
        setOnShown(e -> fields.values().stream().findFirst().ifPresent(f -> {
            f.requestFocus();
            f.selectAll();
        }));
    }
}
