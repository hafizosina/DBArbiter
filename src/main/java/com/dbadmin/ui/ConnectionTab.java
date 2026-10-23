package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.service.DatabaseSession;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.function.Consumer;

/**
 * Create or edit a connection in a tab of the main window. Save hands a new
 * {@link ConnectionConfig} (same id when editing) to {@code onSave}; the input is never modified.
 */
class ConnectionTab extends Tab {

    private final ComboBox<DbType> type = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final TextField database = new TextField();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final CheckBox savePassword = new CheckBox("Save password");
    private final CheckBox readOnly = new CheckBox("Read-only (block all changes to the database)");
    /** Index = warning level (see QueryRisk). */
    private final ComboBox<String> warningLevel = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
            "Nothing", "Dangerous only: drop, truncate, every row", "Any change", "Any change, inserts too"));
    private final Label databaseLabel = new Label("Database:");
    private final Button browse = new Button("Browse…");
    private final Label testResult = new Label();
    private final String editedId;

    ConnectionTab(ConnectionConfig existing, Consumer<ConnectionConfig> onSave) {
        editedId = existing == null ? null : existing.getId();
        setText(existing == null ? "New Connection" : "Edit: " + existing.getName());
        setGraphic(TabBadge.of(TabBadge.Kind.CONNECTION));
        Label title = new Label(existing == null ? "Connect to a database" : "Edit \"" + existing.getName() + "\"");
        title.getStyleClass().add("form-title");

        type.getItems().addAll(DbType.values());
        port.setPrefColumnCount(6);
        testResult.setWrapText(true);
        testResult.setMaxWidth(420);

        ConnectionConfig base = existing != null ? existing : new ConnectionConfig();
        type.setValue(base.getType());
        name.setText(base.getName());
        host.setText(base.getHost());
        port.setText(base.getPort() > 0 ? String.valueOf(base.getPort()) : "");
        database.setText(base.getDatabase());
        username.setText(base.getUsername());
        password.setText(base.getPassword());
        readOnly.setSelected(base.isReadOnly());
        warningLevel.getSelectionModel().select(base.getWarningLevel());
        warningLevel.disableProperty().bind(readOnly.selectedProperty());
        warningLevel.setTooltip(new Tooltip("Asks before running these statements on this connection"));
        savePassword.setSelected(base.isSavePassword());
        savePassword.setTooltip(new Tooltip("Off: the password is not written to disk; it is asked when connecting"));

        type.valueProperty().addListener((obs, oldType, newType) -> {
            if (oldType != null && String.valueOf(oldType.getDefaultPort()).equals(port.getText())) {
                port.setText(String.valueOf(newType.getDefaultPort()));
            }
            updateFields();
        });
        browse.setOnAction(e -> chooseFile());

        Button test = new Button("Test Connection");
        test.setOnAction(e -> testConnection());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(8, 0, 8, 0));
        ColumnConstraints labels = new ColumnConstraints();
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setMinWidth(320);
        grid.getColumnConstraints().addAll(labels, fields);

        HBox dbRow = new HBox(6, database, browse);
        HBox.setHgrow(database, Priority.ALWAYS);

        int r = 0;
        grid.addRow(r++, new Label("Name:"), name);
        grid.addRow(r++, new Label("Type:"), type);
        grid.addRow(r++, new Label("Host:"), host);
        grid.addRow(r++, new Label("Port:"), port);
        grid.addRow(r++, databaseLabel, dbRow);
        grid.addRow(r++, new Label("User:"), username);
        HBox passwordRow = new HBox(10, password, savePassword);
        passwordRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(password, Priority.ALWAYS);
        grid.addRow(r++, new Label("Password:"), passwordRow);
        grid.add(readOnly, 1, r++);
        Label warnLabel = new Label("Warn before:");
        warnLabel.disableProperty().bind(readOnly.selectedProperty());
        HBox warnRow = new HBox(8, warnLabel, warningLevel);
        warnRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(warnRow, 1, r++);
        grid.add(test, 1, r++);
        grid.add(testResult, 1, r);

        String id = base.getId();
        Button save = new Button("Save");
        save.getStyleClass().add("default-look");
        save.setOnAction(e -> {
            String error = validate();
            if (error != null) {
                showResult(error, false);
                return;
            }
            ConnectionConfig c = toConfig();
            c.setId(id);
            close();
            onSave.accept(c);
        });
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> close());
        HBox buttons = new HBox(8, save, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox form = new VBox(8, title, grid, buttons);
        form.setPadding(new Insets(20));
        form.setMaxWidth(620);
        // Enter saves and Esc cancels, but only while typing in this form (not window-wide default buttons)
        form.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) save.fire();
            else if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) cancel.fire();
        });
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("form-scroll");
        setContent(scroll);

        updateFields();
        setOnSelectionChanged(e -> {
            if (isSelected()) Platform.runLater(name::requestFocus);
        });
    }

    /** The id of the connection being edited, or null for a new connection. */
    String editedId() {
        return editedId;
    }

    private void close() {
        if (getTabPane() != null) getTabPane().getTabs().remove(this);
    }

    private void updateFields() {
        boolean file = type.getValue().isFileBased();
        for (Node n : new Node[]{host, port, username, password, savePassword}) {
            n.setDisable(file);
        }
        browse.setVisible(file);
        browse.setManaged(file);
        databaseLabel.setText(file ? "File:" : "Database:");
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open SQLite database");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SQLite", "*.db", "*.sqlite", "*.sqlite3", "*.db3"),
                new FileChooser.ExtensionFilter("All files", "*"));
        File f = chooser.showOpenDialog(getTabPane().getScene().getWindow());
        if (f != null) {
            database.setText(f.getAbsolutePath());
            if (name.getText() == null || name.getText().isBlank()) name.setText(f.getName());
        }
    }

    private String validate() {
        if (blank(database.getText())) {
            return type.getValue().isFileBased() ? "Choose a database file." : "Enter a database name.";
        }
        if (!type.getValue().isFileBased()) {
            if (blank(host.getText())) return "Enter a host.";
            try {
                int p = Integer.parseInt(port.getText().trim());
                if (p < 1 || p > 65535) return "Port must be between 1 and 65535.";
            } catch (NumberFormatException ex) {
                return "Port must be a number.";
            }
        }
        return null;
    }

    private ConnectionConfig toConfig() {
        ConnectionConfig c = new ConnectionConfig();
        DbType t = type.getValue();
        c.setType(t);
        c.setDatabase(database.getText().trim());
        c.setReadOnly(readOnly.isSelected());
        c.setWarningLevel(warningLevel.getSelectionModel().getSelectedIndex());
        if (!t.isFileBased()) {
            c.setHost(host.getText().trim());
            c.setPort(Integer.parseInt(port.getText().trim()));
            c.setUsername(username.getText());
            c.setSavePassword(savePassword.isSelected());
            // not saved and left empty: asked when connecting
            c.setPassword(!c.isSavePassword() && password.getText().isEmpty() ? null : password.getText());
        } else {
            c.setHost(null);
            c.setPort(0);
        }
        String n = blank(name.getText())
                ? (t.isFileBased() ? new File(c.getDatabase()).getName() : c.getDatabase() + "@" + c.getHost())
                : name.getText().trim();
        c.setName(n);
        return c;
    }

    private void testConnection() {
        String error = validate();
        if (error != null) {
            showResult(error, false);
            return;
        }
        ConnectionConfig c = toConfig();
        showResult("Connecting…", true);
        Async.run(() -> DatabaseSession.test(c),
                version -> showResult("Connected: " + version, true),
                ex -> showResult("Failed: " + ex.getMessage(), false));
    }

    private void showResult(String text, boolean ok) {
        testResult.setText(text);
        testResult.setStyle(ok ? "-fx-text-fill: #2e7d32;" : "-fx-text-fill: #c62828;");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
