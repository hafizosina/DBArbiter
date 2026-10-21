package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * In-app "Save Script" dialog, laid out like Open Script: the connection the script is for on the
 * left; a folder navigation bar, the folder's contents and the file name on the right.
 */
class SaveScriptDialog extends Dialog<SaveScriptDialog.Target> {

    /** Where to save, and the connection the script belongs to from now on. */
    record Target(Path file, ConnectionConfig connection) {
    }

    private final Path homeDir;
    private final ListView<ConnectionConfig> connectionList = new ListView<>();
    /** Suggests "Script-&lt;connection&gt;-&lt;n&gt;.sql" for a connection and folder. */
    private final BiFunction<ConnectionConfig, Path, String> suggestName;
    /** Once the user types or picks a name, switching connection or folder no longer changes it. */
    private boolean nameEdited;
    private boolean settingName;
    private final TextField location = new TextField();
    private final ListView<Path> entries = new ListView<>();
    private final TextField fileName = new TextField();
    private final Label message = new Label();
    private final Button up = new Button("↑");
    private Path dir;

    /**
     * @param existingName the script's current file name (Save As), or null to suggest one
     * @param scriptCounts number of saved scripts per connection id, shown next to each connection
     */
    SaveScriptDialog(Window owner, Path homeDir, Path startDir, List<ConnectionConfig> connections,
                     Map<String, Integer> scriptCounts, ConnectionConfig initial, String existingName,
                     BiFunction<ConnectionConfig, Path, String> suggestName) {
        this.homeDir = homeDir;
        this.suggestName = suggestName;
        initOwner(owner);
        setTitle("Save Script");
        setHeaderText(null);
        setResizable(true);

        up.setTooltip(new Tooltip("Parent folder"));
        up.setOnAction(e -> {
            if (dir.getParent() != null) navigate(dir.getParent());
        });
        Button home = new Button("Scripts");
        home.setTooltip(new Tooltip(homeDir.toString()));
        home.setOnAction(e -> navigate(homeDir));
        Button newFolder = new Button("New Folder");
        newFolder.setOnAction(e -> createFolder());
        location.setOnAction(e -> navigateTyped());
        HBox.setHgrow(location, Priority.ALWAYS);
        HBox navBar = new HBox(6, up, home, location, newFolder);
        navBar.setAlignment(Pos.CENTER_LEFT);

        entries.setCellFactory(lv -> new EntryCell());
        entries.setPrefSize(560, 300);
        entries.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> {
            if (p != null && !Files.isDirectory(p)) fileName.setText(p.getFileName().toString()); // counts as edited
        });
        entries.setOnMouseClicked(e -> {
            Path p = entries.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && p != null && Files.isDirectory(p)) navigate(p);
        });
        entries.setOnKeyPressed(e -> {
            Path p = entries.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && p != null && Files.isDirectory(p)) navigate(p);
            if (e.getCode() == KeyCode.BACK_SPACE && dir.getParent() != null) navigate(dir.getParent());
        });

        HBox.setHgrow(fileName, Priority.ALWAYS);
        HBox nameRow = new HBox(8, new Label("File name:"), fileName);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        message.getStyleClass().add("error-text-inline");

        fileName.textProperty().addListener((o, a, b) -> {
            if (!settingName) nameEdited = true;
        });

        connectionList.getItems().setAll(connections);
        connectionList.setCellFactory(lv -> new ConnectionCell(scriptCounts));
        connectionList.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> suggest());
        Label forLabel = new Label("Save for connection:");
        forLabel.getStyleClass().add("hint-small");
        VBox left = new VBox(6, forLabel, connectionList);
        VBox.setVgrow(connectionList, Priority.ALWAYS);

        VBox right = new VBox(8, navBar, entries, nameRow, message);
        VBox.setVgrow(entries, Priority.ALWAYS);
        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.3);
        split.setPrefSize(860, 440);
        VBox content = new VBox(split);
        content.setPadding(new Insets(10));
        VBox.setVgrow(split, Priority.ALWAYS);
        getDialogPane().setContent(content);

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        getDialogPane().lookupButton(save).disableProperty()
                .bind(connectionList.getSelectionModel().selectedItemProperty().isNull());
        getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (targetFile() == null) e.consume();
        });
        setResultConverter(b -> {
            if (b != save) return null;
            Path file = targetFile();
            return file == null ? null : new Target(file, connectionList.getSelectionModel().getSelectedItem());
        });

        if (existingName != null) {
            setName(existingName);
            nameEdited = true;
        }
        navigate(Files.isDirectory(startDir) ? startDir : homeDir);
        if (initial != null) {
            connectionList.getItems().stream().filter(c -> c.getId().equals(initial.getId())).findFirst()
                    .ifPresent(c -> connectionList.getSelectionModel().select(c));
        }
        if (connectionList.getSelectionModel().isEmpty()) message.setText("Choose the connection this script is for.");
        setOnShown(e -> {
            fileName.requestFocus();
            // select the base name so typing replaces it but keeps ".sql"
            String name = fileName.getText();
            int dot = name.lastIndexOf('.');
            fileName.selectRange(0, dot > 0 ? dot : name.length());
        });
    }

    /** Re-suggests the file name for the chosen connection and folder, unless the user chose one. */
    private void suggest() {
        ConnectionConfig c = connectionList.getSelectionModel().getSelectedItem();
        if (c != null && message.getText().startsWith("Choose the connection")) message.setText("");
        if (nameEdited || c == null || dir == null) return;
        setName(suggestName.apply(c, dir));
    }

    private void setName(String name) {
        settingName = true;
        fileName.setText(name);
        settingName = false;
    }

    private void navigate(Path target) {
        dir = target.toAbsolutePath().normalize();
        suggest();
        location.setText(dir.toString());
        up.setDisable(dir.getParent() == null);
        message.setText("");
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> items = list
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> Files.isDirectory(p) || p.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
            entries.setItems(FXCollections.observableArrayList(items));
        } catch (IOException e) {
            entries.getItems().clear();
            message.setText("Cannot read folder: " + e.getMessage());
        }
    }

    private void navigateTyped() {
        try {
            Path p = Path.of(location.getText().trim());
            if (Files.isDirectory(p)) navigate(p);
            else message.setText("Not a folder: " + p);
        } catch (InvalidPathException e) {
            message.setText("Invalid path");
        }
    }

    private void createFolder() {
        TextInputDialog ask = new TextInputDialog();
        ask.initOwner(getDialogPane().getScene().getWindow());
        ask.setTitle("New Folder");
        ask.setHeaderText("Create a folder in " + dir);
        ask.setContentText("Name:");
        InAppDialogs.showAndWait(ask).map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            try {
                Path created = Files.createDirectories(dir.resolve(name));
                navigate(created);
            } catch (IOException | InvalidPathException e) {
                message.setText("Cannot create folder: " + e.getMessage());
            }
        });
    }

    /** The file to save to, or null (with a message shown) if the input is not acceptable. */
    private Path targetFile() {
        String name = fileName.getText().trim();
        if (name.isEmpty()) {
            message.setText("Enter a file name.");
            return null;
        }
        if (name.contains("/") || name.contains("\\")) {
            message.setText("The file name cannot contain / or \\.");
            return null;
        }
        if (!name.toLowerCase().endsWith(".sql")) name += ".sql";
        Path file = dir.resolve(name);
        if (Files.isDirectory(file)) {
            message.setText(name + " is a folder.");
            return null;
        }
        if (Files.exists(file)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    name + " already exists. Replace it?", ButtonType.YES, ButtonType.NO);
            confirm.initOwner(getDialogPane().getScene().getWindow());
            confirm.setHeaderText(null);
            if (InAppDialogs.showAndWait(confirm).orElse(ButtonType.NO) != ButtonType.YES) return null;
        }
        return file;
    }

    private static class ConnectionCell extends ListCell<ConnectionConfig> {
        private final StackPane icon = new StackPane();
        private final Label name = new Label();
        private final Label count = new Label();
        private final HBox box;
        private final Map<String, Integer> counts;

        ConnectionCell(Map<String, Integer> counts) {
            this.counts = counts;
            icon.setMinWidth(18);
            count.getStyleClass().add("hint-small");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(6, icon, name, spacer, count);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(ConnectionConfig c, boolean empty) {
            super.updateItem(c, empty);
            setText(null);
            if (empty || c == null) {
                setGraphic(null);
                return;
            }
            icon.getChildren().setAll(DbIcons.connection(c.getType(), false));
            name.setText(c.getName());
            count.setText(String.valueOf(counts.getOrDefault(c.getId(), 0)));
            setGraphic(box);
        }
    }

    private static class EntryCell extends ListCell<Path> {
        private static final String FOLDER = "M1 3h5l1.5 1.5H15V13H1z";
        private static final String FILE = "M3 1h6.5L13 4.5V15H3z";

        @Override
        protected void updateItem(Path p, boolean empty) {
            super.updateItem(p, empty);
            if (empty || p == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            boolean folder = Files.isDirectory(p);
            SVGPath icon = new SVGPath();
            icon.setContent(folder ? FOLDER : FILE);
            icon.setFill(folder ? Color.web("#F2B84B") : Color.web("#90A4AE"));
            setGraphic(icon);
            setText(p.getFileName().toString());
        }
    }
}
