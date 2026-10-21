package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.ConnectionStore;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.ScriptBindings;
import com.dbadmin.service.ScriptFolder;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Navigator tree on the left, open tables as tabs on the right. */
public class MainWindow implements SessionProvider {

    private final Stage stage;
    private final ConnectionStore store = new ConnectionStore();
    private final ObservableList<ConnectionConfig> connections;
    private final Map<String, DatabaseSession> sessions = new HashMap<>();
    /** Callbacks waiting for a connection that is being opened, keyed by connection id. */
    private final Map<String, List<Waiter>> connecting = new HashMap<>();
    private final SqlCompleter completer = new SqlCompleter();
    private TableSearch navigator;
    private final ScriptBindings scriptBindings = new ScriptBindings();

    /** Display order; names are only labels, the id is what identifies a connection. */
    private static final Comparator<ConnectionConfig> BY_NAME = Comparator
            .comparing(ConnectionConfig::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ConnectionConfig::getId);

    private final TreeItem<NavNode> treeRoot = new TreeItem<>();
    private final TreeView<NavNode> tree = new TreeView<>(treeRoot);
    private final TabPane tabs = new TabPane();
    private final Label status = new Label("Ready");
    private final BorderPane root = new BorderPane();
    /** Holds the window content plus in-window dialogs layered on top (see InAppDialogs). */
    private final StackPane shell = new StackPane(root);

    private final MenuItem editItem = new MenuItem("Edit Connection…");
    private final MenuItem deleteItem = new MenuItem("Delete Connection");
    private final MenuItem disconnectItem = new MenuItem("Disconnect");
    private final MenuItem refreshItem = new MenuItem("Refresh");
    private final MenuItem saveScriptItem = new MenuItem("Save Script");
    private final MenuItem saveScriptAsItem = new MenuItem("Save Script As…");
    private final MenuItem runScriptItem = new MenuItem("Run Script");
    private final MenuItem stopScriptItem = new MenuItem("Stop");

