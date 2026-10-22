package com.dbadmin.ui;

import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The navigator with a search box on top: while text is typed, the tree is replaced by a tree of
 * the same shape (connection, schema, Tables / Views / Materialized Views) holding only the tables
 * and views whose name matches. Only open connections are searched (typing never connects), and
 * system schemas are left out. FX thread only.
 */
class TableSearch extends VBox {

    private static final int MAX_HITS = 200;

    /** What a node of the result tree shows. */
    private record ResultNode(Kind kind, String label, DatabaseSession session, TableRef table) {
        enum Kind { CONNECTION, SCHEMA, FOLDER, TABLE }
    }

    private final TextField field = new TextField();
    private final TreeItem<ResultNode> resultRoot = new TreeItem<>();
    private final TreeView<ResultNode> results = new TreeView<>(resultRoot);
    private final Label placeholder = new Label();
    private final Supplier<Collection<DatabaseSession>> openSessions;
    private final BiConsumer<DatabaseSession, TableRef> open;
    /** Tables per session, loaded on the first search; removed by invalidate(). */
    private final Map<DatabaseSession, List<TableRef>> index = new WeakHashMap<>();
    private final Set<DatabaseSession> loading = new HashSet<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(150));

    TableSearch(Node tree, Supplier<Collection<DatabaseSession>> openSessions, BiConsumer<DatabaseSession, TableRef> open) {
        this.openSessions = openSessions;
        this.open = open;

        field.setPromptText("Search tables / views…");
        field.getStyleClass().add("nav-search");
        field.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        debounce.setOnFinished(e -> refresh());
        field.setOnKeyPressed(e -> {
            TreeItem<ResultNode> first = firstTable(resultRoot);
            if (e.getCode() == KeyCode.ESCAPE) {
                field.clear();
            } else if (e.getCode() == KeyCode.DOWN && first != null) {
                results.requestFocus();
                results.getSelectionModel().select(first);
            } else if (e.getCode() == KeyCode.ENTER && first != null) {
                if (!isTable(results.getSelectionModel().getSelectedItem())) results.getSelectionModel().select(first);
                openSelected();
            } else {
                return;
            }
            e.consume();
        });

        Button clear = new Button("✕");
        clear.getStyleClass().add("nav-search-clear");
        clear.setFocusTraversable(false);
        clear.setOnAction(e -> {
            field.clear();
            field.requestFocus();
        });
        clear.visibleProperty().bind(field.textProperty().isNotEmpty());
        StackPane searchBox = new StackPane(field, clear);
        StackPane.setAlignment(clear, Pos.CENTER_RIGHT);
        StackPane.setMargin(clear, new Insets(0, 4, 0, 0));

        results.setShowRoot(false);
        results.setCellFactory(tv -> new ResultCell());
        results.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) openSelected();
        });
        results.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) openSelected();
            else if (e.getCode() == KeyCode.ESCAPE) field.requestFocus();
        });
        placeholder.setWrapText(true);
        placeholder.getStyleClass().add("hint-small");
        placeholder.setPadding(new Insets(8));
        placeholder.setMouseTransparent(true);
        StackPane.setAlignment(placeholder, Pos.TOP_LEFT);
        StackPane resultPane = new StackPane(results, placeholder);
        resultPane.setVisible(false);
        tree.visibleProperty().bind(resultPane.visibleProperty().not());

        StackPane body = new StackPane(tree, resultPane);
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox.setMargin(searchBox, new Insets(4));
        getChildren().addAll(searchBox, body);
    }

    /** Forgets a session's tables (after DDL, refresh or disconnect); reloaded on the next search. */
    void invalidate(DatabaseSession session) {
        if (session == null || index.remove(session) == null) return;
        if (isSearching()) refresh();
    }

    private boolean isSearching() {
        return results.getParent().isVisible();
    }

    private void openSelected() {
        TreeItem<ResultNode> item = results.getSelectionModel().getSelectedItem();
        if (isTable(item)) open.accept(item.getValue().session(), item.getValue().table());
    }

    private static boolean isTable(TreeItem<ResultNode> item) {
        return item != null && item.getValue() != null && item.getValue().kind() == ResultNode.Kind.TABLE;
    }

    /** Re-runs the current search, e.g. after a connection was opened. */
    void refresh() {
        String query = field.getText() == null ? "" : field.getText().trim().toLowerCase(Locale.ROOT);
        results.getParent().setVisible(!query.isEmpty());
        if (query.isEmpty()) return;

        Collection<DatabaseSession> sessions = openSessions.get();
        for (DatabaseSession s : sessions) {
            if (!index.containsKey(s) && loading.add(s)) load(s);
        }
        List<DatabaseSession> ordered = new ArrayList<>(sessions);
        ordered.sort(Comparator.comparing(s -> s.getConfig().getName(), String.CASE_INSENSITIVE_ORDER));
        Comparator<TableRef> best = Comparator.comparingInt((TableRef t) -> rank(t, query))
                .thenComparingInt(t -> t.name().length())
                .thenComparing(TableRef::name, String.CASE_INSENSITIVE_ORDER);

        List<TreeItem<ResultNode>> connections = new ArrayList<>();
        int hits = 0;
        for (DatabaseSession s : ordered) {
            // schema -> its matching tables, schemas in name order
            Map<String, List<TableRef>> bySchema = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (TableRef t : index.getOrDefault(s, List.of())) {
                if (hits < MAX_HITS && rank(t, query) < NO_MATCH) {
                    bySchema.computeIfAbsent(schemaOf(t), k -> new ArrayList<>()).add(t);
                    hits++;
                }
            }
            if (bySchema.isEmpty()) continue;
            TreeItem<ResultNode> connection = node(ResultNode.Kind.CONNECTION, s.getConfig().getName(), s, null);
            bySchema.forEach((schema, tables) -> {
                tables.sort(best);
                TreeItem<ResultNode> schemaItem = node(ResultNode.Kind.SCHEMA, schema, s, null);
                addFolder(schemaItem, s, "Tables", tables, t -> !t.isView());
                addFolder(schemaItem, s, "Views", tables, t -> t.isView() && !t.isMaterializedView());
                addFolder(schemaItem, s, "Materialized Views", tables, TableRef::isMaterializedView);
                connection.getChildren().add(schemaItem);
            });
            connections.add(connection);
        }
        resultRoot.getChildren().setAll(connections);

        boolean empty = connections.isEmpty();
        placeholder.setVisible(empty);
        placeholder.setText(sessions.isEmpty() ? "Open a connection to search its tables and views."
                : !loading.isEmpty() ? "Searching…" : "No tables or views match.");
    }

    private static void addFolder(TreeItem<ResultNode> schema, DatabaseSession s, String name,
                                  List<TableRef> tables, Predicate<TableRef> belongs) {
        List<TableRef> inFolder = tables.stream().filter(belongs).toList();
        if (inFolder.isEmpty()) return;
        TreeItem<ResultNode> folder = node(ResultNode.Kind.FOLDER, name + " (" + inFolder.size() + ")", s, null);
        for (TableRef t : inFolder) folder.getChildren().add(node(ResultNode.Kind.TABLE, t.name(), s, t));
        schema.getChildren().add(folder);
    }

    private static TreeItem<ResultNode> node(ResultNode.Kind kind, String label, DatabaseSession s, TableRef t) {
        TreeItem<ResultNode> item = new TreeItem<>(new ResultNode(kind, label, s, t));
        item.setExpanded(true);
        return item;
    }

    private static String schemaOf(TableRef t) {
        return t.schema() != null ? t.schema() : t.catalog() != null ? t.catalog() : "main";
    }

    /** The first table in tree order, for Enter / Down in the search box. */
    private static TreeItem<ResultNode> firstTable(TreeItem<ResultNode> item) {
        for (TreeItem<ResultNode> child : item.getChildren()) {
            if (isTable(child)) return child;
            TreeItem<ResultNode> found = firstTable(child);
            if (found != null) return found;
        }
        return null;
    }

    static final int NO_MATCH = 4;

    /**
     * 0 = name starts with the query, 1 = its initials do ("mpv" for m_product_variant, 2+ characters),
     * 2 = name contains it, 3 = schema.name contains it, {@link #NO_MATCH}.
     */
    static int rank(TableRef t, String query) {
        String name = t.name().toLowerCase(Locale.ROOT);
        if (name.startsWith(query)) return 0;
        if (query.length() >= 2 && SqlCompleter.initials(t.name()).startsWith(query)) return 1;
        if (name.contains(query)) return 2;
        return t.displayName().toLowerCase(Locale.ROOT).contains(query) ? 3 : NO_MATCH;
    }

    private void load(DatabaseSession session) {
        Async.run(session, () -> {
            List<TableRef> tables = new ArrayList<>();
            for (Namespace ns : session.listNamespaces()) {
                if (ns.isSystem(session.getConfig().getType())) continue;
                tables.addAll(session.listTables(ns));
            }
            return tables;
        }, tables -> {
            loading.remove(session);
            index.put(session, tables);
            refresh();
        }, ex -> {
            loading.remove(session);
            index.put(session, List.of()); // not retried until invalidated
            refresh();
        });
    }

    /** Looks like the main navigator's cells: same icons and colors. */
    private static class ResultCell extends TreeCell<ResultNode> {
        private final Label icon = new Label();

        @Override
        protected void updateItem(ResultNode node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(node.label());
            if (node.kind() == ResultNode.Kind.CONNECTION) {
                setGraphic(DbIcons.connection(node.session().getConfig().getType(), true));
                return;
            }
            icon.getStyleClass().setAll("label", "nav-icon");
            switch (node.kind()) {
                case SCHEMA -> icon.setText("◆");
                case FOLDER -> icon.setText("▤");
                default -> {
                    TableRef t = node.table();
                    icon.setText(t.isMaterializedView() ? "◩" : t.isView() ? "◫" : "▦");
                    icon.getStyleClass().add(t.isMaterializedView() ? "matview" : t.isView() ? "view" : "table");
                }
            }
            setGraphic(icon);
        }
    }
}
