package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One side of a comparison: connection, schema and (for data) table. Used by both compare tabs. */
class CompareSide extends HBox {
    final ComboBox<ConnectionConfig> connection = new ComboBox<>();
    final ComboBox<Namespace> schema = new ComboBox<>();
    /** Picks a table by typing part of its name. */
    final SearchBox<TableRef> table = new SearchBox<>(TableRef::name, "search table…");
    Supplier<String> preferredSchema;
    Supplier<String> preferredTable = () -> null;
    DatabaseSession session;
    private final SessionProvider sessions;
    /** Where errors are shown: the tab's status line. */
    private final Consumer<String> showError;
    /** The other side of the comparison, set by {@link #pair}. */
    private CompareSide other;
    private String pendingSchema;
    private String pendingTable;

    CompareSide(String title, boolean withTable, ObservableList<ConnectionConfig> connections, SessionProvider sessions,
                Consumer<String> showError) {
        super(6);
        this.sessions = sessions;
        this.showError = showError;
        setAlignment(Pos.CENTER_LEFT);
        Label label = new Label(title + ":");
        label.setStyle("-fx-font-weight: bold;");
        connection.setItems(connections);
        connection.setPromptText("connection");
        connection.setCellFactory(lv -> new ConnectionCell());
        connection.setButtonCell(new ConnectionCell());
        schema.setPromptText("schema");

        schema.setCellFactory(lv -> new NameCell<>());
        schema.setButtonCell(new NameCell<>());
        connection.valueProperty().addListener((o, a, c) -> loadSchemas(c));
        schema.valueProperty().addListener((o, a, ns) -> {
            if (withTable) loadTables(ns);
        });
        getChildren().addAll(label, connection, schema);
        if (withTable) getChildren().add(table);
    }

    /**
     * Makes the two sides one comparison. The target follows the source: a schema or table chosen on
     * the source is chosen on the target too, if it has one by that name and the user has not picked
     * one there yet.
     */
    static void pair(CompareSide source, CompareSide target) {
        source.other = target;
        target.other = source;
        target.preferredSchema = source::schemaLabel;
        source.schema.valueProperty().addListener((o, a, ns) -> {
            if (ns == null || target.schema.getValue() != null && target.table.getValue() != null) return;
            target.schema.getItems().stream().filter(t -> t.label().equalsIgnoreCase(ns.label())).findFirst()
                    .ifPresent(target.schema::setValue);
        });
        source.table.valueProperty().addListener((o, a, t) -> {
            if (t == null || target.table.getValue() != null) return;
            target.table.getItems().stream().filter(x -> x.name().equalsIgnoreCase(t.name())).findFirst()
                    .ifPresentOrElse(target.table::setValue, () -> target.pendingTable = t.name());
        });
    }

    static Button swapButton(CompareSide a, CompareSide b) {
        Button swap = new Button("⇄");
        swap.setTooltip(new Tooltip("Swap source and target"));
        swap.setOnAction(e -> {
            ConnectionConfig ac = a.connection.getValue();
            String as = a.schemaLabel();
            String at = a.tableName();
            a.select(b.connection.getValue(), b.schemaLabel(), b.tableName());
            b.select(ac, as, at);
        });
        return swap;
    }

    boolean ready() {
        return session != null && schema.getValue() != null;
    }

    boolean readyWithTable() {
        return ready() && table.getValue() != null;
    }

    String schemaLabel() {
        return schema.getValue() == null ? null : schema.getValue().label();
    }

    String tableName() {
        return table.getValue() == null ? null : table.getValue().name();
    }

    /** "02. AntamUat" or, when both sides are the same connection, "02. AntamUat/brankas_mobile". */
    String label() {
        String name = connection.getValue() == null ? "?" : connection.getValue().getName();
        boolean sameConnection = other != null && other.connection.getValue() != null
                && other.connection.getValue().getId().equals(connection.getValue().getId());
        return sameConnection ? name + "/" + schemaLabel() : name;
    }

    /** Selects a connection, schema and table, waiting for each list to load. */
    void select(ConnectionConfig c, String schemaLabel, String tableName) {
        pendingSchema = schemaLabel;
        pendingTable = tableName;
        if (c != null && c.equals(connection.getValue())) {
            applySchema();
        } else {
            connection.setValue(c);
        }
    }

    private void loadSchemas(ConnectionConfig c) {
        session = null;
        schema.getItems().clear();
        table.setItems(List.of());
        if (c == null) return;
        sessions.withSession(c, s -> {
            session = s;
            Async.run(s, s::listNamespaces, all -> {
                if (connection.getValue() != c) return;
                schema.getItems().setAll(all.stream().filter(ns -> !ns.isSystem(c.getType())).toList());
                applySchema();
            }, ex -> showError.accept(c.getName() + ": " + ex.getMessage()));
        }, ex -> showError.accept("Could not connect to " + c.getName() + ": " + ex.getMessage()));
    }

    private void applySchema() {
        String wanted = pendingSchema != null ? pendingSchema
                : preferredSchema != null && preferredSchema.get() != null ? preferredSchema.get() : null;
        pendingSchema = null;
        Namespace pick = schema.getItems().stream().filter(ns -> ns.label().equalsIgnoreCase(String.valueOf(wanted)))
                .findFirst()
                .orElse(schema.getItems().stream().filter(ns -> ns.label().equals("public")).findFirst()
                        .orElse(schema.getItems().isEmpty() ? null : schema.getItems().get(0)));
        if (pick != null && pick.equals(schema.getValue())) {
            loadTables(pick); // same schema: still apply a pending table
        } else {
            schema.setValue(pick);
        }
    }

    private void loadTables(Namespace ns) {
        if (ns == null || session == null) return;
        DatabaseSession s = session;
        Async.run(s, () -> s.listTables(ns), tables -> {
            if (schema.getValue() != ns) return;
            List<TableRef> onlyTables = tables.stream().filter(t -> !t.isView()).toList();
            table.setItems(onlyTables);
            String wanted = pendingTable != null ? pendingTable : preferredTable.get();
            pendingTable = null;
            onlyTables.stream().filter(t -> t.name().equalsIgnoreCase(String.valueOf(wanted))).findFirst()
                    .ifPresent(table::setValue);
        }, ex -> showError.accept(ex.getMessage()));
    }

    private static class ConnectionCell extends ListCell<ConnectionConfig> {
        @Override
        protected void updateItem(ConnectionConfig c, boolean empty) {
            super.updateItem(c, empty);
            setText(empty || c == null ? null : c.getName());
            setGraphic(empty || c == null ? null : DbIcons.connection(c.getType(), false));
        }
    }

    /** Shows schemas and tables by name. */
    private static class NameCell<T> extends ListCell<T> {
        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null
                    : item instanceof Namespace ns ? ns.label() : item instanceof TableRef t ? t.name() : item.toString());
        }
    }
}
