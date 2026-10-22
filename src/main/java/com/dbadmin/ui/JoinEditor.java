package com.dbadmin.ui;

import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.compare.KeyJoins;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data Compare's joins: the compared (main) table's alias and one row per joined table, "[left] join table alias on
 * condition". Joined columns then appear in the column picker as alias.column. Shown only while there is a join.
 * Tables are picked from the source schema; {@code changed} runs when the joined columns may have changed.
 */
class JoinEditor extends VBox {

    /** A join whose table and alias are set: its columns can be listed. */
    record Ready(String alias, TableRef table) {
    }

    private final CompareSide source;
    private final Runnable changed;
    private final TextField mainAlias = new TextField();
    /** The alias last filled in automatically, replaced when the main table changes (unless the user typed one). */
    private String autoAlias = "";
    private final VBox rows = new VBox(4);

    JoinEditor(CompareSide source, Runnable changed) {
        super(4);
        this.source = source;
        this.changed = changed;
        mainAlias.setPrefColumnCount(5);
        mainAlias.setPromptText("alias");
        mainAlias.setTooltip(new Tooltip("The compared table's alias, used in the joins' ON conditions"));
        source.table.valueProperty().addListener((o, a, t) -> {
            if (t == null || !mainAlias.getText().equals(autoAlias)) return;
            autoAlias = SqlCompleter.aliasFor(t.name(), Set.of());
            mainAlias.setText(autoAlias);
        });
        Label title = new Label("Joins");
        title.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(6, title, new Label("main alias"), mainAlias);
        header.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(header, rows);
        BooleanBinding hasJoins = Bindings.isNotEmpty(rows.getChildren());
        visibleProperty().bind(hasJoins);
        managedProperty().bind(hasJoins);
        rows.getChildren().addListener((ListChangeListener<Node>) c -> changed.run()); // joined columns come and go
    }

    /** "+ Join": a new, empty join row. */
    void add() {
        rows.getChildren().add(new Row());
    }

    boolean isEmpty() {
        return rows.getChildren().isEmpty();
    }

    String mainAlias() {
        return mainAlias.getText().strip();
    }

    /** Every join as written (a table not picked yet has a null table). */
    List<KeyJoins.Join> joins() {
        return rowList().stream().map(Row::join).toList();
    }

    /** Every join's table in the source schema, in order (null where none is picked yet). */
    List<TableRef> tables() {
        return rowList().stream().map(r -> r.table.getValue()).toList();
    }

    /** A reopened compare's joins are waiting for the source schema's tables to be listed. */
    boolean pending() {
        return rowList().stream().anyMatch(r -> r.pendingTable != null);
    }

    /**
     * The source schema's tables are listed (again): each row offers them, a reopened compare's rows pick theirs, and
     * the joins whose columns can be listed are returned.
     */
    List<Ready> tablesListed() {
        List<Ready> ready = new ArrayList<>();
        for (Row r : rowList()) {
            r.table.setItems(source.table.getItems());
            r.applyPending();
            if (r.ready()) ready.add(new Ready(r.alias.getText().strip(), r.table.getValue()));
        }
        return ready;
    }

    /** A reopened compare's alias and joins; their tables are picked once the source schema's tables are listed. */
    void restore(String alias, List<KeyJoins.Join> joins) {
        rows.getChildren().clear();
        if (joins == null || joins.isEmpty()) return;
        mainAlias.setText(alias == null ? "" : alias);
        for (KeyJoins.Join j : joins) {
            Row row = new Row();
            row.type.setValue(j.left() ? "left join" : "join");
            row.alias.setText(j.alias());
            row.on.setText(j.on());
            row.pendingTable = j.table();
            rows.getChildren().add(row);
        }
    }

    private List<Row> rowList() {
        return rows.getChildren().stream().map(n -> (Row) n).toList();
    }

    private Set<String> takenAliases() {
        Set<String> taken = new HashSet<>();
        taken.add(mainAlias());
        rowList().forEach(r -> taken.add(r.alias.getText().strip()));
        return taken;
    }

