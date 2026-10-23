package com.dbadmin.ui;

import com.dbadmin.service.SchemaChange;
import javafx.beans.Observable;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Supplier;

/**
 * A form for one structure change (a column, constraint, index or foreign key) with the SQL it makes below,
 * written again on every change. Apply is enabled while the form makes at least one statement; the result is
 * the statements. What the database cannot do, or what is missing, shows instead of the SQL.
 */
class StructureDialog extends Dialog<List<String>> {

    private final GridPane form = new GridPane();
    private final TextArea preview = new TextArea();
    private final Label problem = new Label();
    private final ButtonType apply = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
    /** Kept: while the dialog is shown inside the main window, getDialogPane() is a placeholder (see InAppDialogs). */
    private final Button applyButton;
    private Supplier<List<String>> statements = List::of;
    private List<String> current = List.of();
    private int rows;

    StructureDialog(Window owner, String title, String header) {
        if (owner != null) initOwner(owner);
        setTitle(title);
        setHeaderText(header);
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        field.setMinWidth(320);
        form.getColumnConstraints().addAll(new ColumnConstraints(), field);
        preview.setEditable(false);
        preview.setWrapText(true);
        preview.setPrefRowCount(4);
        preview.getStyleClass().add("sql-messages");
        problem.setWrapText(true);
        problem.getStyleClass().add("error-text-inline");
        problem.managedProperty().bind(problem.visibleProperty());
        preview.managedProperty().bind(preview.visibleProperty());
        VBox content = new VBox(10, form, new Label("SQL"), preview, problem);
        content.setPadding(new Insets(12, 16, 12, 16));
        content.setPrefWidth(620);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        applyButton = (Button) getDialogPane().lookupButton(apply);
        setResultConverter(b -> b == apply ? current : null);
    }

    /** A labelled row of the form. */
    void row(String label, Node field) {
        form.addRow(rows++, new Label(label), field);
    }

    /** A row whose visibility follows {@code shown}. */
    void row(String label, Node field, javafx.beans.value.ObservableBooleanValue shown) {
        Label l = new Label(label);
        form.addRow(rows++, l, field);
        for (Node n : List.of(l, field)) {
            n.visibleProperty().bind(shown);
            n.managedProperty().bind(shown);
        }
    }

    /** What the form makes; written again whenever one of {@code inputs} changes. */
    void statements(Supplier<List<String>> make, Observable... inputs) {
        statements = make;
        for (Observable o : inputs) o.addListener(x -> refresh());
        refresh();
    }

    private void refresh() {
        String error = null;
        try {
            current = statements.get();
            if (current.isEmpty()) error = "Nothing changed yet.";
        } catch (SchemaChange.Unsupported | IllegalArgumentException e) {
            current = List.of();
            error = e.getMessage();
        }
        preview.setText(current.stream().map(s -> s + ";").reduce((a, b) -> a + "\n" + b).orElse(""));
        preview.setVisible(!current.isEmpty());
        problem.setText(error == null ? "" : error);
        problem.setVisible(error != null);
        applyButton.setDisable(current.isEmpty());
    }

    /** The statements the form makes now; empty while it makes none (see {@link #problem()}). */
    List<String> current() {
        return current;
    }

    /** Why the form makes no statement now, or "" when it does. */
    String problem() {
        return problem.getText();
    }

    /** A text field for "a, b" with a ▾ menu that appends one of {@code columns}. */
    static HBox columnsField(TextField text, List<String> columns, String prompt) {
        text.setPromptText(prompt);
        MenuButton pick = new MenuButton();
        pick.setTooltip(new javafx.scene.control.Tooltip("Add a column"));
        for (String c : columns) {
            MenuItem item = new MenuItem(c);
            item.setMnemonicParsing(false);
            item.setOnAction(e -> {
                String now = text.getText().strip();
                text.setText(now.isEmpty() ? c : now.replaceAll(",\\s*$", "") + ", " + c);
            });
            pick.getItems().add(item);
        }
        HBox box = new HBox(6, text, pick);
        HBox.setHgrow(text, Priority.ALWAYS);
        return box;
    }
}
