package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.ConnectionStore;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.ScriptBindings;
import com.dbadmin.service.ScriptFolder;
import com.dbadmin.service.SessionManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Navigator tree on the left, open tables as tabs on the right. */
public class MainWindow implements SessionProvider {

    private final Stage stage;
    private final ConnectionStore store = new ConnectionStore();
    private final ObservableList<ConnectionConfig> connections;
    private final SessionManager sessions;
    private final SqlCompleter completer = new SqlCompleter();
    private TableSearch navigator;
    private SplitPane mainSplit;
    private double navigatorDivider = 0.25;
    private final ScriptBindings scriptBindings = new ScriptBindings();

    /** Display order; names are only labels, the id is what identifies a connection. */
    private static final Comparator<ConnectionConfig> BY_NAME = Comparator
            .comparing(ConnectionConfig::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ConnectionConfig::getId);

    private final TreeItem<NavNode> treeRoot = new TreeItem<>();
    private final TreeView<NavNode> tree = new TreeView<>(treeRoot);
    private final TabPane tabs = new TabPane();
    private final AppFooter footer = new AppFooter("Ready");
    private final Label status = footer.status();
    /** The license: footer line, License dialog, and the check before the compares open. */
    private final LicenseGate license;
    private final BorderPane root = new BorderPane();
    /** Holds the window content plus in-window dialogs layered on top (see InAppDialogs). */
    private final StackPane shell = new StackPane(root);

    private final MenuItem editItem = new MenuItem("Edit Connection…");
    private final MenuItem deleteItem = new MenuItem("Delete Connection");
    private final MenuItem disconnectItem = new MenuItem("Disconnect");
    private final MenuItem refreshItem = new MenuItem("Refresh");
    private final MenuItem saveScriptItem = new MenuItem("Save Script");
    private final MenuItem saveScriptAsItem = new MenuItem("Save Script As…");
    private final MenuItem runStatementItem = new MenuItem("Run Statement");
    private final MenuItem runScriptItem = new MenuItem("Run Script");
    private final MenuItem stopScriptItem = new MenuItem("Stop");

