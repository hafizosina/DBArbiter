package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.StatementResult;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.NamedParams;
import com.dbadmin.service.NamedParams.ParsedSql;
import com.dbadmin.service.ScriptFolder;
import com.dbadmin.service.SqlSplitter;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A SQL editor bound to a connection, with results and messages below. */
class ScriptTab extends Tab {

    private static final int MAX_ROWS = 1000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SessionProvider sessions;
    private final ComboBox<ConnectionConfig> connection = new ComboBox<>();
    private final CodeArea editor = new CodeArea();
    private final TextArea messages = new TextArea();
    private final TabPane results = new TabPane();
    private final Tab messagesTab = new Tab("Messages", messages);
    private final Button runButton = new Button("▶ Run");
    private final Button stopButton = new Button("■ Stop");
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper();
    /** Last values entered for each parameter name (null = SQL NULL), pre-filled next time. */
    private final Map<String, String> paramValues = new HashMap<>();

    private final String untitledName;
    private Path file;
    private boolean dirty;
    private boolean cancelRequested;
    private DatabaseSession runningSession;

    ScriptTab(String untitledName, Path file, String content,
              ObservableList<ConnectionConfig> connections, ConnectionConfig initial, SessionProvider sessions) {
        this.untitledName = untitledName;
        this.file = file;
        this.sessions = sessions;

        connection.setItems(connections);
        connection.setValue(initial);
        connection.setPromptText("Select connection");
        connection.setCellFactory(lv -> new ConnectionCell());
        connection.setButtonCell(new ConnectionCell());
        // keep the selection pointing at the current object when a connection is edited or deleted
        connections.addListener((ListChangeListener<ConnectionConfig>) change -> {
            ConnectionConfig current = connection.getValue();
            if (current == null) return;
            connection.setValue(connections.stream()
                    .filter(c -> c.getId().equals(current.getId())).findFirst().orElse(null));
        });

        runButton.setTooltip(new Tooltip("Run selection, or the whole script (Ctrl+Enter)"));
        runButton.setOnAction(e -> run());
        runButton.disableProperty().bind(running.getReadOnlyProperty());
        stopButton.setOnAction(e -> stop());
        stopButton.disableProperty().bind(running.getReadOnlyProperty().not());
        Label mode = new Label();
        connection.valueProperty().addListener((o, a, c) -> {
            showMode(mode, c);
            rehighlight();
        });
        showMode(mode, initial);
        ToolBar bar = new ToolBar(runButton, stopButton, new Separator(Orientation.VERTICAL),
                new Label("Connection:"), connection, mode);

        editor.getStyleClass().add("sql-editor");
        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.replaceText(content);
        rehighlight();
        editor.getUndoManager().forgetHistory();
        editor.textProperty().addListener((o, a, b) -> setDirty(true));
        // re-highlight once typing pauses, not on every keystroke
        editor.multiPlainChanges()
                .successionEnds(Duration.ofMillis(150))
                .subscribe(ignore -> rehighlight());
        // a filter, so the text area never sees Ctrl+Enter and inserts a newline
        editor.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                e.consume();
                run();
            }
        });

        messages.setEditable(false);
        messages.getStyleClass().add("sql-messages");
        messagesTab.setClosable(false);
        results.getTabs().add(messagesTab);

        SplitPane split = new SplitPane(new VirtualizedScrollPane<>(editor), results);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);

        BorderPane pane = new BorderPane(split);
        pane.setTop(bar);
        setContent(pane);

        setOnCloseRequest(e -> {
            if (!confirmClose()) e.consume();
        });
        setOnClosed(e -> stop());
        updateTitle();
    }

    private void rehighlight() {
        ConnectionConfig c = connection.getValue();
        editor.setStyleSpans(0, SqlHighlighter.highlight(editor.getText(), c == null ? null : c.getType()));
    }

    private static void showMode(Label mode, ConnectionConfig c) {
        mode.setText(c == null ? "" : c.isReadOnly() ? "read-only" : "read-write");
        mode.getStyleClass().setAll("label", c != null && !c.isReadOnly() ? "mode-write" : "hint-small");
    }

    ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    boolean isDirty() {
        return dirty;
    }

    void focusEditor() {
        editor.requestFocus();
    }

    // ---- running ---------------------------------------------------------

    void run() {
        if (running.get()) return;
        ConnectionConfig config = connection.getValue();
        if (config == null) {
            log("Select a connection first.");
            return;
        }
        String selected = editor.getSelectedText();
        String sql = selected.isBlank() ? editor.getText() : selected;
        List<ParsedSql> statements = SqlSplitter.split(sql, config.getType()).stream()
                .map(s -> NamedParams.parse(s, config.getType()))
                .toList();
        if (statements.isEmpty()) {
            log("Nothing to run.");
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        statements.forEach(s -> names.addAll(s.names()));
        Map<String, String> values = Map.of();
        if (!names.isEmpty()) {
            values = new ParamDialog(window(), names, paramValues).showAndWait().orElse(null);
            if (values == null) return;
            paramValues.putAll(values);
        }
        Map<String, String> bound = values;

        results.getTabs().retainAll(messagesTab);
        messages.clear();
        results.getSelectionModel().select(messagesTab);
        running.set(true);
        cancelRequested = false;
        log("Running " + statements.size() + " statement(s) on " + config.getName() + "…");
        bound.forEach((name, value) -> log("  :" + name + " = " + (value == null ? "NULL" : "'" + value + "'")));
        sessions.withSession(config, session -> {
            runningSession = session;
            runNext(session, statements, bound, 0, System.nanoTime());
        }, ex -> finish("Could not connect: " + ex.getMessage()));
    }

    private void runNext(DatabaseSession session, List<ParsedSql> statements, Map<String, String> values,
                         int index, long startNanos) {
        if (cancelRequested) {
            finish("Stopped after " + index + " of " + statements.size() + " statement(s).");
            return;
        }
        if (index == statements.size()) {
            finish("Done: " + statements.size() + " statement(s) in " + (System.nanoTime() - startNanos) / 1_000_000 + " ms.");
            return;
        }
        String sql = statements.get(index).sql();
        // ArrayList, not toList(): values may be null (SQL NULL)
        List<String> params = new java.util.ArrayList<>();
        statements.get(index).names().forEach(name -> params.add(values.get(name)));
        Async.run(session, () -> session.execute(sql, params, MAX_ROWS), result -> {
            if (runningSession != session) return;
            showResult(index + 1, result);
            runNext(session, statements, values, index + 1, startNanos);
        }, ex -> {
            if (runningSession != session) return;
            log("Statement " + (index + 1) + " failed: " + ex.getMessage() + "\n    " + abbreviate(sql));
            results.getSelectionModel().select(messagesTab);
            finish(cancelRequested ? "Stopped." : "Stopped at the first error.");
        });
    }

    private void showResult(int number, StatementResult r) {
        if (r.data() == null) {
            String what = r.updateCount() >= 0 ? r.updateCount() + " row(s) affected" : "OK";
            log("Statement " + number + ": " + what + " (" + r.millis() + " ms)");
            return;
        }
        int rows = r.data().rows().size();
        log("Statement " + number + ": " + rows + " row(s) fetched" +
                (r.truncated() ? " (limited to " + MAX_ROWS + ")" : "") + " (" + r.millis() + " ms)");
        DataGrid grid = new DataGrid();
        grid.show(r.data());
        Tab tab = new Tab("Result " + number, grid);
        tab.setTooltip(new Tooltip(abbreviate(r.sql())));
        results.getTabs().add(tab);
        results.getSelectionModel().select(tab);
    }

    void stop() {
        if (!running.get()) return;
        cancelRequested = true;
        DatabaseSession session = runningSession;
        if (session != null) {
            // cancel from another thread: the session's own worker is busy running the statement
            Async.run(() -> {
                session.cancelRunning();
                return null;
            }, x -> { }, x -> { });
        }
    }

    /** Called when a session is closed (disconnect), so a script running on it does not hang. */
    void sessionClosed(DatabaseSession session) {
        if (running.get() && runningSession == session) {
            cancelRequested = true;
            finish("Connection was closed.");
        }
    }

    private void finish(String message) {
        log(message);
        running.set(false);
        runningSession = null;
    }

    private void log(String line) {
        messages.appendText("[" + LocalTime.now().format(TIME) + "] " + line + "\n");
    }

    private static String abbreviate(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ");
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine;
    }

    // ---- files -----------------------------------------------------------

    static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Saves to the current file, asking for one if the script is new. Returns false if not saved. */
    boolean save() {
        return file == null ? saveAs() : writeTo(file);
    }

    boolean saveAs() {
        Path home;
        try {
            home = ScriptFolder.defaultDir();
        } catch (UncheckedIOException e) {
            home = Path.of(System.getProperty("user.home"));
        }
        Path start = file != null && file.getParent() != null ? file.getParent() : home;
        String name = file != null ? file.getFileName().toString() : defaultFileName(start);
        Path path = new SaveScriptDialog(window(), home, start, name).showAndWait().orElse(null);
        if (path == null || !writeTo(path)) return false;
        file = path;
        updateTitle();
        return true;
    }

    /** "Script-&lt;connection&gt;-&lt;n&gt;.sql": the tab's name if it still fits the chosen connection, else the next free one. */
    private String defaultFileName(Path dir) {
        ConnectionConfig c = connection.getValue();
        String connName = c == null ? null : c.getName();
        String prefix = connName == null ? "Script-" : "Script-" + ScriptFolder.safe(connName) + "-";
        if (untitledName.startsWith(prefix) && !Files.exists(dir.resolve(untitledName + ".sql"))) {
            return untitledName + ".sql";
        }
        List<String> others = getTabPane().getTabs().stream()
                .filter(t -> t instanceof ScriptTab && t != this)
                .map(t -> ((ScriptTab) t).displayName())
                .toList();
        return ScriptFolder.nextName(dir, connName, others) + ".sql";
    }

    private boolean writeTo(Path path) {
        try {
            Files.writeString(path, editor.getText(), StandardCharsets.UTF_8);
            setDirty(false);
            return true;
        } catch (IOException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
            alert.initOwner(window());
            alert.setHeaderText("Could not save " + path);
            alert.showAndWait();
            return false;
        }
    }

    /** Asks about unsaved changes. Returns false if the user cancelled. */
    boolean confirmClose() {
        if (!dirty) return true;
        getTabPane().getSelectionModel().select(this);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "\"" + displayName() + "\" has unsaved changes.", save, discard, ButtonType.CANCEL);
        alert.initOwner(window());
        alert.setHeaderText("Save changes?");
        ButtonType answer = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (answer == save) return save();
        return answer == discard;
    }

    private void setDirty(boolean dirty) {
        if (this.dirty == dirty) return;
        this.dirty = dirty;
        updateTitle();
    }

    String displayName() {
        return file != null ? file.getFileName().toString() : untitledName;
    }

    private void updateTitle() {
        setText((dirty ? "*" : "") + displayName());
        setTooltip(new Tooltip(file != null ? file.toString() : "Unsaved script"));
    }

    private Window window() {
        return getTabPane().getScene().getWindow();
    }

    private static class ConnectionCell extends ListCell<ConnectionConfig> {
        @Override
        protected void updateItem(ConnectionConfig c, boolean empty) {
            super.updateItem(c, empty);
            setText(empty || c == null ? null : c.getName());
            setGraphic(empty || c == null ? null : DbIcons.connection(c.getType(), false));
        }
    }
}
