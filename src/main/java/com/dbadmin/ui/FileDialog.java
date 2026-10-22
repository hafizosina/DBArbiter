package com.dbadmin.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * In-app Save / Open dialog for one kind of file (e.g. .dbcompare), in the style of Save Script: a folder
 * bar (up, the default folder, a typed path, New Folder), the folder's subfolders and files of that kind,
 * and the file name. The result is the chosen file.
 */
class FileDialog extends Dialog<Path> {

    enum Mode { SAVE, OPEN }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm");

    private final Mode mode;
    private final String extension;
    private final TextField location = new TextField();
    private final ListView<Path> entries = new ListView<>();
    private final TextField fileName = new TextField();
    private final Label message = new Label();
    private final Button up = new Button("↑");
    private final VBox content;
    /** The file accepted by the OK button: checked (and Replace asked) once, then returned. */
    private Path chosen;
    private Path dir;

    /**
     * @param extension     e.g. ".dbcompare": the files listed, and added to a typed name when saving
     * @param suggestedName the file name to start with when saving (null when opening)
     */
    FileDialog(Window owner, Mode mode, String title, Path homeDir, String homeLabel, Path startDir, String extension,
               String kindLabel, String suggestedName) {
        this.mode = mode;
        this.extension = extension.toLowerCase(Locale.ROOT);
        initOwner(owner);
        setTitle(title);
        setHeaderText(null);
        setResizable(true);

        up.setTooltip(new Tooltip("Parent folder"));
        up.setOnAction(e -> {
            if (dir.getParent() != null) navigate(dir.getParent());
        });
        Button home = new Button(homeLabel);
        home.setTooltip(new Tooltip(homeDir.toString()));
        home.setOnAction(e -> navigate(homeDir));
        location.setOnAction(e -> navigateTyped());
        HBox.setHgrow(location, Priority.ALWAYS);
        HBox navBar = new HBox(6, up, home, location);
        if (mode == Mode.SAVE) {
            Button newFolder = new Button("New Folder");
            newFolder.setOnAction(e -> createFolder());
            navBar.getChildren().add(newFolder);
        }
        navBar.setAlignment(Pos.CENTER_LEFT);

        entries.setCellFactory(lv -> new EntryCell());
        entries.setPrefSize(720, 340);
        entries.setPlaceholder(new Label("No " + kindLabel + " in this folder."));
        entries.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> {
            if (p != null && !Files.isDirectory(p)) fileName.setText(p.getFileName().toString());
        });
        entries.setOnMouseClicked(e -> {
            Path p = entries.getSelectionModel().getSelectedItem();
            if (e.getClickCount() != 2 || p == null) return;
            if (Files.isDirectory(p)) navigate(p);
            else if (mode == Mode.OPEN) finish();
        });
        entries.setOnKeyPressed(e -> {
            Path p = entries.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && p != null) {
                if (Files.isDirectory(p)) navigate(p);
                else if (mode == Mode.OPEN) finish();
            }
            if (e.getCode() == KeyCode.BACK_SPACE && dir.getParent() != null) navigate(dir.getParent());
        });

        fileName.setEditable(mode == Mode.SAVE);
        HBox.setHgrow(fileName, Priority.ALWAYS);
        HBox nameRow = new HBox(8, new Label("File name:"), fileName);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        message.getStyleClass().add("error-text-inline");

        content = new VBox(8, navBar, entries, nameRow, message);
        content.setPadding(new Insets(10));
        VBox.setVgrow(entries, Priority.ALWAYS);
        getDialogPane().setContent(content);

        ButtonType ok = new ButtonType(mode == Mode.SAVE ? "Save" : "Open", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        Node okButton = getDialogPane().lookupButton(ok);
        if (mode == Mode.OPEN) okButton.disableProperty().bind(fileName.textProperty().isEmpty());
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            chosen = chosenFile();
            if (chosen == null) e.consume();
        });
        setResultConverter(b -> b == ok ? chosen : null);

        if (suggestedName != null) fileName.setText(suggestedName);
        navigate(startDir != null && Files.isDirectory(startDir) ? startDir : homeDir);
        setOnShown(e -> {
            if (mode == Mode.OPEN) {
                entries.requestFocus();
                return;
            }
            fileName.requestFocus();
            // the base name selected: typing replaces it and keeps the extension
            String name = fileName.getText();
            int dot = name.toLowerCase(Locale.ROOT).lastIndexOf(this.extension);
            fileName.selectRange(0, dot > 0 ? dot : name.length());
        });
    }

    /** Adds a row of options under the file name (e.g. what to save). */
    void addOptions(Node options) {
        content.getChildren().add(content.getChildren().size() - 1, options);
    }

    /** Opening: the selected file (double-click, Enter) closes the dialog with it. */
    private void finish() {
        Path file = chosenFile();
        if (file == null) return;
        setResult(file);
        close();
    }

    private void navigate(Path target) {
        dir = target.toAbsolutePath().normalize();
        location.setText(dir.toString());
        up.setDisable(dir.getParent() == null);
        message.setText("");
        try (Stream<Path> list = Files.list(dir)) {
            entries.setItems(FXCollections.observableArrayList(list
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> Files.isDirectory(p) || p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList()));
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
        ask.initOwner(getOwner());
        ask.setTitle("New Folder");
        ask.setHeaderText("Create a folder in " + dir);
        ask.setContentText("Name:");
        InAppDialogs.showAndWait(ask).map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            try {
                navigate(Files.createDirectories(dir.resolve(name)));
            } catch (IOException | InvalidPathException e) {
                message.setText("Cannot create folder: " + e.getMessage());
            }
        });
    }

    /** The file to save to or open, or null (with a message shown) if the input is not acceptable. */
    private Path chosenFile() {
        String name = fileName.getText().trim();
        if (name.isEmpty()) {
            message.setText(mode == Mode.SAVE ? "Enter a file name." : "Pick a file.");
            return null;
        }
        if (name.contains("/") || name.contains("\\")) {
            message.setText("The file name cannot contain / or \\.");
            return null;
        }
        if (mode == Mode.SAVE && !name.toLowerCase(Locale.ROOT).endsWith(extension)) name += extension;
        Path file = dir.resolve(name);
        if (Files.isDirectory(file)) {
            message.setText(name + " is a folder.");
            return null;
        }
        if (mode == Mode.OPEN && !Files.isRegularFile(file)) {
            message.setText(name + " does not exist.");
            return null;
        }
        if (mode == Mode.SAVE && Files.exists(file)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, name + " already exists. Replace it?", ButtonType.YES, ButtonType.NO);
            confirm.initOwner(getOwner());
            confirm.setHeaderText(null);
            if (InAppDialogs.showAndWait(confirm).orElse(ButtonType.NO) != ButtonType.YES) return null;
        }
        return file;
    }

    /** A folder or file: icon, name, and the file's last change. */
    private static class EntryCell extends ListCell<Path> {
        private static final String FOLDER = "M1 3h5l1.5 1.5H15V13H1z";
        private static final String FILE = "M3 1h6.5L13 4.5V15H3z";
        private final Label name = new Label();
        private final Label modified = new Label();
        private final SVGPath icon = new SVGPath();
        private final HBox box;

        EntryCell() {
            modified.getStyleClass().add("hint-small");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(6, icon, name, spacer, modified);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Path p, boolean empty) {
            super.updateItem(p, empty);
            setText(null);
            if (empty || p == null) {
                setGraphic(null);
                return;
            }
            boolean folder = Files.isDirectory(p);
            icon.setContent(folder ? FOLDER : FILE);
            icon.setFill(folder ? Color.web("#F2B84B") : Color.web("#90A4AE"));
            name.setText(p.getFileName().toString());
            String when = "";
            if (!folder) {
                try {
                    when = WHEN.format(LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault()));
                } catch (IOException ignored) {
                    // shown without a date
                }
            }
            modified.setText(when);
            setGraphic(box);
        }
    }
}
