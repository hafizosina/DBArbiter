package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.compare.SchemaCompare.Status;
import com.dbadmin.service.compare.SchemaCompare.TableDiff;
import com.dbadmin.service.compare.SchemaCompare;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tools → Compare Table: lists the structural differences of all or some tables of two schemas, e.g.
 * UAT (source) against Prod (target) before a release. Only reads.
 */
class SchemaCompareTab extends Tab {

    private final ObservableList<ConnectionConfig> connections;
    private final SessionProvider sessions;
    /** Opens another tab (the "Compare data of this table" button's). */
    private final Consumer<Tab> openTab;

    private final CompareSide schemaSource;
    private final CompareSide schemaTarget;
    private final Label schemaStatus = new Label();
    private final ObservableList<TableDiff> diffs = FXCollections.observableArrayList();
    private final FilteredList<TableDiff> shownDiffs = new FilteredList<>(diffs);
    private final ListView<TableDiff> diffList = new ListView<>(shownDiffs);
    private final DataView detailView = new DataView();
    private final DataGrid detailGrid = detailView.grid();
    private final Label detailTitle = new Label();
    private final CheckBox showIdentical = new CheckBox("Show identical");
    private final Button compareDataButton = new Button("Compare data of this table");
    private final Button sqlDiffButton = new Button("SQL diff");
    /** Tables picked for a Table Compare (lower case); empty = all. */
    private Set<String> pickedTables = Set.of();
    private final Button tablesButton = new Button("All tables");
    private String sourceName = "Source";
    private String targetName = "Target";

    SchemaCompareTab(ObservableList<ConnectionConfig> connections, SessionProvider sessions, ConnectionConfig initial,
                     Consumer<Tab> openTab) {
        super("Table Compare");
        this.connections = connections;
        this.sessions = sessions;
        this.openTab = openTab;
        schemaSource = new CompareSide("Source", false, connections, sessions, schemaStatus::setText);
        schemaTarget = new CompareSide("Target", false, connections, sessions, schemaStatus::setText);
        CompareSide.pair(schemaSource, schemaTarget);
        setContent(schemaPage());
        if (initial != null) schemaSource.connection.setValue(initial);
    }