    public MainWindow(Stage stage) {
        this.stage = stage;
        InAppDialogs.install(shell);
        this.connections = FXCollections.observableArrayList(loadConnections());
        connections.sort(BY_NAME);
        stage.setOnCloseRequest(e -> {
            if (!confirmCloseScripts()) e.consume();
        });

        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new NavCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) openSelectedTable();
        });
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) openSelectedTable();
        });
        tree.setContextMenu(buildContextMenu());
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateMenuItems());
        for (ConnectionConfig c : connections) {
            treeRoot.getChildren().add(new LazyItem(NavNode.connection(c)));
        }

        Label empty = new Label("Create a connection, then double-click a table to view it.");
        empty.getStyleClass().add("hint");
        StackPane center = new StackPane(empty, tabs);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.getStyleClass().add("main-tabs");
        tabs.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(tabs.getTabs()));
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateScriptItems());

        navigator = new TableSearch(tree, () -> List.copyOf(sessions.values()), this::openTable);
        completer.setOnInvalidate(navigator::invalidate); // DDL and refresh also reload the search
        SplitPane split = new SplitPane(navigator, center);
        split.setDividerPositions(0.25);
        SplitPane.setResizableWithParent(navigator, false);

        status.setPadding(new Insets(3, 8, 3, 8));
        root.setTop(buildMenuBar());
        // a filter, so it works wherever the focus is (editor, grid, tree)
        KeyCombination closeTab = KeyCombination.keyCombination("Shortcut+W");
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (closeTab.match(e)) {
                e.consume();
                closeTab(tabs.getSelectionModel().getSelectedItem());
            }
        });
        root.setCenter(split);
        root.setBottom(status);
        updateMenuItems();
        updateScriptItems();
    }

    public Parent getRoot() {
        return shell;
    }

    public void shutdown() {
        sessions.values().forEach(DatabaseSession::close);
        sessions.clear();
    }

    // ---- layout ----------------------------------------------------------

    private MenuBar buildMenuBar() {
        MenuItem newItem = new MenuItem("New Connection…");
        newItem.setAccelerator(KeyCombination.keyCombination("Shortcut+N"));
        newItem.setOnAction(e -> newConnection());
        editItem.setOnAction(e -> editConnection());
        deleteItem.setOnAction(e -> deleteConnection());
        disconnectItem.setOnAction(e -> selectedConnection().ifPresent(this::disconnect));
        refreshItem.setAccelerator(KeyCombination.keyCombination("F5"));
        refreshItem.setOnAction(e -> refreshSelected());
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> {
            if (confirmCloseScripts()) stage.close();
        });

        Menu connection = new Menu("Connection", null, newItem, editItem, deleteItem,
                new SeparatorMenuItem(), disconnectItem, refreshItem,
                new SeparatorMenuItem(), exitItem);

        MenuItem newScript = new MenuItem("New Script");
        newScript.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+N"));
        newScript.setOnAction(e -> newScript());
        MenuItem openScript = new MenuItem("Open Script…");
        openScript.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        openScript.setOnAction(e -> openScript());
        saveScriptItem.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        saveScriptItem.setOnAction(e -> currentScript().ifPresent(ScriptTab::save));
        saveScriptAsItem.setOnAction(e -> currentScript().ifPresent(ScriptTab::saveAs));
        runScriptItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Enter"));
        runScriptItem.setOnAction(e -> currentScript().ifPresent(ScriptTab::run));
        stopScriptItem.setOnAction(e -> currentScript().ifPresent(ScriptTab::stop));

        Menu script = new Menu("Script", null, newScript, openScript,
                new SeparatorMenuItem(), saveScriptItem, saveScriptAsItem,
                new SeparatorMenuItem(), runScriptItem, stopScriptItem);
        return new MenuBar(connection, script);
    }

    private ContextMenu buildContextMenu() {
        MenuItem open = new MenuItem("Open");
        open.setOnAction(e -> openSelectedTable());
        MenuItem edit = new MenuItem("Edit Connection");
        edit.setOnAction(e -> editConnection());
        MenuItem delete = new MenuItem("Delete Connection");
        delete.setOnAction(e -> deleteConnection());
        MenuItem disconnect = new MenuItem("Disconnect");
        disconnect.setOnAction(e -> selectedConnection().ifPresent(this::disconnect));
        MenuItem refresh = new MenuItem("Refresh (F5)");
        refresh.setOnAction(e -> refreshSelected());
        MenuItem reconnect = new MenuItem("Reconnect");
        reconnect.setOnAction(e -> selectedConnection().ifPresent(this::reconnect));
        // scripts for the right-clicked connection (the tree selects the row on right-click)
        MenuItem newScript = new MenuItem("New Script");
        newScript.setOnAction(e -> newScript());
        MenuItem openScript = new MenuItem("Open Script…");
        openScript.setOnAction(e -> openScript());
        SeparatorMenuItem scriptSeparator = new SeparatorMenuItem();
        ContextMenu menu = new ContextMenu(open, newScript, openScript, scriptSeparator, edit, delete, disconnect, reconnect,
                new SeparatorMenuItem(), refresh);
        menu.setOnShowing(e -> {
            NavNode n = selectedNode();
            boolean hasConnection = n != null && n.connection() != null;
            newScript.setVisible(hasConnection);
            openScript.setVisible(hasConnection);
            scriptSeparator.setVisible(hasConnection);
            open.setVisible(n != null && n.kind() == NavNode.Kind.TABLE);
            boolean onConnection = n != null && n.kind() == NavNode.Kind.CONNECTION;
            edit.setVisible(onConnection);
            delete.setVisible(onConnection);
            disconnect.setVisible(n != null && sessions.containsKey(idOf(n)));
            reconnect.setVisible(n != null && sessions.containsKey(idOf(n)));
            refresh.setDisable(n == null);
        });
        return menu;
    }

    private void updateScriptItems() {
        Optional<ScriptTab> script = currentScript();
        boolean running = script.map(s -> s.runningProperty().get()).orElse(false);
        saveScriptItem.setDisable(script.isEmpty());
        saveScriptAsItem.setDisable(script.isEmpty());
        runScriptItem.setDisable(script.isEmpty() || running);
        stopScriptItem.setDisable(!running);
    }

    private void updateMenuItems() {
        NavNode n = selectedNode();
        boolean onConnection = n != null && n.kind() == NavNode.Kind.CONNECTION;
        editItem.setDisable(!onConnection);
        deleteItem.setDisable(!onConnection);
        disconnectItem.setDisable(n == null || !sessions.containsKey(idOf(n)));
        refreshItem.setDisable(n == null || n.kind() == NavNode.Kind.MESSAGE);
    }

    // ---- connections -----------------------------------------------------

    private List<ConnectionConfig> loadConnections() {
        try {
            return store.load();
        } catch (IllegalStateException e) {
            error("Could not load saved connections", e);
            return new ArrayList<>();
        }
    }

    private void saveConnections() {
        try {
            store.save(connections);
        } catch (IllegalStateException e) {
            error("Could not save connections", e);
        }
    }

    private void newConnection() {
        openConnectionTab(new ConnectionTab(null, c -> {
            connections.add(c);
            LazyItem item = new LazyItem(NavNode.connection(c));
            treeRoot.getChildren().add(item);
            sortConnections();
            saveConnections();
            tree.getSelectionModel().select(item);
            item.setExpanded(true);
        }));
    }

    private void editConnection() {
        TreeItem<NavNode> selected = connectionItem(tree.getSelectionModel().getSelectedItem());
        if (selected == null) return;
        ConnectionConfig edited = selected.getValue().connection();
        for (Tab t : tabs.getTabs()) {
            if (t instanceof ConnectionTab ct && edited.getId().equals(ct.editedId())) {
                tabs.getSelectionModel().select(t);
                return;
            }
        }
        openConnectionTab(new ConnectionTab(edited, updated -> {
            // look the connection up again: the tab may have stayed open while things changed
            ConnectionConfig current = connections.stream()
                    .filter(c -> c.getId().equals(updated.getId())).findFirst().orElse(null);
            if (current == null) {
                status.setText("Not saved: the connection was deleted");
                return;
            }
            disconnect(current);
            connections.set(connections.indexOf(current), updated);
            LazyItem replacement = new LazyItem(NavNode.connection(updated));
            for (int i = 0; i < treeRoot.getChildren().size(); i++) {
                if (treeRoot.getChildren().get(i).getValue().connection() == current) {
                    treeRoot.getChildren().set(i, replacement);
                }
            }
            sortConnections(); // the name may have changed
            saveConnections();
            tree.getSelectionModel().select(replacement);
        }));
    }

    /** Closes a tab the way its ✕ button does, so unsaved scripts still ask first. */
    private void closeTab(Tab tab) {
        if (tab == null || !tab.isClosable()) return;
        if (tab.getOnCloseRequest() != null) {
            javafx.event.Event request = new javafx.event.Event(Tab.TAB_CLOSE_REQUEST_EVENT);
            tab.getOnCloseRequest().handle(request);
            if (request.isConsumed()) return;
        }
        tabs.getTabs().remove(tab);
        if (tab.getOnClosed() != null) tab.getOnClosed().handle(new javafx.event.Event(Tab.CLOSED_EVENT));
    }

    private void sortConnections() {
        connections.sort(BY_NAME);
        treeRoot.getChildren().sort(Comparator.comparing(item -> item.getValue().connection(), BY_NAME));
    }

    private void openConnectionTab(ConnectionTab tab) {
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    private void deleteConnection() {
        TreeItem<NavNode> item = connectionItem(tree.getSelectionModel().getSelectedItem());
        if (item == null) return;
        ConnectionConfig c = item.getValue().connection();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete connection \"" + c.getName() + "\"? The database itself is not touched.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText(null);
        if (InAppDialogs.showAndWait(confirm).orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        disconnect(c);
        tabs.getTabs().removeIf(t -> t instanceof ConnectionTab ct && c.getId().equals(ct.editedId()));
        connections.remove(c);
        saveConnections();
        treeRoot.getChildren().remove(item);
    }

    private void disconnect(ConnectionConfig c) {
        DatabaseSession s = sessions.remove(c.getId());
        if (s == null) return;
        tabs.getTabs().removeIf(t -> t instanceof TableTab tt && tt.key().startsWith(c.getId() + "|"));
        completer.invalidate(s); // also drops it from the table search
        s.close();
        for (Tab t : tabs.getTabs()) {
            if (t instanceof ScriptTab st) st.sessionClosed(s);
        }
        for (TreeItem<NavNode> item : treeRoot.getChildren()) {
            if (item.getValue().connection() == c && item instanceof LazyItem lazy) {
                lazy.reset();
                item.setExpanded(false);
            }
        }
        tree.refresh();
        updateMenuItems();
        status.setText("Disconnected from " + c.getName());
    }

    /** Closes and reopens a connection, then reloads its tree. */
    private void reconnect(ConnectionConfig c) {
        disconnect(c);
        for (TreeItem<NavNode> item : treeRoot.getChildren()) {
            if (item.getValue().connection() == c) {
                tree.getSelectionModel().select(item);
                item.setExpanded(true);
            }
        }
    }

    // ---- tree loading ----------------------------------------------------

    private void load(LazyItem item) {
        NavNode node = item.getValue();
        ConnectionConfig c = node.connection();
        DatabaseSession session = sessions.get(c.getId());

        if (session == null) {
            withSession(c, s -> load(item), ex -> {
                item.failed();
                error("Could not connect to " + c.getName(), ex);
            });
            return;
        }

        switch (node.kind()) {
            case CONNECTION -> Async.run(session, session::listNamespaces, all -> {
                // system schemas stay usable in scripts and autocomplete, just not listed here
                List<Namespace> namespaces = all.stream().filter(ns -> !ns.isSystem(c.getType())).toList();
                item.getChildren().setAll(namespaces.stream()
                        .map(ns -> (TreeItem<NavNode>) new LazyItem(NavNode.namespace(c, ns)))
                        .toList());
                if (namespaces.isEmpty()) item.getChildren().add(message("(no schemas)"));
                if (namespaces.size() == 1) item.getChildren().get(0).setExpanded(true);
            }, ex -> loadFailed(item, ex));
            case NAMESPACE -> {
                Namespace ns = node.namespace();
                Async.run(session, () -> session.listTables(ns), tables -> {
                    TreeItem<NavNode> tablesFolder = folder(c, "Tables", tables.stream().filter(t -> !t.isView()).toList());
                    TreeItem<NavNode> viewsFolder = folder(c, "Views",
                            tables.stream().filter(t -> t.isView() && !t.isMaterializedView()).toList());
                    List<TableRef> materialized = tables.stream().filter(TableRef::isMaterializedView).toList();
                    item.getChildren().setAll(List.of(tablesFolder, viewsFolder));
                    // only PostgreSQL has materialized views; elsewhere the folder would always be empty
                    if (c.getType() == DbType.POSTGRESQL || !materialized.isEmpty()) {
                        item.getChildren().add(folder(c, "Materialized Views", materialized));
                    }
                    tablesFolder.setExpanded(true);
                    status.setText(ns.label() + ": " + tables.size() + " tables/views");
                }, ex -> loadFailed(item, ex));
            }
            default -> { }
        }
    }

    @Override
    public void withSession(ConnectionConfig c, Consumer<DatabaseSession> onReady, Consumer<Throwable> onError) {
        DatabaseSession existing = sessions.get(c.getId());
        if (existing != null) {
            onReady.accept(existing);
            return;
        }
        List<Waiter> waiters = connecting.get(c.getId());
        if (waiters != null) {
            waiters.add(new Waiter(onReady, onError));
            return;
        }
        waiters = new ArrayList<>(List.of(new Waiter(onReady, onError)));
        connecting.put(c.getId(), waiters);
        status.setText("Connecting to " + c.getName() + "…");
        Async.run(() -> DatabaseSession.open(c), s -> {
            List<Waiter> ready = connecting.remove(c.getId());
            if (!connections.contains(c)) {
                // edited or deleted while connecting
                s.close();
                ready.forEach(w -> w.onError().accept(new IllegalStateException("Connection was changed")));
                return;
            }
            sessions.put(c.getId(), s);
            s.setOnReconnect(name -> javafx.application.Platform.runLater(
                    () -> status.setText("Connection to " + name + " was lost; reconnected")));
            status.setText("Connected to " + c.getName() + (c.isReadOnly() ? " (read-only)" : " (read-write)"));
            navigator.refresh(); // a search in progress now includes this connection
            tree.refresh();
            updateMenuItems();
            ready.forEach(w -> w.onReady().accept(s));
        }, ex -> {
            status.setText("Connection to " + c.getName() + " failed");
            connecting.remove(c.getId()).forEach(w -> w.onError().accept(ex));
        });
    }

    @Override
    public Optional<DatabaseSession> openSession(ConnectionConfig c) {
        return Optional.ofNullable(sessions.get(c.getId()));
    }

    private record Waiter(Consumer<DatabaseSession> onReady, Consumer<Throwable> onError) {
    }

    private TreeItem<NavNode> folder(ConnectionConfig c, String name, List<TableRef> tables) {
        TreeItem<NavNode> folder = new TreeItem<>(NavNode.folder(c, name + " (" + tables.size() + ")"));
        for (TableRef t : tables) folder.getChildren().add(new TreeItem<>(NavNode.table(c, t)));
        return folder;
    }

    private void loadFailed(LazyItem item, Throwable ex) {
        item.failed();
        status.setText("Error: " + ex.getMessage());
        error("Could not load " + item.getValue(), ex);
    }

    private void refreshSelected() {
        TreeItem<NavNode> item = tree.getSelectionModel().getSelectedItem();
        while (item != null && !(item instanceof LazyItem)) item = item.getParent();
        if (item instanceof LazyItem lazy) {
            completer.invalidate(sessions.get(idOf(lazy.getValue())));
            lazy.reset();
            lazy.setExpanded(false);
            lazy.setExpanded(true);
        }
    }

    // ---- tables ----------------------------------------------------------

    private void openSelectedTable() {
        NavNode node = selectedNode();
        if (node == null || node.kind() != NavNode.Kind.TABLE) return;
        DatabaseSession session = sessions.get(node.connection().getId());
        if (session != null) openTable(session, node.table());
    }

    /** Opens a table's tab, or switches to it if already open. */
    private void openTable(DatabaseSession session, TableRef table) {
        String key = TableTab.key(session, table);
        for (Tab t : tabs.getTabs()) {
            if (t instanceof TableTab tt && tt.key().equals(key)) {
                tabs.getSelectionModel().select(t);
                return;
            }
        }
        TableTab tab = new TableTab(session, table, this, completer);
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    // ---- scripts ---------------------------------------------------------

    private void newScript() {
        ConnectionConfig c = defaultConnection();
        List<String> open = tabs.getTabs().stream()
                .filter(t -> t instanceof ScriptTab).map(t -> ((ScriptTab) t).displayName()).toList();
        String name = ScriptFolder.nextName(scriptDir(), c == null ? null : c.getName(), open);
        openScriptTab(new ScriptTab(name, null, "", connections, c, this, completer, scriptBindings));
    }

    private void openScript() {
        List<Path> files = InAppDialogs.showAndWait(new OpenScriptDialog(stage, connections, scriptBindings.all(),
                scriptDir(), selectedConnection().orElse(null))).orElse(List.of());
        for (Path f : files) {
            // already open: just switch to it
            Optional<Tab> open = tabs.getTabs().stream()
                    .filter(t -> t instanceof ScriptTab st && f.equals(st.file()))
                    .findFirst();
            if (open.isPresent()) {
                tabs.getSelectionModel().select(open.get());
                continue;
            }
            try {
                String content = ScriptTab.read(f);
                // only the connection the script was saved for; never guess (e.g. from the tree selection)
                String boundId = scriptBindings.connectionFor(f);
                ConnectionConfig bound = connections.stream()
                        .filter(c -> c.getId().equals(boundId)).findFirst().orElse(null);
                openScriptTab(new ScriptTab(f.getFileName().toString(), f, content, connections, bound, this, completer, scriptBindings));
            } catch (IOException e) {
                error("Could not open " + f, e);
            }
        }
    }

    /** The default script folder, or null if it cannot be created. */
    private Path scriptDir() {
        try {
            return ScriptFolder.defaultDir();
        } catch (UncheckedIOException e) {
            status.setText(e.getMessage());
            return null;
        }
    }

    private void openScriptTab(ScriptTab tab) {
        tab.runningProperty().addListener((o, a, b) -> updateScriptItems());
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        tab.focusEditor(); // waits until the tab's editor is on screen (else focus stays e.g. in the search box)
    }

    /** The connection selected in the tree, or the only/first one. */
    private ConnectionConfig defaultConnection() {
        return selectedConnection().orElse(connections.isEmpty() ? null : connections.get(0));
    }

    private Optional<ScriptTab> currentScript() {
        return tabs.getSelectionModel().getSelectedItem() instanceof ScriptTab st ? Optional.of(st) : Optional.empty();
    }

    /** Asks about every unsaved script. Returns false if the user cancelled. */
    private boolean confirmCloseScripts() {
        for (Tab t : List.copyOf(tabs.getTabs())) {
            if (t instanceof ScriptTab st && !st.confirmClose()) return false;
        }
        return true;
    }

    // ---- helpers ---------------------------------------------------------

    private NavNode selectedNode() {
        TreeItem<NavNode> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private Optional<ConnectionConfig> selectedConnection() {
        NavNode n = selectedNode();
        return n == null ? Optional.empty() : Optional.ofNullable(n.connection());
    }

    private static TreeItem<NavNode> connectionItem(TreeItem<NavNode> item) {
        while (item != null && (item.getValue() == null || item.getValue().kind() != NavNode.Kind.CONNECTION)) {
            item = item.getParent();
        }
        return item;
    }

    private static String idOf(NavNode n) {
        return n.connection() == null ? "" : n.connection().getId();
    }

    private static TreeItem<NavNode> message(String text) {
        return new TreeItem<>(NavNode.message(text));
    }

    private void error(String header, Throwable ex) {
        Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(ex.getMessage()), ButtonType.OK);
        if (stage.isShowing()) alert.initOwner(stage);
        alert.setHeaderText(header);
        alert.getDialogPane().setMinWidth(480);
        InAppDialogs.show(alert);
    }

    /** A tree item that fetches its children from the database the first time it is expanded. */
    private class LazyItem extends TreeItem<NavNode> {
        private boolean loaded;

        LazyItem(NavNode node) {
            super(node);
            reset();
            expandedProperty().addListener((obs, was, is) -> {
                if (is && !loaded) {
                    loaded = true;
                    load(this);
                }
            });
        }

        void reset() {
            loaded = false;
            getChildren().setAll(List.of(message("Loading…")));
        }

        /** Allow a retry on the next expand. */
        void failed() {
            reset();
            setExpanded(false);
        }
    }

    private class NavCell extends TreeCell<NavNode> {
        private final Label icon = new Label();

        @Override
        protected void updateItem(NavNode node, boolean empty) {
            super.updateItem(node, empty);
            icon.getStyleClass().setAll("nav-icon");
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(node.toString());
            switch (node.kind()) {
                case CONNECTION -> {
                    setGraphic(DbIcons.connection(node.connection().getType(), sessions.containsKey(idOf(node))));
                    return;
                }
                case NAMESPACE -> icon.setText("◆");
                case FOLDER -> icon.setText("▤");
                case TABLE -> {
                    TableRef t = node.table();
                    icon.setText(t.isMaterializedView() ? "◩" : t.isView() ? "◫" : "▦");
                    icon.getStyleClass().add(t.isMaterializedView() ? "matview" : t.isView() ? "view" : "table");
                }
                case MESSAGE -> icon.setText("");
            }
            setGraphic(icon);
        }
    }
}
