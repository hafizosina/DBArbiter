package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.StatementResult;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.ScriptBindings;
import com.dbadmin.service.ScriptFolder;
import javafx.beans.property.ReadOnlyBooleanProperty;
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
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** A SQL editor bound to a connection, with results and messages below. */
class ScriptTab extends Tab {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ScriptBindings bindings;
    /** Id of the connection this script was saved for; null for a script never saved from here. */
    private String boundId;
    private final Label bindingWarning = new Label();
    private final ComboBox<ConnectionConfig> connection = new ComboBox<>();
    /** The same editor and runner as a table's query box; this tab adds saving and results tabs. */
    private final SqlEditor editor;
    private final ScriptRunner runner;
    private final TextArea messages = new TextArea();
    private final TabPane results = new TabPane();
    private final Tab messagesTab = new Tab("Messages", messages);
    private final Button runButton = new Button("▶ Run");
    private final Button runAllButton = new Button("▶▶ Run All");
    private final Button stopButton = new Button("■ Stop");

    private final String untitledName;
    private Path file;
    private boolean dirty;

    ScriptTab(String untitledName, Path file, String content,
              ObservableList<ConnectionConfig> connections, ConnectionConfig initial,
              SessionProvider sessions, SqlCompleter completer, ScriptBindings bindings) {
        this.untitledName = untitledName;
        this.file = file;
        this.editor = new SqlEditor(completer, sessions, connection::getValue, this::runStatement);
        this.runner = new ScriptRunner(sessions, completer);
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

        runButton.setTooltip(new Tooltip("Run the statement at the cursor, or the selection (Ctrl+Enter)"));
        runButton.setOnAction(e -> runStatement());
        runButton.disableProperty().bind(runner.runningProperty());
        runAllButton.setTooltip(new Tooltip("Run the whole script, or the selection"));
        runAllButton.setOnAction(e -> run());
        runAllButton.disableProperty().bind(runner.runningProperty());
        stopButton.setOnAction(e -> stop());
        stopButton.disableProperty().bind(runner.runningProperty().not());
        Label mode = new Label();
        connection.valueProperty().addListener((o, a, c) -> {
            showMode(mode, c);
            updateBindingWarning();
            editor.connectionChanged();
        });
        showMode(mode, initial);
        ToolBar bar = new ToolBar(runButton, runAllButton, stopButton, new Separator(Orientation.VERTICAL),
                new Label("Connection:"), connection, mode, bindingWarning,
                new Separator(Orientation.VERTICAL), DataView.valuePanelToggle());
        bindingWarning.getStyleClass().add("binding-warning");
        updateBindingWarning();

        editor.setText(content);
        editor.area().textProperty().addListener((o, a, b) -> setDirty(true));
        setOnSelectionChanged(e -> editor.hidePopup());

        messages.setEditable(false);
        messages.getStyleClass().add("sql-messages");
        messagesTab.setClosable(false);
        results.getTabs().add(messagesTab);
        results.getStyleClass().add("sub-tabs");

        SplitPane split = new SplitPane(editor, results);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);

