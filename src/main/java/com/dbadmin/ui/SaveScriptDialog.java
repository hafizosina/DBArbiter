package com.dbadmin.ui;

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
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
import java.util.stream.Stream;

/** In-app "Save Script" dialog: a folder navigation bar, the folder's contents and a file name. */
class SaveScriptDialog extends Dialog<Path> {

    private final Path homeDir;
    private final TextField location = new TextField();
    private final ListView<Path> entries = new ListView<>();
    private final TextField fileName = new TextField();
    private final Label message = new Label();
    private final Button up = new Button("↑");
    private Path dir;

    SaveScriptDialog(Window owner, Path homeDir, Path startDir, String defaultName) {
        this.homeDir = homeDir;
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
            if (p != null && !Files.isDirectory(p)) fileName.setText(p.getFileName().toString());
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

        VBox content = new VBox(8, navBar, entries, nameRow, message);
        content.setPadding(new Insets(12));
        VBox.setVgrow(entries, Priority.ALWAYS);
        getDialogPane().setContent(content);

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (target() == null) e.consume();
        });
        setResultConverter(b -> b == save ? target() : null);

        navigate(Files.isDirectory(startDir) ? startDir : homeDir);
        fileName.setText(defaultName);
        setOnShown(e -> {
            fileName.requestFocus();
            // select the base name so typing replaces it but keeps ".sql"
            int dot = defaultName.lastIndexOf('.');
            fileName.selectRange(0, dot > 0 ? dot : defaultName.length());
        });
    }

    private void navigate(Path target) {
        dir = target.toAbsolutePath().normalize();
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
        ask.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            try {
                Path created = Files.createDirectories(dir.resolve(name));
                navigate(created);
            } catch (IOException | InvalidPathException e) {
                message.setText("Cannot create folder: " + e.getMessage());
            }
        });
    }

    /** The file to save to, or null (with a message shown) if the input is not acceptable. */
    private Path target() {
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
            if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return null;
        }
        return file;
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
