package com.dbadmin.ui;

import com.dbadmin.model.TableData;
import com.dbadmin.service.transfer.Csv;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shows a CSV file before it is imported: which file column goes into which table column (by name,
 * or skipped), and its first rows. The result is what to insert: table columns and text values.
 */
class ImportDialog extends Dialog<ImportDialog.Plan> {

    private static final String SKIP = "(skip)";
    private static final int PREVIEW_ROWS = 100;

    /** Rows to insert: the values of {@code columns}, null for NULL. */
    record Plan(List<String> columns, List<List<String>> rows) {
    }

    private final String text;
    private final List<String> tableColumns;
    private final ComboBox<String> delimiter = new ComboBox<>();
    private final CheckBox header = new CheckBox("First row has the column names");
    private final CheckBox emptyIsNull = new CheckBox("Empty values become NULL");
    private final GridPane mapping = new GridPane();
    private final DataGrid preview = new DataGrid();
    private final Label summary = new Label();
    private final List<ComboBox<String>> targets = new ArrayList<>();
    private List<List<String>> records = List.of();

    ImportDialog(Window owner, String fileName, String text, String table, List<String> tableColumns, List<String> primaryKey) {
        initOwner(owner);
        this.text = text;
        this.tableColumns = tableColumns;
        setTitle("Import");
        setHeaderText("Import " + fileName + " into " + table);
        setResizable(true);

        String firstLine = text.lines().findFirst().orElse("");
        delimiter.getItems().addAll("Comma  ,", "Semicolon  ;", "Tab", "Pipe  |");
        delimiter.setValue(switch (Csv.guessDelimiter(firstLine)) {
            case ';' -> "Semicolon  ;";
            case '\t' -> "Tab";
            case '|' -> "Pipe  |";
            default -> "Comma  ,";
        });
        header.setSelected(true);
        emptyIsNull.setSelected(true);
        delimiter.valueProperty().addListener((o, a, b) -> reload());
        header.selectedProperty().addListener((o, a, b) -> reload());
        HBox options = new HBox(12, new Label("Delimiter:"), delimiter, header, emptyIsNull);
        options.setAlignment(Pos.CENTER_LEFT);

        mapping.setHgap(10);
        mapping.setVgap(4);
        ScrollPane mappingScroll = new ScrollPane(mapping);
        mappingScroll.setFitToWidth(true);
        mappingScroll.setPrefHeight(170);
        Label hint = new Label(primaryKey.isEmpty() ? "Pick the table column for each file column, or (skip)."
                : "Pick the table column for each file column, or (skip). With the primary key (" + String.join(", ", primaryKey)
                + ") mapped, rows whose key already exists are skipped; skip it to let the database number new rows.");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint-small");
        preview.setPrefHeight(220);
        summary.getStyleClass().add("hint-small");

        VBox content = new VBox(8, options, new Label("Columns:"), mappingScroll, hint, new Label("First rows:"), preview, summary);
        content.setPadding(new Insets(12, 16, 8, 16));
        content.setPrefWidth(820);
        VBox.setVgrow(preview, Priority.ALWAYS);
        getDialogPane().setContent(content);

        ButtonType importButton = new ButtonType("Import…", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(importButton, ButtonType.CANCEL);
        Node importNode = getDialogPane().lookupButton(importButton);
        importNode.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            String problem = problem();
            if (problem != null) {
                summary.setText("⚠ " + problem);
                e.consume();
            }
        });
        setResultConverter(b -> b == importButton ? plan() : null);
        reload();
    }

    private char delimiterChar() {
        return switch (delimiter.getValue().charAt(0)) {
            case 'S' -> ';';
            case 'T' -> '\t';
            case 'P' -> '|';
            default -> ',';
        };
    }

    /** Parses the file again (delimiter or header changed) and rebuilds the mapping and preview. */
    private void reload() {
        try {
            records = Csv.read(new StringReader(text), delimiterChar());
        } catch (IOException e) {
            records = List.of();
        }
        int width = records.stream().mapToInt(List::size).max().orElse(0);
        List<String> fileColumns = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            String name = header.isSelected() && !records.isEmpty() && i < records.get(0).size() ? records.get(0).get(i).strip() : "";
            fileColumns.add(name.isEmpty() ? "Column " + (i + 1) : name);
        }
        mapping.getChildren().clear();
        targets.clear();
        for (int i = 0; i < width; i++) {
            ComboBox<String> target = new ComboBox<>();
            target.getItems().add(SKIP);
            target.getItems().addAll(tableColumns);
            String fileColumn = fileColumns.get(i);
            // by name; without a header row, by position
            String match = header.isSelected()
                    ? tableColumns.stream().filter(c -> c.equalsIgnoreCase(fileColumn)).findFirst().orElse(SKIP)
                    : i < tableColumns.size() ? tableColumns.get(i) : SKIP;
            target.setValue(match);
            target.valueProperty().addListener((o, a, b) -> updateSummary());
            targets.add(target);
            mapping.addRow(i, new Label(fileColumn), new Label("→"), target);
        }
        List<List<Object>> shown = new ArrayList<>();
        for (List<String> r : dataRows().subList(0, Math.min(PREVIEW_ROWS, dataRows().size()))) shown.add(new ArrayList<>(pad(r, width)));
        preview.show(new TableData(fileColumns, shown));
        updateSummary();
    }

    private List<List<String>> dataRows() {
        return header.isSelected() && !records.isEmpty() ? records.subList(1, records.size()) : records;
    }

    private static List<String> pad(List<String> row, int width) {
        List<String> r = new ArrayList<>(row);
        while (r.size() < width) r.add("");
        return r;
    }

    private void updateSummary() {
        long mapped = targets.stream().filter(t -> !SKIP.equals(t.getValue())).count();
        String problem = problem();
        summary.setText(problem != null ? "⚠ " + problem
                : dataRows().size() + " row(s) · " + mapped + " of " + targets.size() + " column(s) imported");
    }

    private String problem() {
        Set<String> used = new HashSet<>();
        for (ComboBox<String> t : targets) {
            if (SKIP.equals(t.getValue())) continue;
            if (!used.add(t.getValue().toLowerCase(Locale.ROOT))) return t.getValue() + " is picked for two file columns.";
        }
        if (used.isEmpty()) return "Pick at least one table column.";
        if (dataRows().isEmpty()) return "The file has no rows to import.";
        return null;
    }

    private Plan plan() {
        List<String> columns = new ArrayList<>();
        List<Integer> from = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            if (SKIP.equals(targets.get(i).getValue())) continue;
            columns.add(targets.get(i).getValue());
            from.add(i);
        }
        boolean nulls = emptyIsNull.isSelected();
        List<List<String>> rows = new ArrayList<>();
        for (List<String> r : dataRows()) {
            List<String> values = new ArrayList<>(from.size());
            for (int i : from) {
                String v = i < r.size() ? r.get(i) : "";
                values.add(nulls && v.isEmpty() ? null : v);
            }
            rows.add(values);
        }
        return new Plan(columns, rows);
    }
}
