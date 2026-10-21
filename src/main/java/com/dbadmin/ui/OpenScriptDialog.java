package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * In-app "Open Script" dialog: connections on the left, the scripts saved for the selected
 * connection on the right. Scripts in the Script folder that belong to no existing connection
 * are listed under "Not linked". The result is the chosen script files.
 */
class OpenScriptDialog extends Dialog<List<Path>> {

    private static final DateTimeFormatter MODIFIED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /** A left-hand entry: a connection, or null for "Not linked". */
    private record Source(ConnectionConfig connection, List<Script> scripts) {
    }

    private record Script(Path path, String folder, FileTime modified) {
    }

    private final TableView<Script> table = new TableView<>();
    private final TextField search = new TextField();
    private FilteredList<Script> shown;

    OpenScriptDialog(Window owner, List<ConnectionConfig> connections, Map<Path, String> bindings,
                     Path scriptDir, ConnectionConfig preferred) {
        initOwner(owner);
        setTitle("Open Script");
        setHeaderText(null);
        setResizable(true);

        Map<String, Source> sources = collect(connections, bindings, scriptDir);
        ListView<Source> left = new ListView<>(FXCollections.observableArrayList(sources.values()));
        left.setCellFactory(lv -> new SourceCell());
        left.setPrefWidth(230);
        left.getSelectionModel().selectedItemProperty().addListener((o, a, src) -> showScripts(src));

        TableColumn<Script, String> name = new TableColumn<>("Script");
        name.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().path().getFileName().toString()));
        name.setPrefWidth(240);
        TableColumn<Script, String> folder = new TableColumn<>("Folder");
        folder.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().folder()));
        folder.setPrefWidth(110);
        TableColumn<Script, String> modified = new TableColumn<>("Modified");
        modified.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().modified() == null ? "" : MODIFIED.format(c.getValue().modified().toInstant())));
        modified.setPrefWidth(120);
        table.getColumns().addAll(List.of(name, folder, modified));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label("No scripts saved for this connection"));
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                setResult(selectedPaths());
                close();
            }
        });

        search.setPromptText("Filter scripts…");
        search.textProperty().addListener((o, a, text) -> applyFilter());
        VBox right = new VBox(6, search, table);
        VBox.setVgrow(table, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.3);
        split.setPrefSize(820, 420);
        VBox content = new VBox(split);
        content.setPadding(new Insets(10));
        VBox.setVgrow(split, Priority.ALWAYS);
        getDialogPane().setContent(content);

        ButtonType open = new ButtonType("Open", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(open, ButtonType.CANCEL);
        Node openButton = getDialogPane().lookupButton(open);
        openButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        setResultConverter(b -> b == open ? selectedPaths() : null);

        // start on the connection selected in the main window, else the first one with scripts
        Source start = sources.values().stream()
                .filter(s -> preferred != null && s.connection() != null && s.connection().getId().equals(preferred.getId()))
                .findFirst()
                .orElse(sources.values().stream().filter(s -> !s.scripts().isEmpty()).findFirst()
                        .orElse(sources.values().stream().findFirst().orElse(null)));
        left.getSelectionModel().select(start);
        setOnShown(e -> search.requestFocus());
    }

    private List<Path> selectedPaths() {
        return table.getSelectionModel().getSelectedItems().stream().map(Script::path).toList();
    }

    private void showScripts(Source source) {
        shown = new FilteredList<>(FXCollections.observableArrayList(source == null ? List.of() : source.scripts()));
        table.setItems(shown);
        table.setPlaceholder(new Label(source != null && source.connection() == null
                ? "No unlinked scripts in the Script folder"
                : "No scripts saved for this connection"));
        applyFilter();
        table.getSelectionModel().selectFirst();
    }

    private void applyFilter() {
        if (shown == null) return;
        String f = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        shown.setPredicate(s -> f.isEmpty() || s.path().getFileName().toString().toLowerCase(Locale.ROOT).contains(f));
        if (table.getSelectionModel().getSelectedItem() == null) table.getSelectionModel().selectFirst();
    }

    /** Groups existing script files by connection; files of deleted connections and unbound files go to "Not linked". */
    private static Map<String, Source> collect(List<ConnectionConfig> connections, Map<Path, String> bindings, Path scriptDir) {
        Map<String, Source> sources = new LinkedHashMap<>();
        for (ConnectionConfig c : connections) sources.put(c.getId(), new Source(c, new ArrayList<>()));
        Source unlinked = new Source(null, new ArrayList<>());

        Set<Path> seen = new HashSet<>();
        bindings.forEach((path, id) -> {
            if (!Files.isRegularFile(path)) return; // moved or deleted outside the app
            seen.add(path.toAbsolutePath().normalize());
            Source s = sources.get(id);
            (s != null ? s : unlinked).scripts().add(script(path, scriptDir));
        });
        if (scriptDir != null && Files.isDirectory(scriptDir)) {
            try (Stream<Path> files = Files.walk(scriptDir, 4)) {
                files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                        .filter(Files::isRegularFile)
                        .filter(p -> !seen.contains(p.toAbsolutePath().normalize()))
                        .forEach(p -> unlinked.scripts().add(script(p, scriptDir)));
            } catch (IOException ignored) {
                // unreadable folder: only linked scripts are listed
            }
        }
        Comparator<Script> newestFirst = Comparator.comparing(Script::modified,
                Comparator.nullsLast(Comparator.reverseOrder()));
        sources.values().forEach(s -> s.scripts().sort(newestFirst));
        unlinked.scripts().sort(newestFirst);
        sources.put("", unlinked);
        return sources;
    }

    private static Script script(Path path, Path scriptDir) {
        Path abs = path.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        String folder;
        if (scriptDir != null && parent != null && parent.startsWith(scriptDir.toAbsolutePath().normalize())) {
            Path rel = scriptDir.toAbsolutePath().normalize().relativize(parent);
            folder = rel.toString().isEmpty() ? "Script" : "Script/" + rel;
        } else {
            folder = parent == null ? "" : parent.toString();
        }
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(abs);
        } catch (IOException e) {
            modified = null;
        }
        return new Script(abs, folder, modified);
    }

    private static class SourceCell extends ListCell<Source> {
        private final StackPane icon = new StackPane();
        private final Label name = new Label();
        private final Label count = new Label();
        private final HBox box;

        SourceCell() {
            icon.setMinWidth(18);
            count.getStyleClass().add("hint-small");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(6, icon, name, spacer, count);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Source s, boolean empty) {
            super.updateItem(s, empty);
            setText(null);
            if (empty || s == null) {
                setGraphic(null);
                return;
            }
            boolean unlinked = s.connection() == null;
            icon.getChildren().setAll(unlinked ? new Label("?") : DbIcons.connection(s.connection().getType(), false));
            name.setText(unlinked ? "Not linked" : s.connection().getName());
            name.setStyle(unlinked ? "-fx-font-style: italic;" : "");
            count.setText(String.valueOf(s.scripts().size()));
            setGraphic(box);
        }
    }
}
