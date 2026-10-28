package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.ConnectionStore;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.ScriptBindings;
import com.dbadmin.service.ScriptFolder;
import com.dbadmin.service.SessionManager;
import com.dbadmin.service.compare.CompareReportFiles;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
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
import java.nio.file.Files;
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
    /** Showing and closing tabs, their right-click menu, asking about unsaved work before the window closes. */
    private final TabStrip tabStrip = new TabStrip(tabs);
    private final com.dbadmin.service.OpenTabsStore openTabsStore = new com.dbadmin.service.OpenTabsStore();
    /** Closed tabs Ctrl+Shift+T opens again, for 10 minutes. */
    private final ClosedTabs closedTabs = new ClosedTabs(10 * 60_000, System::currentTimeMillis);
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
            if (!confirmExit()) e.consume();
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

        navigator = new TableSearch(tree, sessions::all, this::openTable);
        navigator.setMenus((session, t) -> withRefresh(tableMenu(session, t), session),
                session -> withRefresh(connectionMenu(session.getConfig()), session));
        completer.setOnInvalidate(navigator::invalidate); // DDL and refresh also reload the search
        mainSplit = new SplitPane(navigator, center);
        mainSplit.setDividerPositions(0.25);
        SplitPane.setResizableWithParent(navigator, false);
        SplitPane split = mainSplit;

        root.setTop(buildTopBar());
        // a filter, so it works wherever the focus is (editor, grid, tree)
        KeyCombination closeTab = KeyCombination.keyCombination("Shortcut+W");
        KeyCombination reopenTab = KeyCombination.keyCombination("Shortcut+Shift+T");
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (closeTab.match(e)) {
                e.consume();
                tabStrip.close(tabs.getSelectionModel().getSelectedItem());
            } else if (reopenTab.match(e)) {
                e.consume();
                reopenClosedTab();
            }
        });
        tabStrip.setReopen(this::reopenClosedTab, () -> closedTabs.lastTitle().orElse(null));
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> {
            while (change.next()) {
                for (Tab t : change.getRemoved()) rememberClosed(t);
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
        tabStrip.show(tab);
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
        if (file != null) openCompareReport(file);
    }

    private void openCompareReport(Path file) {
        try {
            Object report = CompareReportFiles.open(file);
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
            if (confirmExit()) stage.close();
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
        MenuItem scanData = new MenuItem("Scan Data…");
        scanData.setOnAction(e -> {
            if (license.allows("Scan Data")) showTab(new ScanTab(connections, this, selectedConnection().orElse(null)));
        });
        Menu tools = new Menu("Tools", null, compareSchema, compareData, new SeparatorMenuItem(), openReport,
                new SeparatorMenuItem(), scanData);
        return new MenuBar(connection, script, tools);
    }

    /**
     * The tree's right-click menu, made each time from the same pieces as the search results' menu (see
     * {@link #tableMenu}, {@link #connectionMenu}), so both offer the same actions.
     */
    private ContextMenu buildContextMenu() {
        ContextMenu menu = new ContextMenu();
        menu.setOnShowing(e -> {
            NavNode n = selectedNode();
            MenuItem refresh = new MenuItem("Refresh (F5)");
            refresh.setOnAction(ev -> refreshSelected());
            List<MenuItem> items = new ArrayList<>();
            if (n == null || n.kind() == NavNode.Kind.MESSAGE) {
                refresh.setDisable(true);
            } else if (n.kind() == NavNode.Kind.TABLE) {
                sessions.get(n.connection().getId()).ifPresent(s -> items.addAll(tableMenu(s, n.table())));
            } else if (n.kind() == NavNode.Kind.CONNECTION) {
                items.addAll(connectionMenu(n.connection()));
            } else {
                items.addAll(scriptMenu(n.connection()));
            }
            if (!items.isEmpty()) items.add(new SeparatorMenuItem());
            items.add(refresh);
            menu.getItems().setAll(items);
        });
        menu.getItems().add(new MenuItem("…")); // a menu with no items is never shown; filled when showing
        return menu;
    }

    /** A table's right-click items, in the tree and in the search results: open, data, the table's own, scripts. */
    private List<MenuItem> tableMenu(DatabaseSession session, TableRef t) {
        List<MenuItem> items = new ArrayList<>();
        MenuItem open = new MenuItem("Open");
        open.setOnAction(e -> openTable(session, t));
        MenuItem exportData = new MenuItem("Export Data…");
        exportData.setOnAction(e -> TableTransfer.export(stage, session, t, null, transferHost));
        items.addAll(List.of(open, exportData));
        if (!t.isView()) {
            MenuItem importData = new MenuItem("Import Data…");
            importData.setOnAction(e -> TableTransfer.importData(stage, session, t, transferHost));
            importData.setDisable(session.getConfig().isReadOnly());
            items.add(importData);
        }
        TableActions.Items actions = TableActions.items(() -> stage, () -> new TableActions.Target(session, t), tableActionsHost);
        actions.showFor(session.getConfig(), t);
        if (actions.anyShown()) {
            items.add(new SeparatorMenuItem());
            actions.all().stream().filter(MenuItem::isVisible).forEach(items::add);
        }
        items.add(new SeparatorMenuItem());
        items.addAll(scriptMenu(session.getConfig()));
        return items;
    }

    /** Search results: the items and Refresh, which reads the connection's tables again (the search too). */
    private List<MenuItem> withRefresh(List<MenuItem> items, DatabaseSession session) {
        MenuItem refresh = new MenuItem("Refresh");
        refresh.setOnAction(e -> {
            completer.invalidate(session); // also drops the session's tables from the search index
            navigator.refresh();
        });
        List<MenuItem> all = new ArrayList<>(items);
        all.add(new SeparatorMenuItem());
        all.add(refresh);
        return all;
    }

    /** A connection's right-click items, in the tree and in the search results. */
    private List<MenuItem> connectionMenu(ConnectionConfig c) {
        List<MenuItem> items = new ArrayList<>(scriptMenu(c));
        MenuItem edit = new MenuItem("Edit Connection");
        edit.setOnAction(e -> editConnection(c));
        MenuItem delete = new MenuItem("Delete Connection");
        delete.setOnAction(e -> deleteConnection(c));
        items.addAll(List.of(new SeparatorMenuItem(), edit, delete));
        if (sessions.isOpen(c.getId())) {
            MenuItem disconnect = new MenuItem("Disconnect");
            disconnect.setOnAction(e -> disconnect(c));
            MenuItem reconnect = new MenuItem("Reconnect");
            reconnect.setOnAction(e -> reconnect(c));
            items.addAll(List.of(disconnect, reconnect));
        }
        return items;
    }

    /** New Script, Open Script…, Open Last Script for a connection. */
    private List<MenuItem> scriptMenu(ConnectionConfig c) {
        MenuItem newScript = new MenuItem("New Script");
        newScript.setOnAction(e -> newScript(c));
        MenuItem openScript = new MenuItem("Open Script…");
        openScript.setOnAction(e -> openScript(c));
        Optional<Path> last = lastScriptFor(c);
        MenuItem openLast = new MenuItem(last.map(p -> "Open Last Script (" + p.getFileName() + ")").orElse("Open Last Script"));
        openLast.setDisable(last.isEmpty());
        openLast.setOnAction(e -> last.ifPresent(p -> openScripts(List.of(p))));
        return List.of(newScript, openScript, openLast);
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
        if (selected != null) editConnection(selected.getValue().connection());
    }

    private void editConnection(ConnectionConfig edited) {
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
        if (item != null) deleteConnection(item.getValue().connection());
    }

    private void deleteConnection(ConnectionConfig c) {
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
        treeRoot.getChildren().removeIf(i -> i.getValue().connection() == c);
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
                    // only some databases have materialized views; elsewhere the folder would always be empty
                    if (c.getType().hasMaterializedViews() || !materialized.isEmpty()) {
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
    /** Callers that asked for a connection while its password prompt was open: they go on once it is answered. */
    private final Map<String, List<Runnable>> waitingForPrompt = new java.util.HashMap<>();

    /**
     * Opens (or reuses) the connection's session. A connection with "Save password" off is asked for its password
     * first, unless it is already open or the password is in memory (typed earlier in this run).
     */
    @Override
    public void withSession(ConnectionConfig c, Consumer<DatabaseSession> onReady, Consumer<Throwable> onError) {
        boolean ask = !c.getType().isFileBased() && !c.isSavePassword() && c.getPassword() == null && sessions.get(c.getId()).isEmpty();
        if (ask) {
            // the prompt is already open for another caller (e.g. both sides of a compare): wait for its answer
            List<Runnable> waiting = waitingForPrompt.get(c.getId());
            if (waiting != null) {
                waiting.add(() -> {
                    if (c.getPassword() == null) {
                        onError.accept(new java.util.concurrent.CancellationException("No password given for " + c.getName()));
                    } else {
                        withSession(c, onReady, onError);
                    }
                });
                return;
            }
            waitingForPrompt.put(c.getId(), new ArrayList<>());
            String typed = InAppDialogs.showAndWait(new PasswordPrompt(stage, c)).orElse(null);
            List<Runnable> next = waitingForPrompt.remove(c.getId());
            if (typed == null) {
                onError.accept(new java.util.concurrent.CancellationException("No password given for " + c.getName()));
                next.forEach(Runnable::run);
                return;
            }
            c.setPassword(typed);
            promptedPasswords.add(c.getId());
            next.forEach(Runnable::run); // they share the session that opens now (SessionManager)
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
        public void running(Runnable cancel) {
            footer.showCancel(cancel);
        }

        @Override
        public void stopped() {
            footer.hideCancel();
        }

        @Override
        public void imported(DatabaseSession session, TableRef table) {
            String key = TableTab.key(session, table);
            for (Tab t : tabs.getTabs()) {
                if (t instanceof TableTab tt && tt.key().equals(key)) tt.reload();
            }
        }
    };

    /** After a whole-table change: what is open and listed shows it. */
    private final TableActions.Host tableActionsHost = (session, table, change) -> {
        String key = TableTab.key(session, table);
        completer.invalidate(session);
        switch (change) {
            case EMPTIED -> tabs.getTabs().forEach(t -> {
                if (t instanceof TableTab tt && tt.key().equals(key)) tt.reload();
            });
            case DROPPED, RENAMED -> tabs.getTabs().removeIf(t -> t instanceof TableTab tt && tt.key().equals(key)); // the table is gone
            case BACKED_UP -> { }
        }
        if (change != TableActions.Change.EMPTIED) refreshTablesAround(session.getConfig(), table);
        status.setText(switch (change) {
            case EMPTIED -> "Emptied " + table.displayName();
            case DROPPED -> "Dropped " + table.displayName();
            case RENAMED -> "Renamed " + table.displayName();
            case BACKED_UP -> "Backed up " + table.displayName();
        });
    };

    /** Lists again the tables of the navigator folder that holds {@code table}, if it is shown. */
    private void refreshTablesAround(ConnectionConfig c, TableRef table) {
        java.util.ArrayDeque<TreeItem<NavNode>> todo = new java.util.ArrayDeque<>(tree.getRoot() == null ? List.of() : List.of(tree.getRoot()));
        while (!todo.isEmpty()) {
            TreeItem<NavNode> item = todo.pop();
            NavNode n = item.getValue();
            if (n != null && n.kind() == NavNode.Kind.TABLE && table.equals(n.table()) && n.connection().getId().equals(c.getId())) {
                TreeItem<NavNode> up = item.getParent();
                while (up != null && !(up instanceof LazyItem)) up = up.getParent();
                if (up instanceof LazyItem lazy) {
                    lazy.reset();
                    lazy.setExpanded(false);
                    lazy.setExpanded(true);
                }
                return;
            }
            todo.addAll(item.getChildren());
        }
    }

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
        TableTab tab = new TableTab(session, table, this, completer, transferHost, tableActionsHost);
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
        openScript(selectedConnection().orElse(null));
    }

    private void openScript(ConnectionConfig c) {
        openScripts(InAppDialogs.showAndWait(new OpenScriptDialog(stage, connections, scriptBindings.all(),
                scriptDir(), c)).orElse(List.of()));
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

    /**
     * Before the window closes: "Close DBArbiter?" when that is set (Cancel the default), then every tab with unsaved
     * work; then the open tabs are written down for the next start (or forgotten when that setting is off).
     */
    private boolean confirmExit() {
        if (Themes.settings().isConfirmExit()) {
            ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.OK_DONE);
            Alert ask = new Alert(Alert.AlertType.CONFIRMATION, "Close DBArbiter?", close, ButtonType.CANCEL);
            ask.initOwner(stage);
            ask.setHeaderText(null);
            ((Button) ask.getDialogPane().lookupButton(close)).setDefaultButton(false);
            ((Button) ask.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
            if (InAppDialogs.showAndWait(ask).orElse(ButtonType.CANCEL) != close) return false;
        }
        if (!tabStrip.confirmCloseAll()) return false;
        openTabsStore.save(Themes.settings().isReopenTabs() ? openTabs() : List.of());
        return true;
    }

    /** The tabs to open at the next start: saved scripts, tables, compares saved to a file; in their order. */
    private List<com.dbadmin.service.OpenTabsStore.OpenTab> openTabs() {
        List<com.dbadmin.service.OpenTabsStore.OpenTab> list = new ArrayList<>();
        Tab front = tabs.getSelectionModel().getSelectedItem();
        for (Tab t : tabs.getTabs()) {
            boolean selected = t == front;
            if (t instanceof ScriptTab st && st.file() != null) {
                list.add(new com.dbadmin.service.OpenTabsStore.OpenTab("SCRIPT", st.file().toString(), st.connection() == null ? null : st.connection().getId(),
                        null, null, null, null, selected));
            } else if (t instanceof TableTab tt) {
                TableRef r = tt.table();
                list.add(new com.dbadmin.service.OpenTabsStore.OpenTab("TABLE", null, tt.connection().getId(), r.catalog(), r.schema(), r.name(), r.type(), selected));
            } else if (t instanceof DataCompareTab dc && dc.file() != null) {
                list.add(new com.dbadmin.service.OpenTabsStore.OpenTab("COMPARE", dc.file().toString(), null, null, null, null, null, selected));
            } else if (t instanceof SchemaCompareTab sc && sc.file() != null) {
                list.add(new com.dbadmin.service.OpenTabsStore.OpenTab("COMPARE", sc.file().toString(), null, null, null, null, null, selected));
            }
        }
        return list;
    }

    /**
     * At startup, when set: opens the tabs open last time. Files that are gone and deleted connections are skipped;
     * a table opens once its connection has connected; compares only when licensed.
     */
    public void reopenTabs() {
        if (!Themes.settings().isReopenTabs()) return;
        Tab[] front = new Tab[1];
        for (com.dbadmin.service.OpenTabsStore.OpenTab o : openTabsStore.load()) {
            switch (o.kind()) {
                case "SCRIPT" -> {
                    Path file = Path.of(o.file());
                    if (!Files.isRegularFile(file)) continue;
                    openScripts(List.of(file));
                    if (tabs.getSelectionModel().getSelectedItem() instanceof ScriptTab st) {
                        st.useConnection(connectionById(o.connectionId()));
                        if (o.selected()) front[0] = st;
                    }
                }
                case "TABLE" -> {
                    ConnectionConfig c = connectionById(o.connectionId());
                    if (c == null) continue;
                    TableRef table = new TableRef(o.catalog(), o.schema(), o.table(), o.type());
                    boolean wasFront = o.selected();
                    // it opens once connected, after the others: it only stays in front if it was
                    withSession(c, s -> {
                        Tab before = tabs.getSelectionModel().getSelectedItem();
                        openTable(s, table);
                        if (!wasFront && before != null) tabs.getSelectionModel().select(before);
                    }, ex -> status.setText("Could not open " + table.name() + ": " + ex.getMessage()));
                }
                case "COMPARE" -> {
                    Path file = Path.of(o.file());
                    if (!Files.isRegularFile(file) || !license.allowsQuietly()) continue;
                    openCompareReport(file);
                    if (o.selected()) front[0] = tabs.getSelectionModel().getSelectedItem();
                }
                default -> { }
            }
        }
        if (front[0] != null) tabs.getSelectionModel().select(front[0]);
    }

    /** Remembers how to open a closed tab again (Ctrl+Shift+T): scripts, tables, and compares saved to a file. */
    private void rememberClosed(Tab t) {
        if (t instanceof ScriptTab st) {
            String text = st.text();
            Path file = st.file();
            boolean unsaved = st.isDirty();
            String name = st.untitledName();
            String connectionId = st.connection() == null ? null : st.connection().getId();
            closedTabs.add(st.displayName(), () -> {
                ConnectionConfig c = connectionById(connectionId);
                if (file != null && !unsaved) {
                    openScripts(List.of(file));
                    return;
                }
                ScriptTab tab = new ScriptTab(file != null ? file.getFileName().toString() : name, file, text, connections, c, this,
                        completer, scriptBindings);
                openScriptTab(tab);
                if (unsaved) tab.markUnsaved();
            });
        } else if (t instanceof TableTab tt) {
            String connectionId = tt.connection().getId();
            TableRef table = tt.table();
            closedTabs.add(table.name(), () -> {
                ConnectionConfig c = connectionById(connectionId);
                if (c == null) {
                    status.setText("The connection of " + table.name() + " was deleted");
                    return;
                }
                withSession(c, s -> openTable(s, table), ex -> error("Could not open " + table.name(), ex));
            });
        } else if (t instanceof DataCompareTab dc && dc.file() != null) {
            Path file = dc.file();
            closedTabs.add(file.getFileName().toString(), () -> openCompareReport(file));
        } else if (t instanceof SchemaCompareTab sc && sc.file() != null) {
            Path file = sc.file();
            closedTabs.add(file.getFileName().toString(), () -> openCompareReport(file));
        }
    }

    private void reopenClosedTab() {
        if (!closedTabs.reopenLast()) status.setText("No closed tab to reopen (they are kept for 10 minutes)");
    }

    private ConnectionConfig connectionById(String id) {
        return id == null ? null : connections.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);
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
     * Opens .sql and .dbcompare files dropped anywhere on the window. Filters, so a drop on the editor opens
     * the file instead of pasting its path; other drags (text) are left alone.
     */
    private void installScriptDrop() {
        root.addEventFilter(DragEvent.DRAG_OVER, e -> {
            Dragboard board = e.getDragboard();
            if (!droppedFiles(board, ".sql").isEmpty() || !droppedFiles(board, CompareReportFiles.EXTENSION).isEmpty()) {
                e.acceptTransferModes(TransferMode.COPY);
                e.consume();
            }
        });
        root.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            List<Path> scripts = droppedFiles(e.getDragboard(), ".sql");
            List<Path> reports = droppedFiles(e.getDragboard(), CompareReportFiles.EXTENSION);
            if (scripts.isEmpty() && reports.isEmpty()) return;
            e.setDropCompleted(true);
            e.consume();
            // after the drop returns, so the file manager is not held by an error dialog
            Platform.runLater(() -> {
                openScripts(scripts);
                if (!reports.isEmpty() && license.allows("Compare Table and Compare Data")) {
                    reports.forEach(this::openCompareReport);
                }
            });
        });
    }

    private static List<Path> droppedFiles(Dragboard board, String extension) {
        if (!board.hasFiles()) return List.of();
        return board.getFiles().stream()
                .filter(f -> f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(extension))
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