    /**
     * Fills an empty ON condition from a foreign key: of the main table or an earlier join to the picked table
     * (e.g. mpi.etalase_id → m_product_etalase.id gives "mpe.id = mpi.etalase_id"). Only reads metadata.
     */
    private void suggestOn(Row row) {
        DatabaseSession s = source.session;
        TableRef main = source.table.getValue();
        if (s == null || main == null || !row.on.getText().isBlank()) return;
        List<Map.Entry<String, TableRef>> from = new ArrayList<>();
        from.add(Map.entry(mainAlias(), main));
        for (Row r : rowList()) {
            if (r == row) break;
            if (r.table.getValue() != null) from.add(Map.entry(r.alias.getText().strip(), r.table.getValue()));
        }
        String wanted = row.table.getValue().name();
        Async.run(s, () -> {
            for (Map.Entry<String, TableRef> f : from) {
                for (List<Object> fk : s.listForeignKeys(f.getValue()).rows()) {
                    String target = String.valueOf(fk.get(2));
                    String column = String.valueOf(fk.get(1));
                    if (target.substring(target.lastIndexOf('.') + 1).equalsIgnoreCase(wanted) && !column.contains(",")) {
                        return "%s." + fk.get(3) + " = " + f.getKey() + "." + column;
                    }
                }
            }
            return null;
        }, on -> {
            if (on != null && row.on.getText().isBlank()) row.on.setText(on.formatted(row.alias.getText().strip()));
        }, ex -> { });
    }

    /** One join: [left] join table alias on condition. */
    private final class Row extends HBox {
        final ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("join", "left join"));
        final SearchBox<TableRef> table = new SearchBox<>(TableRef::name, "join table…");
        final TextField alias = new TextField();
        final TextField on = new TextField();
        /** A table to pick by name once the list is there (a reopened report). */
        String pendingTable;
        /** Set while the table is picked by the code: the caller lists the columns. */
        private boolean quiet;

        Row() {
            super(6);
            setAlignment(Pos.CENTER_LEFT);
            type.setValue("left join"); // keeps main rows whose joined row is missing (a NULL id)
            table.setItems(source.table.getItems());
            alias.setPrefColumnCount(5);
            alias.setPromptText("alias");
            on.setPromptText("e.g. mpe.id = " + (mainAlias.getText().isBlank() ? "mpi" : mainAlias.getText()) + ".etalase_id");
            HBox.setHgrow(on, Priority.ALWAYS);
            Button remove = new Button("✕");
            remove.setTooltip(new Tooltip("Remove this join"));
            remove.setOnAction(e -> rows.getChildren().remove(this)); // the list's listener lists the columns again
            table.valueProperty().addListener((o, a, t) -> {
                if (t == null) return;
                if (alias.getText().isBlank()) alias.setText(SqlCompleter.aliasFor(t.name(), takenAliases()));
                suggestOn(this);
                if (!quiet) changed.run();
            });
            alias.setOnAction(e -> changed.run());
            alias.focusedProperty().addListener((o, was, focused) -> {
                if (!focused) changed.run();
            });
            getChildren().addAll(type, table, alias, new Label("on"), on, remove);
        }

        /** Picks the pending table if the list has it. */
        void applyPending() {
            if (pendingTable == null) return;
            String name = pendingTable;
            table.getItems().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst().ifPresent(t -> {
                pendingTable = null;
                quiet = true;
                table.setValue(t);
                quiet = false;
            });
        }

        KeyJoins.Join join() {
            return new KeyJoins.Join("left join".equals(type.getValue()), table.getValue() == null ? null : table.getValue().name(),
                    alias.getText().strip(), on.getText().strip());
        }

        /** Picked a table and has a usable alias: its columns can be listed. */
        boolean ready() {
            return table.getValue() != null && alias.getText().strip().matches("[A-Za-z_][A-Za-z0-9_]*");
        }
    }
}
