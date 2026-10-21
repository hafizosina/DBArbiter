package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.StatementResult;
import com.dbadmin.service.CompletionContext;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.NamedParams;
import com.dbadmin.service.NamedParams.ParsedSql;
import com.dbadmin.service.ScriptBindings;
import com.dbadmin.service.ScriptFolder;
import com.dbadmin.service.SqlSplitter;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
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
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
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
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** A SQL editor bound to a connection, with results and messages below. */
class ScriptTab extends Tab {

    private static final int MAX_ROWS = 1000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Statements that change the schema, after which cached autocomplete metadata is stale. */
    private static final Pattern DDL = Pattern.compile("\\b(create|alter|drop|rename|truncate)\\b", Pattern.CASE_INSENSITIVE);

    private final SessionProvider sessions;
    private final SqlCompleter completer;
    private final ScriptBindings bindings;
    /** Id of the connection this script was saved for; null for a script never saved from here. */
    private String boundId;
    private final Label bindingWarning = new Label();
    private final CompletionPopup popup = new CompletionPopup();
    private int completionRequest;
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
              ObservableList<ConnectionConfig> connections, ConnectionConfig initial,
              SessionProvider sessions, SqlCompleter completer, ScriptBindings bindings) {
        this.untitledName = untitledName;
        this.file = file;
        this.sessions = sessions;
        this.completer = completer;
        this.bindings = bindings;
        this.boundId = file == null ? null : bindings.connectionFor(file);

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
            updateBindingWarning(); // a rename changes the text
        });

        runButton.setTooltip(new Tooltip("Run selection, or the whole script (Ctrl+Enter)"));
        runButton.setOnAction(e -> run());
        runButton.disableProperty().bind(running.getReadOnlyProperty());
        stopButton.setOnAction(e -> stop());
        stopButton.disableProperty().bind(running.getReadOnlyProperty().not());
        Label mode = new Label();
        connection.valueProperty().addListener((o, a, c) -> {
            showMode(mode, c);
            updateBindingWarning();
            rehighlight();
            popup.hide();
        });
        showMode(mode, initial);
        ToolBar bar = new ToolBar(runButton, stopButton, new Separator(Orientation.VERTICAL),
                new Label("Connection:"), connection, mode, bindingWarning,
                new Separator(Orientation.VERTICAL), DataView.valuePanelToggle());
        bindingWarning.getStyleClass().add("binding-warning");
        updateBindingWarning();

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
        editor.addEventFilter(KeyEvent.KEY_PRESSED, this::completionKeys);
        editor.plainTextChanges().subscribe(change -> {
            String inserted = change.getInserted();
            if (inserted.length() == 1 && change.getRemoved().isEmpty() && isCompletionTrigger(inserted.charAt(0))) {
                Platform.runLater(() -> requestCompletion(false));
            } else if (inserted.isEmpty() && popup.isShowing()) {
                Platform.runLater(() -> requestCompletion(false)); // backspace: refilter
            } else {
                popup.hide();
            }
        });
        editor.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> popup.hide());
        setOnSelectionChanged(e -> popup.hide());
        // a filter, so the text area never sees Ctrl+Enter and inserts a newline
        editor.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
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

    // ---- connection binding ----------------------------------------------

    private boolean isOnOtherConnection() {
        ConnectionConfig c = connection.getValue();
        return boundId != null && (c == null || !boundId.equals(c.getId()));
    }

    private String boundName() {
        return connection.getItems().stream()
                .filter(c -> c.getId().equals(boundId))
                .map(c -> "\"" + c.getName() + "\"")
                .findFirst().orElse("a deleted connection");
    }

    private void updateBindingWarning() {
        bindingWarning.setText(isOnOtherConnection() ? "⚠ saved for " + boundName() : "");
    }

    /** Asks before running a script on a connection other than the one it was saved for. Cancel is the default. */
    private boolean confirmOtherConnection(ConnectionConfig target) {
        ButtonType runAnyway = new ButtonType("Run on \"" + target.getName() + "\"", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.WARNING,
                "This script was saved for " + boundName() + ".\n\nRun it on \"" + target.getName() + "\" ("
                        + (target.isReadOnly() ? "read-only" : "read-write") + ") instead?",
                runAnyway, ButtonType.CANCEL);
        alert.initOwner(window());
        alert.setHeaderText("Different connection");
        ((Button) alert.getDialogPane().lookupButton(runAnyway)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == runAnyway;
    }

    // ---- autocomplete ----------------------------------------------------

    private static boolean isCompletionTrigger(char c) {
        return c == '.' || c == '_' || Character.isLetterOrDigit(c);
    }

    private void completionKeys(KeyEvent e) {
        if (e.getCode() == KeyCode.SPACE && e.isShortcutDown()) {
            e.consume();
            requestCompletion(true);
            return;
        }
        if (!popup.isShowing()) return;
        switch (e.getCode()) {
            case UP -> popup.move(-1);
            case DOWN -> popup.move(1);
            case PAGE_UP -> popup.move(-8);
            case PAGE_DOWN -> popup.move(8);
            case ENTER, TAB -> {
                if (e.isShortcutDown()) return; // Ctrl+Enter still runs the script
                popup.acceptSelected();
            }
            case ESCAPE -> popup.hide();
            default -> {
                if (e.getCode().isNavigationKey()) popup.hide();
                return;
            }
        }
        e.consume();
    }

    /**
     * Shows suggestions for the word at the caret. Typing triggers it after two characters
     * or a '.', using metadata only if the connection is already open; Ctrl+Space ("explicit")
     * always shows it and connects if needed.
     */
    private void requestCompletion(boolean explicit) {
        int request = ++completionRequest;
        ConnectionConfig config = connection.getValue();
        DbType type = config == null ? null : config.getType();
        CompletionContext ctx = CompletionContext.analyze(editor.getText(), editor.getCaretPosition(), type);
        if (ctx == null || !explicit && ctx.qualifier() == null && ctx.prefix().length() < 2) {
            popup.hide();
            return;
        }
        Consumer<DatabaseSession> complete = session -> completer.complete(ctx, session, items -> {
            if (request == completionRequest) showCompletions(ctx, type, items, explicit);
        });
        if (config == null) {
            complete.accept(null);
        } else if (explicit) {
            sessions.withSession(config, complete, ex -> complete.accept(null));
        } else {
            complete.accept(sessions.openSession(config).orElse(null));
        }
    }

    private void showCompletions(CompletionContext ctx, DbType type, List<SqlCompleter.Suggestion> items, boolean explicit) {
        boolean alreadyComplete = items.size() == 1 && items.get(0).text().equalsIgnoreCase(ctx.prefix());
        Bounds caret = editor.getCaretBounds().orElse(null);
        if (items.isEmpty() || !explicit && alreadyComplete || caret == null || !editor.isFocused()) {
            popup.hide();
            return;
        }
        popup.show(editor, caret.getMinX(), caret.getMaxY() + 2, items, s -> accept(s, type));
    }

    private void accept(SqlCompleter.Suggestion s, DbType type) {
        // re-read the word at the caret: the text may have changed since the list was built
        int caret = editor.getCaretPosition();
        CompletionContext now = CompletionContext.analyze(editor.getText(), caret, type);
        if (now == null) return;
        editor.replaceText(now.start(), caret, SqlCompleter.insertText(s, now.prefix(), type));
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

    /** The file this script was opened from or saved to; null if never saved. */
    Path file() {
        return file;
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
        if (boundId != null && !boundId.equals(config.getId()) && !confirmOtherConnection(config)) return;
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
        DataView view = new DataView();
        view.grid().show(r.data());
        Tab tab = new Tab("Result " + number, view);
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
        if (DDL.matcher(editor.getText()).find()) completer.invalidate(runningSession);
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
            ConnectionConfig c = connection.getValue();
            if (c != null) {
                try {
                    bindings.bind(path, c.getId());
                    boundId = c.getId();
                } catch (UncheckedIOException e) {
                    log("Saved, but could not record the script's connection: " + e.getMessage());
                }
                updateBindingWarning();
            }
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