    public MainWindow(Stage stage) {
        this.stage = stage;
        InAppDialogs.install(shell);
        this.connections = FXCollections.observableArrayList(loadConnections());
        connections.sort(BY_NAME);
        sessions = new SessionManager(DatabaseSession::open, Async.background(), Platform::runLater,
                connections::contains, new SessionManager.Listener() {
            @Override
            public void connecting(ConnectionConfig c) {
                status.setText("Connecting to " + c.getName() + "…");
            }

            @Override
            public void opened(ConnectionConfig c, DatabaseSession s) {
                s.setOnReconnect(name -> Platform.runLater(() -> {
                    status.setText("Connection to " + name + " was lost; reconnected");
                    completer.invalidate(s); // like Reconnect: tables may have changed meanwhile
                }));
                status.setText("Connected to " + c.getName() + (c.isReadOnly() ? " (read-only)" : " (read-write)"));
                navigator.refresh(); // a search in progress now includes this connection
                tree.refresh();
                updateMenuItems();
            }

            @Override
            public void failed(ConnectionConfig c, Throwable error) {
                status.setText("Connection to " + c.getName() + " failed");
            }
        });
        stage.setOnCloseRequest(e -> {
            if (!confirmCloseTabs()) e.consume();
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
        // right-click a tab: Close / Close Others / Close Tabs to the Left / to the Right / Close All
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> {
            while (change.next()) {
                for (Tab t : change.getAddedSubList()) {
                    if (t.getContextMenu() == null) t.setContextMenu(tabMenu(t));
                }
            }
        });

        navigator = new TableSearch(tree, sessions::all, this::openTable);
        navigator.setMenus((session, t) -> {
            MenuItem open = new MenuItem("Open");
            open.setOnAction(e -> openTable(session, t));
            MenuItem exportData = new MenuItem("Export Data…");
            exportData.setOnAction(e -> TableTransfer.export(stage, session, t, null, transferHost));
            if (t.isView()) return List.of(open, exportData);
            MenuItem importData = new MenuItem("Import Data…");
            importData.setOnAction(e -> TableTransfer.importData(stage, session, t, transferHost));
            importData.setDisable(session.getConfig().isReadOnly());
            return List.of(open, exportData, importData);
        }, session -> {
            MenuItem script = new MenuItem("New Script");
            script.setOnAction(e -> newScript(session.getConfig()));
            return List.of(script);
        });
        completer.setOnInvalidate(navigator::invalidate); // DDL and refresh also reload the search
        mainSplit = new SplitPane(navigator, center);
        mainSplit.setDividerPositions(0.25);
        SplitPane.setResizableWithParent(navigator, false);
        SplitPane split = mainSplit;

        root.setTop(buildTopBar());
        // a filter, so it works wherever the focus is (editor, grid, tree)
        KeyCombination closeTab = KeyCombination.keyCombination("Shortcut+W");
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (closeTab.match(e)) {
                e.consume();
                closeTab(tabs.getSelectionModel().getSelectedItem());
            }
        });
        root.setCenter(split);
        root.setBottom(footer);
        license = new LicenseGate(stage, footer, status::setText);
        installScriptDrop();
        updateMenuItems();
        updateScriptItems();
    }

    public Parent getRoot() {
        return shell;
    }

    public void shutdown() {
        sessions.closeAll();
    }

    // ---- layout ----------------------------------------------------------

    /** Connection and Script menus on the left, the gear menu (settings) on the far right. */
    private HBox buildTopBar() {
        javafx.scene.shape.SVGPath gear = new javafx.scene.shape.SVGPath();
        // Material Design "settings" icon (Apache 2.0)
        gear.setContent("M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,"
                + "0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81"
                + "c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29"
                + "L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.8,"
                + "11.69,4.8,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,"
                + "0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-"
                + "0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32"
                + "c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6"
                + "s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z");
        gear.getStyleClass().add("gear-icon");
        gear.setScaleX(0.75);
        gear.setScaleY(0.75);

        MenuItem settings = new MenuItem("Settings…");
        settings.setOnAction(e -> openSettings());
        MenuItem licenseItem = new MenuItem("License…");
        licenseItem.setOnAction(e -> license.openDialog());
        MenuItem about = new MenuItem("About DBArbiter");
        about.setOnAction(e -> {
            Alert info = new Alert(Alert.AlertType.INFORMATION,
                    "A desktop database browser and SQL editor.\nPostgreSQL · MySQL / MariaDB · SQLite"
                            + "\n\nAuthor: Hafiz Hisbullah\nzhenzhu.gamedev@gmail.com", ButtonType.OK);
            info.setTitle("About");
            info.setHeaderText("DBArbiter " + AppFooter.version());
            InAppDialogs.show(info);
        });
        MenuButton gearMenu = new MenuButton(null, new javafx.scene.Group(gear), settings, licenseItem, new SeparatorMenuItem(), about);
        gearMenu.getStyleClass().add("gear-menu");
        gearMenu.setTooltip(new javafx.scene.control.Tooltip("Settings"));

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        // hides the connections panel to give tabs the whole width; showing it restores its size
        javafx.scene.control.ToggleButton navToggle = new javafx.scene.control.ToggleButton("☰");
        navToggle.getStyleClass().add("nav-toggle");
        navToggle.setSelected(true);
        navToggle.setFocusTraversable(false);
        navToggle.setTooltip(new javafx.scene.control.Tooltip("Show or hide the connections panel"));
        navToggle.selectedProperty().addListener((o, a, show) -> {
            if (show && !mainSplit.getItems().contains(navigator)) {
                mainSplit.getItems().add(0, navigator);
                mainSplit.setDividerPositions(navigatorDivider);
            } else if (!show && mainSplit.getItems().contains(navigator)) {
                navigatorDivider = mainSplit.getDividerPositions()[0];
                mainSplit.getItems().remove(navigator);
            }
        });
        HBox bar = new HBox(navToggle, buildMenuBar(), spacer, gearMenu);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private void showTab(Tab tab) {
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    private void openSettings() {
        InAppDialogs.showAndWait(new SettingsDialog(stage)).ifPresent(settings -> {
            try {
                Themes.save(settings);
            } catch (java.io.UncheckedIOException e) {
                error("Could not save settings", e);
            }
        });
    }

    /** Opens a saved Table or Data Compare report in a new tab. */
    private void openCompareReport() {
        Path file = CompareReportChooser.open(stage);
        if (file == null) return;
        try {
            Object report = com.dbadmin.service.compare.CompareReportFiles.open(file);
            if (report instanceof com.dbadmin.service.compare.DataCompareReport data) {
                DataCompareTab tab = new DataCompareTab(connections, this, null);
                showTab(tab);
                tab.showSaved(data, file);
            } else if (report instanceof com.dbadmin.service.compare.SchemaCompareReport table) {
                SchemaCompareTab tab = new SchemaCompareTab(connections, this, null, this::showTab);
                showTab(tab);
                tab.showSaved(table, file);
            }
        } catch (IOException e) {
            error("Could not open " + file.getFileName(), e);
        }
    }

    /**
     * Asks GitHub in the background whether a newer release is out; if so the footer links to it. Skipped when
     * turned off in Settings and for builds run from the IDE (version "dev"). Failures are silent.
     */
    public void checkForUpdates(javafx.application.HostServices browser) {
        String current = AppFooter.version();
        if (!Themes.settings().isCheckForUpdates() || current.equals("dev")) return;
        Async.run(() -> com.dbadmin.service.UpdateCheck.newerThan(current),
                newer -> newer.ifPresent(r -> footer.showUpdate(r.version(), () -> browser.showDocument(r.page()))),
                ex -> { });
    }

    /** Styles the main window's scene with the current theme (and follows later changes). */
    public static void applyTheme(javafx.scene.Scene scene) {
        Themes.install(scene);
    }

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
            if (confirmCloseTabs()) stage.close();
        });

        Menu connection = new Menu("Connection", null, newItem, editItem, deleteItem,
                new SeparatorMenuItem(), disconnectItem, refreshItem,
                new SeparatorMenuItem(), exitItem);

        MenuItem newScript = new MenuItem("New Script");
        newScript.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+N"));
        newScript.setOnAction(e -> newScript(null)); // no connection until the user picks one
        MenuItem openScript = new MenuItem("Open Script…");
        openScript.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        openScript.setOnAction(e -> openScript());
        saveScriptItem.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        saveScriptItem.setOnAction(e -> currentScript().ifPresent(ScriptTab::save));
        saveScriptAsItem.setOnAction(e -> currentScript().ifPresent(ScriptTab::saveAs));
        runStatementItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Enter"));
        runStatementItem.setOnAction(e -> currentScript().ifPresent(ScriptTab::runStatement));
        runScriptItem.setOnAction(e -> currentScript().ifPresent(ScriptTab::run));
        stopScriptItem.setOnAction(e -> currentScript().ifPresent(ScriptTab::stop));

        Menu script = new Menu("Script", null, newScript, openScript,
                new SeparatorMenuItem(), saveScriptItem, saveScriptAsItem,
                new SeparatorMenuItem(), runStatementItem, runScriptItem, stopScriptItem);
        MenuItem compareSchema = new MenuItem("Compare Table…");
        compareSchema.setOnAction(e -> {
            if (license.allows("Compare Table and Compare Data")) showTab(new SchemaCompareTab(connections, this, selectedConnection().orElse(null), this::showTab));
        });
        MenuItem compareData = new MenuItem("Compare Data…");
        compareData.setOnAction(e -> {
            if (license.allows("Compare Table and Compare Data")) showTab(new DataCompareTab(connections, this, selectedConnection().orElse(null)));
        });
        MenuItem openReport = new MenuItem("Open Compare Report…");
        openReport.setOnAction(e -> {
            if (license.allows("Compare Table and Compare Data")) openCompareReport();
        });
        Menu tools = new Menu("Tools", null, compareSchema, compareData, new SeparatorMenuItem(), openReport);
        return new MenuBar(connection, script, tools);
    }

    private ContextMenu buildContextMenu() {
        MenuItem open = new MenuItem("Open");
        open.setOnAction(e -> openSelectedTable());
        MenuItem exportData = new MenuItem("Export Data…");
        exportData.setOnAction(e -> withSelectedTable((session, t) -> TableTransfer.export(stage, session, t, null, transferHost)));
        MenuItem importData = new MenuItem("Import Data…");
        importData.setOnAction(e -> withSelectedTable((session, t) -> TableTransfer.importData(stage, session, t, transferHost)));
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
        newScript.setOnAction(e -> newScript(selectedConnection().orElse(null)));
        MenuItem openScript = new MenuItem("Open Script…");
        openScript.setOnAction(e -> openScript());
        MenuItem openLastScript = new MenuItem("Open Last Script");
        SeparatorMenuItem scriptSeparator = new SeparatorMenuItem();
        SeparatorMenuItem dataSeparator = new SeparatorMenuItem();
        ContextMenu menu = new ContextMenu(open, exportData, importData, dataSeparator, newScript, openScript, openLastScript,
                scriptSeparator, edit, delete, disconnect, reconnect, new SeparatorMenuItem(), refresh);
        menu.setOnShowing(e -> {
            NavNode n = selectedNode();
            boolean hasConnection = n != null && n.connection() != null;
            newScript.setVisible(hasConnection);
            openScript.setVisible(hasConnection);
            openLastScript.setVisible(hasConnection);
            Optional<Path> last = hasConnection ? lastScriptFor(n.connection()) : Optional.empty();
            openLastScript.setText(last.map(p -> "Open Last Script (" + p.getFileName() + ")").orElse("Open Last Script"));
            openLastScript.setDisable(last.isEmpty());
            openLastScript.setOnAction(ev -> last.ifPresent(p -> openScripts(List.of(p))));
            scriptSeparator.setVisible(hasConnection);
            boolean onTable = n != null && n.kind() == NavNode.Kind.TABLE;
            open.setVisible(onTable);
            exportData.setVisible(onTable);
            importData.setVisible(onTable && !n.table().isView());
            importData.setDisable(onTable && n.connection().isReadOnly());
            dataSeparator.setVisible(onTable);
            boolean onConnection = n != null && n.kind() == NavNode.Kind.CONNECTION;
            edit.setVisible(onConnection);
            delete.setVisible(onConnection);
            disconnect.setVisible(n != null && sessions.isOpen(idOf(n)));
            reconnect.setVisible(n != null && sessions.isOpen(idOf(n)));
            refresh.setDisable(n == null);
        });
        return menu;
    }

    private void updateScriptItems() {
        Optional<ScriptTab> script = currentScript();
        boolean running = script.map(s -> s.runningProperty().get()).orElse(false);
        saveScriptItem.setDisable(script.isEmpty());
        saveScriptAsItem.setDisable(script.isEmpty());
        runStatementItem.setDisable(script.isEmpty() || running);
        runScriptItem.setDisable(script.isEmpty() || running);
        stopScriptItem.setDisable(!running);
    }

    private void updateMenuItems() {
        NavNode n = selectedNode();
        boolean onConnection = n != null && n.kind() == NavNode.Kind.CONNECTION;
        editItem.setDisable(!onConnection);
        deleteItem.setDisable(!onConnection);
        disconnectItem.setDisable(n == null || !sessions.isOpen(idOf(n)));
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
            if (!disconnect(current)) {
                status.setText("Not saved: " + current.getName() + " still has unsaved table edits; save or discard them, then edit it again");
                return;
            }
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

    private ContextMenu tabMenu(Tab tab) {
        MenuItem close = new MenuItem("Close");
        close.setOnAction(e -> closeTab(tab));
        MenuItem others = new MenuItem("Close Others");
        others.setOnAction(e -> closeTabs(tabs.getTabs().stream().filter(t -> t != tab).toList()));
        MenuItem left = new MenuItem("Close Tabs to the Left");
        left.setOnAction(e -> closeTabs(List.copyOf(tabs.getTabs().subList(0, tabs.getTabs().indexOf(tab)))));
        MenuItem right = new MenuItem("Close Tabs to the Right");
        right.setOnAction(e -> {
            int i = tabs.getTabs().indexOf(tab);
            closeTabs(List.copyOf(tabs.getTabs().subList(i + 1, tabs.getTabs().size())));
        });
        MenuItem all = new MenuItem("Close All");
        all.setOnAction(e -> closeTabs(List.copyOf(tabs.getTabs())));
        ContextMenu menu = new ContextMenu(close, others, left, right, new SeparatorMenuItem(), all);
        menu.setOnShowing(e -> {
            int i = tabs.getTabs().indexOf(tab);
            others.setDisable(tabs.getTabs().size() < 2);
            left.setDisable(i <= 0);
            right.setDisable(i < 0 || i == tabs.getTabs().size() - 1);
        });
        return menu;
    }

    /** Closes the tabs one by one, each as its ✕ would; stops at the first one the user keeps open (Cancel). */
    private void closeTabs(List<Tab> list) {
        for (Tab t : list) {
            closeTab(t);
            if (tabs.getTabs().contains(t) && t.isClosable()) return;
        }
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
        if (!disconnect(c)) return;
        tabs.getTabs().removeIf(t -> t instanceof ConnectionTab ct && c.getId().equals(ct.editedId()));
        connections.remove(c);
        saveConnections();
        treeRoot.getChildren().remove(item);
    }

    /**
     * Closes the connection and its table tabs. Unsaved cell edits in those tabs and in the result grids of its
     * scripts are asked about first; false (nothing closed) if the user kept them.
     */
    private boolean disconnect(ConnectionConfig c) {
        for (Tab t : List.copyOf(tabs.getTabs())) {
            if (t instanceof TableTab tt && tt.key().startsWith(c.getId() + "|") && !tt.confirmClose()) return false;
            if (t instanceof ScriptTab st && !st.confirmConnectionClose(c)) return false;
        }
        DatabaseSession s = sessions.close(c.getId()).orElse(null);
        if (s == null) return true;
        tabs.getTabs().removeIf(t -> t instanceof TableTab tt && tt.key().startsWith(c.getId() + "|"));
        completer.invalidate(s); // also drops it from the table search
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
        return true;
    }

    /** Closes and reopens a connection, then reloads its tree. */
    private void reconnect(ConnectionConfig c) {
        if (!disconnect(c)) return;
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
        DatabaseSession session = sessions.get(c.getId()).orElse(null);

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

    /** Connections whose password was typed in the prompt (not saved): a failed connect asks again next time. */
    private final java.util.Set<String> promptedPasswords = new java.util.HashSet<>();

    /**
     * Opens (or reuses) the connection's session. A connection with "Save password" off is asked for its password
     * first, unless it is already open or the password is in memory (typed earlier in this run).
     */
    @Override
    public void withSession(ConnectionConfig c, Consumer<DatabaseSession> onReady, Consumer<Throwable> onError) {
        boolean ask = !c.getType().isFileBased() && !c.isSavePassword() && c.getPassword() == null && sessions.get(c.getId()).isEmpty();
        if (ask) {
            String typed = InAppDialogs.showAndWait(new PasswordPrompt(stage, c)).orElse(null);
            if (typed == null) {
                onError.accept(new java.util.concurrent.CancellationException("No password given for " + c.getName()));
                return;
            }
            c.setPassword(typed);
            promptedPasswords.add(c.getId());
        }
        sessions.withSession(c, s -> {
            promptedPasswords.remove(c.getId()); // it worked: kept in memory for this run
            onReady.accept(s);
        }, ex -> {
            // a wrong password typed in the prompt is not kept: the next try asks again
            if (promptedPasswords.remove(c.getId())) c.setPassword(null);
            onError.accept(ex);
        });
    }

    @Override
    public Optional<DatabaseSession> openSession(ConnectionConfig c) {
        return sessions.get(c.getId());
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
            completer.invalidate(sessions.get(idOf(lazy.getValue())).orElse(null));
            lazy.reset();
            lazy.setExpanded(false);
            lazy.setExpanded(true);
        }
    }

    // ---- tables ----------------------------------------------------------

    /** Runs with the table selected in the tree and its open session. */
    private void withSelectedTable(java.util.function.BiConsumer<DatabaseSession, TableRef> action) {
        NavNode node = selectedNode();
        if (node == null || node.kind() != NavNode.Kind.TABLE) return;
        sessions.get(node.connection().getId()).ifPresent(session -> action.accept(session, node.table()));
    }

    /** What export / import need from the window: the status line, script tabs, open table tabs. */
    private final TableTransfer.Host transferHost = new TableTransfer.Host() {
        @Override
        public void status(String text) {
            status.setText(text);
        }

        @Override
        public void openScript(Path file, ConnectionConfig connection) {
            try {
                openScriptTab(new ScriptTab(file.getFileName().toString(), file, ScriptTab.read(file), connections, connection,
                        MainWindow.this, completer, scriptBindings));
            } catch (IOException e) {
                error("Could not open " + file, e);
            }
        }

        @Override
        public void imported(DatabaseSession session, TableRef table) {
            String key = TableTab.key(session, table);
            for (Tab t : tabs.getTabs()) {
                if (t instanceof TableTab tt && tt.key().equals(key)) tt.reload();
            }
        }
    };

    private void openSelectedTable() {
        NavNode node = selectedNode();
        if (node == null || node.kind() != NavNode.Kind.TABLE) return;
        sessions.get(node.connection().getId()).ifPresent(session -> openTable(session, node.table()));
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
        TableTab tab = new TableTab(session, table, this, completer, transferHost);
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    // ---- scripts ---------------------------------------------------------

    /** A new empty script for the given connection, or with no connection chosen yet (null). */
    private void newScript(ConnectionConfig c) {
        newScript(c, "");
    }

    @Override
    public void newScript(ConnectionConfig c, String content) {
        List<String> open = tabs.getTabs().stream()
                .filter(t -> t instanceof ScriptTab).map(t -> ((ScriptTab) t).displayName()).toList();
        // "Untitled-n" until saved; the Save dialog suggests the file name for the connection
        int n = 1;
        while (open.contains("Untitled-" + n)) n++;
        String name = "Untitled-" + n;
        openScriptTab(new ScriptTab(name, null, content, connections, c, this, completer, scriptBindings));
    }

    private void openScript() {
        openScripts(InAppDialogs.showAndWait(new OpenScriptDialog(stage, connections, scriptBindings.all(),
                scriptDir(), selectedConnection().orElse(null))).orElse(List.of()));
    }

    /** The most recently saved script linked to a connection that still exists on disk, if any. */
    private Optional<Path> lastScriptFor(ConnectionConfig c) {
        Path last = null;
        long lastModified = Long.MIN_VALUE;
        for (Map.Entry<Path, String> e : scriptBindings.all().entrySet()) {
            if (!e.getValue().equals(c.getId())) continue;
            try {
                long modified = java.nio.file.Files.getLastModifiedTime(e.getKey()).toMillis();
                if (modified > lastModified) {
                    last = e.getKey();
                    lastModified = modified;
                }
            } catch (IOException ignored) {
                // moved or deleted outside the app
            }
        }
        return Optional.ofNullable(last);
    }

    /** Opens script files in tabs, or switches to a tab where one is already open. */
    private void openScripts(List<Path> files) {
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

    /**
     * Opens .sql files dropped anywhere on the window. Filters, so a drop on the editor opens the file
     * instead of pasting its path; other drags (text) are left alone.
     */
    private void installScriptDrop() {
        root.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (!sqlFiles(e.getDragboard()).isEmpty()) {
                e.acceptTransferModes(TransferMode.COPY);
                e.consume();
            }
        });
        root.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            List<Path> files = sqlFiles(e.getDragboard());
            if (files.isEmpty()) return;
            e.setDropCompleted(true);
            e.consume();
            // after the drop returns, so the file manager is not held by an error dialog
            Platform.runLater(() -> openScripts(files));
        });
    }

    private static List<Path> sqlFiles(Dragboard board) {
        if (!board.hasFiles()) return List.of();
        return board.getFiles().stream()
                .filter(f -> f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".sql"))
                .map(f -> f.toPath().toAbsolutePath().normalize())
                .toList();
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

    private Optional<ScriptTab> currentScript() {
        return tabs.getSelectionModel().getSelectedItem() instanceof ScriptTab st ? Optional.of(st) : Optional.empty();
    }

    /** Asks about every unsaved script and compare. Returns false if the user cancelled. */
    /** Asks every tab with unsaved work (scripts, cell edits, compares); false if one was cancelled. */
    private boolean confirmCloseTabs() {
        for (Tab t : List.copyOf(tabs.getTabs())) {
            if (t instanceof UnsavedWork w && !w.confirmClose()) return false;
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
                    setGraphic(DbIcons.connection(node.connection().getType(), sessions.isOpen(idOf(node))));
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
