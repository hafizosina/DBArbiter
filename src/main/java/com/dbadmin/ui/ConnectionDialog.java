package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.service.DatabaseSession;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;

/** Create or edit a connection. Returns a new {@link ConnectionConfig}; the input is never modified. */
class ConnectionDialog extends Dialog<ConnectionConfig> {

    private final ComboBox<DbType> type = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final TextField database = new TextField();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final CheckBox readOnly = new CheckBox("Read-only (block all changes to the database)");
    private final Label databaseLabel = new Label("Database:");
    private final Button browse = new Button("Browse…");
    private final Label testResult = new Label();

    ConnectionDialog(Window owner, ConnectionConfig existing) {
        initOwner(owner);
        setTitle(existing == null ? "New Connection" : "Edit Connection");
        setHeaderText(existing == null ? "Connect to a database" : "Edit \"" + existing.getName() + "\"");

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
        grid.setPadding(new Insets(16));
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
        grid.addRow(r++, new Label("Password:"), password);
        grid.add(readOnly, 1, r++);
        grid.add(test, 1, r++);
        grid.add(testResult, 1, r);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Node ok = getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            String error = validate();
            if (error != null) {
                showResult(error, false);
                e.consume();
            }
        });

        String id = base.getId();
        setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            ConnectionConfig c = toConfig();
            c.setId(id);
            return c;
        });

        updateFields();
        setOnShown(e -> name.requestFocus());
    }

    private void updateFields() {
        boolean file = type.getValue().isFileBased();
        for (Node n : new Node[]{host, port, username, password}) {
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
        File f = chooser.showOpenDialog(getOwner());
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
        if (!t.isFileBased()) {
            c.setHost(host.getText().trim());
            c.setPort(Integer.parseInt(port.getText().trim()));
            c.setUsername(username.getText());
            c.setPassword(password.getText());
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
        getDialogPane().getScene().getWindow().sizeToScene();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
