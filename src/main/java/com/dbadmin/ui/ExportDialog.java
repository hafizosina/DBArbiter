package com.dbadmin.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;

import java.util.List;

/** Asks how to export a table's rows: the file format, its options, and (in a table tab) which rows. */
class ExportDialog extends Dialog<ExportDialog.Choice> {

    enum Format {
        CSV("CSV", ".csv"), XLSX("Excel", ".xlsx"), SQL("SQL INSERT", ".sql"), JSON("JSON", ".json");

        final String label;
        final String extension;

        Format(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }
    }

    /**
     * {@code existsBy}: SQL INSERT skips a row when one with the same values in these columns exists (empty: it
     * does not check); {@code currentQuery}: export the rows of the table tab's current query instead of the whole table.
     */
    record Choice(Format format, char delimiter, boolean header, List<String> existsBy, boolean currentQuery) {
    }

    /** @param primaryKey checked first for "already exists" (any columns can be picked instead) */
    ExportDialog(Window owner, String table, List<String> columns, List<String> primaryKey, boolean canUseQuery) {
        initOwner(owner);
        setTitle("Export");
        setHeaderText("Export the rows of " + table);

        ToggleGroup formats = new ToggleGroup();
        HBox formatBox = new HBox(12);
        for (Format f : Format.values()) {
            RadioButton b = new RadioButton(f.label);
            b.setUserData(f);
            b.setToggleGroup(formats);
            formatBox.getChildren().add(b);
        }
        formats.selectToggle(formats.getToggles().get(0));

        ComboBox<String> delimiter = new ComboBox<>();
        delimiter.getItems().addAll("Comma  ,", "Semicolon  ;", "Tab");
        delimiter.setValue("Comma  ,");
        CheckBox header = new CheckBox("Column names in the first row");
        header.setSelected(true);
        HBox csvOptions = new HBox(10, new Label("Delimiter:"), delimiter, header);
        csvOptions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // which columns find a row that already exists: the primary key, or any others (e.g. a unique code)
        CheckBox skipExisting = new CheckBox("Skip rows that already exist, found by");
        MenuButton keyColumns = new MenuButton();
        for (String c : columns) {
            CheckMenuItem item = new CheckMenuItem(c);
            item.setSelected(primaryKey.stream().anyMatch(c::equalsIgnoreCase));
            keyColumns.getItems().add(item);
        }
        Runnable showKeys = () -> {
            List<String> picked = picked(keyColumns);
            keyColumns.setText(picked.isEmpty() ? "choose columns…" : String.join(", ", picked));
        };
        keyColumns.getItems().forEach(i -> ((CheckMenuItem) i).selectedProperty().addListener((o, a, b) -> showKeys.run()));
        showKeys.run();
        keyColumns.disableProperty().bind(skipExisting.selectedProperty().not().or(skipExisting.disabledProperty()));
        Label keyHint = new Label(primaryKey.isEmpty() ? "(the table has no primary key)" : "(instead of failing)");
        keyHint.getStyleClass().add("hint-small");
        HBox sqlOptions = new HBox(8, skipExisting, keyColumns, keyHint);
        sqlOptions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        ToggleGroup rows = new ToggleGroup();
        RadioButton whole = new RadioButton("Whole table");
        RadioButton query = new RadioButton("Current query result (all its rows)");
        whole.setToggleGroup(rows);
        query.setToggleGroup(rows);
        whole.setSelected(true);
        query.setDisable(!canUseQuery);
        HBox rowsBox = new HBox(12, whole, query);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Format:"), formatBox);
        grid.addRow(1, new Label("CSV:"), csvOptions);
        grid.addRow(2, new Label("SQL:"), sqlOptions);
        grid.addRow(3, new Label("Rows:"), rowsBox);
        Runnable update = () -> {
            Format f = (Format) formats.getSelectedToggle().getUserData();
            csvOptions.setDisable(f != Format.CSV);
            skipExisting.setDisable(f != Format.SQL);
        };
        formats.selectedToggleProperty().addListener((o, a, b) -> update.run());
        update.run();
        getDialogPane().setContent(grid);

        ButtonType export = new ButtonType("Export…", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);
        // skipping needs at least one column to find the rows by
        Runnable checkKeys = () -> getDialogPane().lookupButton(export).setDisable(
                skipExisting.isSelected() && !skipExisting.isDisabled() && picked(keyColumns).isEmpty());
        skipExisting.selectedProperty().addListener((o, a, b) -> checkKeys.run());
        skipExisting.disabledProperty().addListener((o, a, b) -> checkKeys.run());
        keyColumns.getItems().forEach(i -> ((CheckMenuItem) i).selectedProperty().addListener((o, a, b) -> checkKeys.run()));
        setResultConverter(b -> b != export ? null : new Choice((Format) formats.getSelectedToggle().getUserData(),
                switch (delimiter.getValue().charAt(0)) {
                    case 'S' -> ';';
                    case 'T' -> '\t';
                    default -> ',';
                }, header.isSelected(), skipExisting.isSelected() ? picked(keyColumns) : List.of(), query.isSelected()));
    }

    private static List<String> picked(MenuButton keyColumns) {
        return keyColumns.getItems().stream().filter(i -> ((CheckMenuItem) i).isSelected()).map(MenuItem::getText).toList();
    }
}