    private Node schemaPage() {
        Button compare = new Button("Compare");
        compare.getStyleClass().add("default-look");
        compare.setOnAction(e -> runSchemaCompare());
        Button swap = CompareSide.swapButton(schemaSource, schemaTarget);
        showIdentical.selectedProperty().addListener((o, a, b) -> applyFilter());
        applyFilter();

        diffList.setCellFactory(lv -> new DiffCell());
        diffList.setPlaceholder(new Label("Pick a source and a target, then Compare."));
        diffList.getSelectionModel().selectedItemProperty().addListener((o, a, d) -> showDetails(d));
        detailGrid.setPlaceholder(new Label("Select a table to see its differences."));

        detailTitle.getStyleClass().add("form-title");
        compareDataButton.setDisable(true);
        compareDataButton.setOnAction(e -> openDataCompare(diffList.getSelectionModel().getSelectedItem()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        sqlDiffButton.setVisible(false);
        sqlDiffButton.setManaged(false);
        HBox detailBar = new HBox(8, detailTitle, spacer, sqlDiffButton, compareDataButton);
        detailBar.setAlignment(Pos.CENTER_LEFT);
        detailBar.setPadding(new Insets(6));
        BorderPane details = new BorderPane(detailView); // the value panel shows long definitions in full
        details.setTop(detailBar);

        SplitPane split = new SplitPane(diffList, details);
        split.setDividerPositions(0.24);

        schemaStatus.getStyleClass().add("hint-small");
        tablesButton.setOnAction(e -> pickTables());
        tablesButton.setTooltip(new Tooltip("Compare all tables, or only the ones you pick"));
        // a different schema means a different list of tables
        schemaSource.schema.valueProperty().addListener((o, a, b) -> resetPickedTables());
        schemaTarget.schema.valueProperty().addListener((o, a, b) -> resetPickedTables());
        FlowPane form = new FlowPane(10, 6, schemaSource, swap, schemaTarget, tablesButton, compare, showIdentical);
        form.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(6, form, schemaStatus);
        top.setPadding(new Insets(8));
        BorderPane page = new BorderPane(split);
        page.setTop(top);
        return page;
    }

    private void resetPickedTables() {
        pickedTables = Set.of();
        tablesButton.setText("All tables");
    }

    /** Lets the user pick tables from both sides' lists; none picked means all. */
    private void pickTables() {
        if (!schemaSource.ready() || !schemaTarget.ready()) {
            schemaStatus.setText("Pick a connection and schema on both sides first.");
            return;
        }
        DatabaseSession src = schemaSource.session;
        DatabaseSession dst = schemaTarget.session;
        Namespace srcNs = schemaSource.schema.getValue();
        Namespace dstNs = schemaTarget.schema.getValue();
        Async.run(src, () -> src.listTables(srcNs).stream().map(TableRef::name).toList(), srcTables ->
                Async.run(dst, () -> dst.listTables(dstNs).stream().map(TableRef::name).toList(), dstTables ->
                        InAppDialogs.showAndWait(new TablePickerDialog(getTabPane().getScene().getWindow(), srcTables, dstTables,
                                schemaSource.label(), schemaTarget.label(), pickedTables)).ifPresent(picked -> {
                            pickedTables = picked;
                            tablesButton.setText(picked.isEmpty() ? "All tables"
                                    : picked.size() == 1 ? "1 table: " + picked.iterator().next() : picked.size() + " tables");
                        }), ex -> schemaStatus.setText(ex.getMessage())), ex -> schemaStatus.setText(ex.getMessage()));
    }

    private void runSchemaCompare() {
        if (!schemaSource.ready() || !schemaTarget.ready()) {
            schemaStatus.setText("Pick a connection and schema on both sides.");
            return;
        }
        sourceName = schemaSource.label();
        targetName = schemaTarget.label();
        diffs.clear();
        showDetails(null);
        DatabaseSession src = schemaSource.session;
        DatabaseSession dst = schemaTarget.session;
        Namespace srcNs = schemaSource.schema.getValue();
        Namespace dstNs = schemaTarget.schema.getValue();

        // read both sides at the same time (they usually are different servers)
        int[] progress = {0, 0, 0, 0};
        Runnable showProgress = () -> schemaStatus.setText("Reading " + sourceName + ": " + progress[0] + "/" + progress[1]
                + " · " + targetName + ": " + progress[2] + "/" + progress[3] + " tables…");
        showProgress.run();
        @SuppressWarnings("unchecked")
        List<SchemaCompare.TableSnapshot>[] snapshots = new List[2];
        Consumer<Throwable> failed = ex -> schemaStatus.setText("Could not read the schema: " + ex.getMessage());
        Runnable whenBoth = () -> {
            if (snapshots[0] == null || snapshots[1] == null) return;
            diffs.setAll(SchemaCompare.compare(snapshots[0], snapshots[1]));
            schemaStatus.setText(summary());
            diffList.getSelectionModel().selectFirst();
        };
        Set<String> only = pickedTables;
        Async.run(src, () -> SchemaCompare.snapshot(src, srcNs, only, (done, total) -> Platform.runLater(() -> {
            progress[0] = done;
            progress[1] = total;
            showProgress.run();
        })), snap -> {
            snapshots[0] = snap;
            whenBoth.run();
        }, failed);
        Async.run(dst, () -> SchemaCompare.snapshot(dst, dstNs, only, (done, total) -> Platform.runLater(() -> {
            progress[2] = done;
            progress[3] = total;
            showProgress.run();
        })), snap -> {
            snapshots[1] = snap;
            whenBoth.run();
        }, failed);
    }

    private String summary() {
        long same = diffs.stream().filter(d -> d.status() == Status.SAME).count();
        long changed = diffs.stream().filter(d -> d.status() == Status.CHANGED).count();
        long onlySource = diffs.stream().filter(d -> d.status() == Status.ONLY_SOURCE).count();
        long onlyTarget = diffs.stream().filter(d -> d.status() == Status.ONLY_TARGET).count();
        return same + " identical · " + changed + " changed · " + onlySource + " only in " + sourceName
                + " · " + onlyTarget + " only in " + targetName;
    }

    private void applyFilter() {
        shownDiffs.setPredicate(d -> showIdentical.isSelected() || d.status() != Status.SAME);
    }

    private void showDetails(TableDiff d) {
        compareDataButton.setDisable(d == null || d.status() == Status.ONLY_SOURCE || d.status() == Status.ONLY_TARGET
                || d.kind().contains("VIEW"));
        if (d == null) {
            detailTitle.setText("");
            detailGrid.show(new TableData(List.of("Category", "Name", "Status", sourceName, targetName), List.of()));
            return;
        }
        detailTitle.setText(d.name() + "  ·  " + statusText(d.status()));
        List<List<Object>> rows = new ArrayList<>();
        if (d.items().isEmpty()) {
            rows.add(List.of(d.kind().toLowerCase(), d.name(), statusText(d.status()),
                    d.status() == Status.ONLY_SOURCE ? "exists" : "", d.status() == Status.ONLY_TARGET ? "exists" : ""));
        }
        SchemaCompare.Item definition = null;
        for (SchemaCompare.Item i : d.items()) {
            if (i.category().equals("Definition")) definition = i;
            if (i.status() == Status.SAME && !showIdentical.isSelected() && !i.name().contains(" = ")) continue;
            if (i.category().equals("Definition")) {
                // long SQL: click the row to see both sides in full in the value panel
                rows.add(List.of(i.category(), i.name(), statusText(i.status()),
                        new ComparedValue(i.source(), i.target(), true, true, sourceName, targetName, true),
                        new ComparedValue(i.target(), i.source(), true, true, targetName, sourceName, true)));
            } else {
                rows.add(List.of(i.category(), i.name(), statusText(i.status()), i.source(), i.target()));
            }
        }
        SchemaCompare.Item viewSql = definition;
        sqlDiffButton.setVisible(viewSql != null);
        sqlDiffButton.setManaged(viewSql != null);
        sqlDiffButton.setOnAction(e -> InAppDialogs.showAndWait(new SqlDiffDialog(getTabPane().getScene().getWindow(),
                d.name(), viewSql.source(), viewSql.target(), sourceName, targetName)));
        detailGrid.show(new TableData(List.of("Category", "Name", "Status", sourceName, targetName), rows));
        fitDetailColumns();
    }

    /** Fixed widths for Category / Name / Status; the two side columns share the rest, so both are visible. */
    private void fitDetailColumns() {
        var columns = detailGrid.getColumns();
        if (columns.size() != 5) return;
        double[] fixed = {85, 170, 140};
        for (int i = 0; i < 3; i++) columns.get(i).setPrefWidth(fixed[i]);
        for (int i = 3; i < 5; i++) {
            columns.get(i).prefWidthProperty().bind(javafx.beans.binding.Bindings.max(150,
                    detailGrid.widthProperty().subtract(fixed[0] + fixed[1] + fixed[2] + 20).divide(2)));
        }
    }

    private String statusText(Status s) {
        return switch (s) {
            case SAME -> "✔ same";
            case CHANGED -> "✎ changed";
            case ONLY_SOURCE -> "＋ only in " + sourceName;
            case ONLY_TARGET -> "− only in " + targetName;
        };
    }

    /** Opens a Data Compare tab for this table, with both sides already chosen. */
    private void openDataCompare(TableDiff d) {
        if (d == null) return;
        DataCompareTab data = new DataCompareTab(connections, sessions, null);
        data.setText("Data Compare: " + d.name());
        data.select(schemaSource.connection.getValue(), schemaSource.schemaLabel(),
                schemaTarget.connection.getValue(), schemaTarget.schemaLabel(), d.name());
        openTab.accept(data);
    }

    private class DiffCell extends ListCell<TableDiff> {
        private final Label icon = new Label();
        private final Label name = new Label();
        private final Label detail = new Label();
        private final HBox box;

        DiffCell() {
            icon.setMinWidth(18);
            detail.getStyleClass().add("hint-small");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(6, icon, name, spacer, detail);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(TableDiff d, boolean empty) {
            super.updateItem(d, empty);
            setText(null);
            if (empty || d == null) {
                setGraphic(null);
                return;
            }
            Map<Status, String[]> look = Map.of(
                    Status.SAME, new String[]{"✔", "cmp-same"},
                    Status.CHANGED, new String[]{"✎", "cmp-changed"},
                    Status.ONLY_SOURCE, new String[]{"＋", "cmp-only-source"},
                    Status.ONLY_TARGET, new String[]{"−", "cmp-only-target"});
            icon.setText(look.get(d.status())[0]);
            icon.getStyleClass().setAll("label", "cmp-icon", look.get(d.status())[1]);
            name.setText(d.name());
            String kind = d.kind().contains("VIEW") ? "view" : "";
            detail.setText(switch (d.status()) {
                case SAME -> kind;
                case CHANGED -> (kind.isEmpty() ? "" : kind + " · ") + d.differences() + " diff";
                case ONLY_SOURCE -> "only in " + sourceName;
                case ONLY_TARGET -> "only in " + targetName;
            });
            setGraphic(box);
        }
    }
}