        BorderPane pane = new BorderPane(split);
        pane.setTop(bar);
        // find/replace also from the results area
        pane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (editor.handleFindKeys(e)) e.consume();
        });
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
        return InAppDialogs.showAndWait(alert).orElse(ButtonType.CANCEL) == runAnyway;
    }

    private static void showMode(Label mode, ConnectionConfig c) {
        mode.setText(c == null ? "" : c.isReadOnly() ? "read-only" : "read-write");
        mode.getStyleClass().setAll("label", c != null && !c.isReadOnly() ? "mode-write" : "hint-small");
    }

    ReadOnlyBooleanProperty runningProperty() {
        return runner.runningProperty();
    }

    /** The file this script was opened from or saved to; null if never saved. */
    Path file() {
        return file;
    }

    boolean isDirty() {
        return dirty;
    }

    void focusEditor() {
        editor.focusEditor();
    }

    // ---- running ---------------------------------------------------------

    /** ▶ Run: the selection, or the whole script. */
    void run() {
        run(editor.sqlToRun());
    }

    /** Ctrl+Enter: the selection, or only the statement at the cursor. */
    void runStatement() {
        run(editor.statementToRun());
    }

    /** Asks before unsaved edits in result grids are dropped (re-running or closing). */
    private boolean confirmDiscardResultEdits() {
        for (Tab t : results.getTabs()) {
            if (t.getContent() instanceof DataView view && view.hasEdits()) {
                results.getSelectionModel().select(t);
                if (!view.confirmDiscard()) return false;
            }
        }
        return true;
    }

    private void run(String sql) {
        if (!confirmDiscardResultEdits()) return;
        ConnectionConfig config = connection.getValue();
        if (config == null) {
            log("Select a connection first.");
            return;
        }
        if (runner.runningProperty().get()) return;
        if (boundId != null && !boundId.equals(config.getId()) && !confirmOtherConnection(config)) return;
        if (runner.run(sql, config, window(), new Results()) == ScriptRunner.Outcome.NOTHING_TO_RUN) {
            log("Nothing to run.");
        }
    }

    /** Shows a run as a log in Messages plus one tab per result set. */
    private class Results implements ScriptRunner.Listener {
        @Override
        public void started(ConnectionConfig config, int statements, Map<String, String> values) {
            results.getTabs().retainAll(messagesTab);
            messages.clear();
            results.getSelectionModel().select(messagesTab);
            log("Running " + statements + " statement(s) on " + config.getName() + "…");
            values.forEach((name, value) -> log("  :" + name + " = " + (value == null ? "NULL" : "'" + value + "'")));
        }

        @Override
        public void result(int number, StatementResult r, DatabaseSession session) {
            showResult(number, r, session);
        }

        @Override
        public void failed(int number, String sql, Throwable error) {
            log("Statement " + number + " failed: " + error.getMessage() + "\n    " + abbreviate(sql));
            results.getSelectionModel().select(messagesTab);
        }

        @Override
        public void finished(String summary) {
            log(summary);
        }
    }

    private void showResult(int number, StatementResult r, DatabaseSession session) {
        if (r.data() == null) {
            String what = r.updateCount() >= 0 ? r.updateCount() + " row(s) affected" : "OK";
            log("Statement " + number + ": " + what + " (" + r.millis() + " ms)");
            return;
        }
        int rows = r.data().rows().size();
        log("Statement " + number + ": " + rows + " row(s) fetched" +
                (r.truncated() ? " (first " + ScriptRunner.FETCH_SIZE + "; more below the grid)" : "")
                + " (" + r.millis() + " ms)");
        DataView view = new DataView();
        view.showResult(r, session);
        TableEditing.forQuery(session, r.sql(), t -> view.enableEditing(t, () -> { }));
        Tab tab = new Tab("Result " + number, view);
        tab.setTooltip(new Tooltip(abbreviate(r.sql())));
        results.getTabs().add(tab);
        results.getSelectionModel().select(tab);
    }

    void stop() {
        runner.stop();
    }

    /** Called when a session is closed (disconnect), so a script running on it does not hang. */
    void sessionClosed(DatabaseSession session) {
        runner.sessionClosed(session);
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
        SaveScriptDialog dialog = new SaveScriptDialog(window(), home, start, connection.getItems(), bindings.all(),
                connection.getValue(), file == null ? null : file.getFileName().toString(), this::defaultFileName);
        SaveScriptDialog.Target target = InAppDialogs.showAndWait(dialog).orElse(null);
        if (target == null) return false;
        // the script now belongs to the connection chosen in the dialog
        if (connection.getValue() != target.connection()) connection.setValue(target.connection());
        if (!writeTo(target.file())) return false;
        file = target.file();
        updateTitle();
        return true;
    }

    /** "Script-&lt;connection&gt;-&lt;n&gt;.sql": the tab's name if it still fits the connection, else the next free one. */
    private String defaultFileName(ConnectionConfig c, Path dir) {
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
            Files.writeString(path, editor.area().getText(), StandardCharsets.UTF_8);
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
            InAppDialogs.showAndWait(alert);
            return false;
        }
    }

    /** Asks about unsaved changes. Returns false if the user cancelled. */
    boolean confirmClose() {
        if (!confirmDiscardResultEdits()) return false;
        if (!dirty) return true;
        getTabPane().getSelectionModel().select(this);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "\"" + displayName() + "\" has unsaved changes.", save, discard, ButtonType.CANCEL);
        alert.initOwner(window());
        alert.setHeaderText("Save changes?");
        ButtonType answer = InAppDialogs.showAndWait(alert).orElse(ButtonType.CANCEL);
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
