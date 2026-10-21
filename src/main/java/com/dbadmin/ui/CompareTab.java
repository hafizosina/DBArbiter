package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.StatementResult;
import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.SqlSplitter;
import com.dbadmin.service.compare.DataCompare;
import com.dbadmin.service.compare.SchemaCompare.Status;
import com.dbadmin.service.compare.SchemaCompare.TableDiff;
import com.dbadmin.service.compare.SchemaCompare;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
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
import java.util.function.Supplier;

/**
 * Tools → Compare Table / Compare Data: compares two databases, e.g. UAT (source) against Prod
 * (target) before a release. A table compare lists structural differences of all or some tables of
 * two schemas; a data compare lists row differences of one table. Everything here only reads.
 */
class CompareTab extends Tab {

    enum Mode { SCHEMA, DATA }


    /** Rows listed in a data-compare report at most (the counts cover everything). */
    private static final int MAX_LISTED_ROWS = 50_000;

    private final ObservableList<ConnectionConfig> connections;
    private final SessionProvider sessions;
    /** Opens another compare tab (the schema compare's "Compare data of this table"). */
    private final Consumer<CompareTab> openTab;

    // schema page
    private final Side schemaSource;
    private final Side schemaTarget;
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

    // data page
    private final Side dataSource;
    private final Side dataTarget;
    /** Columns of the compared table: which identify a row (Key) and which are compared. */
    private final ObservableList<ColumnChoice> columnChoices = FXCollections.observableArrayList();
    private final TableView<ColumnChoice> columnChooser = new TableView<>(columnChoices);
    private final javafx.scene.control.TextField where = new javafx.scene.control.TextField();
    private final javafx.scene.control.ToggleButton columnsToggle = new javafx.scene.control.ToggleButton("Columns");
    private final Spinner<Integer> rowLimit = new Spinner<>(100, 1_000_000, 10_000, 1_000);
    private final Label dataStatus = new Label();
    /** The last data-compare report: each row with its status, filtered by the status check boxes. */
    private final List<Map.Entry<DataCompare.RowStatus, List<Object>>> reportRows = new ArrayList<>();
    private List<String> reportHeader = List.of();
    private final Map<DataCompare.RowStatus, CheckBox> statusFilters = new java.util.EnumMap<>(DataCompare.RowStatus.class);
    private final FlowPane statusBar = new FlowPane(12, 4);
    private final DataView dataView = new DataView();
    private final DataGrid dataGrid = dataView.grid();

    CompareTab(Mode mode, ObservableList<ConnectionConfig> connections, SessionProvider sessions,
               ConnectionConfig initial, Consumer<CompareTab> openTab) {
        super(mode == Mode.SCHEMA ? "Table Compare" : "Data Compare");
        this.connections = connections;
        this.sessions = sessions;
        this.openTab = openTab;
        schemaSource = new Side("Source", false, null);
        schemaTarget = new Side("Target", false, () -> schemaSource.schemaLabel());
        dataSource = new Side("Source", true, null);
        dataTarget = new Side("Target", true, () -> dataSource.schemaLabel());
        dataTarget.preferredTable = () -> dataSource.table.getValue() == null ? null : dataSource.table.getValue().name();
        followSource(schemaSource, schemaTarget);
        followSource(dataSource, dataTarget);

        setContent(mode == Mode.SCHEMA ? schemaPage() : dataPage());
        if (initial != null) {
            (mode == Mode.SCHEMA ? schemaSource : dataSource).connection.setValue(initial);
        }
    }

    // ---- schema ------------------------------------------------------------

    private Node schemaPage() {
        Button compare = new Button("Compare");
        compare.getStyleClass().add("default-look");
        compare.setOnAction(e -> runSchemaCompare());
        Button swap = swapButton(schemaSource, schemaTarget);
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
        CompareTab data = new CompareTab(Mode.DATA, connections, sessions, null, openTab);
        data.setText("Data Compare: " + d.name());
        data.dataSource.select(schemaSource.connection.getValue(), schemaSource.schemaLabel(), d.name());
        data.dataTarget.select(schemaTarget.connection.getValue(), schemaTarget.schemaLabel(), d.name());
        openTab.accept(data);
    }

    // ---- data --------------------------------------------------------------

    /** One column of the compared table. Columns missing on one side can be neither key nor compared. */
    static final class ColumnChoice {
        final String name;
        final boolean inSource;
        final boolean inTarget;
        final BooleanProperty key = new SimpleBooleanProperty();
        final BooleanProperty compare = new SimpleBooleanProperty();
        /** Shown in the report only: its differences do not change a row's status. */
        final BooleanProperty show = new SimpleBooleanProperty();

        ColumnChoice(String name, boolean inSource, boolean inTarget) {
            this.name = name;
            this.inSource = inSource;
            this.inTarget = inTarget;
        }

        boolean onBothSides() {
            return inSource && inTarget;
        }
    }

    private Node dataPage() {
        Button compare = new Button("Compare data");
        compare.getStyleClass().add("default-look");
        compare.setOnAction(e -> runDataCompare());
        Button swap = swapButton(dataSource, dataTarget);
        dataSource.table.valueProperty().addListener((o, a, t) -> loadColumns());
        dataTarget.table.valueProperty().addListener((o, a, t) -> loadColumns());
        rowLimit.setEditable(true);
        rowLimit.setPrefWidth(110);
        rowLimit.setTooltip(new Tooltip("Rows read from each side, in key order"));

        FlowPane sides = new FlowPane(10, 6, dataSource, swap, dataTarget);
        sides.setAlignment(Pos.CENTER_LEFT);
        where.setPromptText("optional, e.g.  is_deleted = false and type = 'EMAS'");
        where.setTooltip(new Tooltip("A SQL condition applied to both sides; only matching rows are compared"));
        where.setOnAction(e -> runDataCompare());
        HBox.setHgrow(where, Priority.ALWAYS);
        HBox options = new HBox(8, new Label("Where:"), where, new Label("Rows per side:"), rowLimit, compare,
                columnsToggle);
        options.setAlignment(Pos.CENTER_LEFT);
        dataStatus.setWrapText(true);
        dataStatus.getStyleClass().add("hint-small");
        VBox top = new VBox(6, sides, options, dataStatus);
        top.setPadding(new Insets(8));

        dataGrid.setPlaceholder(new Label("Pick the table on both sides, then Compare data."));
        Label showLabel = new Label("Show:");
        statusBar.getChildren().add(showLabel);
        for (DataCompare.RowStatus status : DataCompare.RowStatus.values()) {
            CheckBox box = new CheckBox(statusLabel(status, "source", "target"));
            box.setMnemonicParsing(false); // keep the "_" in ONLY_IN_SOURCE
            box.setSelected(true);
            // a swatch of the status color: the filter bar doubles as the legend
            javafx.scene.shape.Rectangle swatch = new javafx.scene.shape.Rectangle(10, 10);
            swatch.setArcWidth(3);
            swatch.setArcHeight(3);
            Runnable paint = () -> swatch.setFill(javafx.scene.paint.Color.web(Themes.statusColor(status.name())));
            paint.run();
            Themes.shownSettings().addListener((o, x, y) -> paint.run());
            box.setGraphic(swatch);
            box.selectedProperty().addListener((o, a, b) -> showReport());
            statusFilters.put(status, box);
            statusBar.getChildren().add(box);
        }
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 6, 4, 6));
        statusBar.setVisible(false);
        statusBar.setManaged(false);
        // status cells take their colors from the theme settings
        Themes.shownSettings().addListener((o, a, b) -> dataGrid.refresh());
        BorderPane report = new BorderPane(dataView);
        report.setTop(statusBar);
        Node columns = columnPane();
        SplitPane split = new SplitPane(columns, report);
        split.setDividerPositions(0.31);
        // hide the column picker to give the report the whole width; showing it again restores its size
        columnsToggle.setSelected(true);
        columnsToggle.setTooltip(new Tooltip("Show or hide the column picker"));
        double[] divider = {0.31};
        columnsToggle.selectedProperty().addListener((o, a, show) -> {
            if (show && !split.getItems().contains(columns)) {
                split.getItems().add(0, columns);
                split.setDividerPositions(divider[0]);
            } else if (!show && split.getItems().contains(columns)) {
                divider[0] = split.getDividerPositions()[0];
                split.getItems().remove(columns);
            }
        });
        BorderPane page = new BorderPane(split);
        page.setTop(top);
        return page;
    }

    private Node columnPane() {
        TableColumn<ColumnChoice, String> name = new TableColumn<>("Column");
        name.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name
                + (c.getValue().onBothSides() ? "" : c.getValue().inSource ? "  (only in source)" : "  (only in target)")));
        name.setPrefWidth(125);
        TableColumn<ColumnChoice, Boolean> key = new TableColumn<>("Key");
        key.setCellValueFactory(c -> c.getValue().key);
        key.setCellFactory(col -> new ChoiceCheckCell());
        key.setPrefWidth(40);
        TableColumn<ColumnChoice, Boolean> compare = new TableColumn<>("Compare");
        compare.setCellValueFactory(c -> c.getValue().compare);
        compare.setCellFactory(col -> new ChoiceCheckCell());
        compare.setPrefWidth(72);
        TableColumn<ColumnChoice, Boolean> show = new TableColumn<>("Show");
        show.setCellValueFactory(c -> c.getValue().show);
        show.setCellFactory(col -> new ChoiceCheckCell());
        show.setPrefWidth(52);
        columnChooser.getColumns().addAll(List.of(name, key, compare, show));
        columnChooser.setEditable(true);
        columnChooser.setPlaceholder(new Label("The table's columns appear here."));

        Button all = new Button("Compare all");
        all.setOnAction(e -> columnChoices.forEach(c -> c.compare.set(c.onBothSides() && !c.key.get())));
        Button showAll = new Button("Show all");
        showAll.setOnAction(e -> columnChoices.forEach(c -> c.show.set(c.onBothSides())));
        Button none = new Button("Clear");
        none.setOnAction(e -> columnChoices.forEach(c -> {
            c.key.set(false);
            c.compare.set(false);
            c.show.set(false);
        }));
        Label hint = new Label("Key: identifies a row (several allowed). Compare: values checked. "
                + "Show: only displayed in the report, never changes a status.");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint-small");
        HBox buttons = new HBox(6, all, showAll, none);
        VBox pane = new VBox(6, hint, columnChooser, buttons);
        pane.setPadding(new Insets(6));
        VBox.setVgrow(columnChooser, Priority.ALWAYS);
        return pane;
    }

    /** A checkbox, disabled for a column that is not on both sides. */
    private static class ChoiceCheckCell extends javafx.scene.control.cell.CheckBoxTableCell<ColumnChoice, Boolean> {
        @Override
        public void updateItem(Boolean value, boolean empty) {
            super.updateItem(value, empty);
            ColumnChoice c = empty || getTableRow() == null ? null : getTableRow().getItem();
            setDisable(c == null || !c.onBothSides());
            setEditable(c != null && c.onBothSides());
        }
    }

    /**
     * Lists the columns of both tables once both are chosen. Defaults: the primary key as key and every
     * other column on both sides compared; choices already made for a column name are kept.
     */
    private void loadColumns() {
        TableRef t1 = dataSource.table.getValue();
        TableRef t2 = dataTarget.table.getValue();
        if (t1 == null || t2 == null || dataSource.session == null || dataTarget.session == null) return;
        DatabaseSession s1 = dataSource.session;
        DatabaseSession s2 = dataTarget.session;
        Map<String, Boolean[]> previous = new java.util.HashMap<>();
        columnChoices.forEach(c -> previous.put(c.name.toLowerCase(), new Boolean[]{c.key.get(), c.compare.get(), c.show.get()}));
        Async.run(s1, () -> new Object[]{s1.columnDefs(t1), s1.primaryKey(t1)}, first -> Async.run(s2, () -> s2.columnDefs(t2), second -> {
            if (dataSource.table.getValue() != t1 || dataTarget.table.getValue() != t2) return;
            @SuppressWarnings("unchecked") List<DatabaseSession.ColumnDef> srcCols = (List<DatabaseSession.ColumnDef>) first[0];
            @SuppressWarnings("unchecked") List<String> pk = (List<String>) first[1];
            java.util.Set<String> tgtNames = new java.util.HashSet<>();
            second.forEach(c -> tgtNames.add(c.name().toLowerCase()));
            java.util.Set<String> srcNames = new java.util.HashSet<>();
            List<ColumnChoice> choices = new ArrayList<>();
            for (DatabaseSession.ColumnDef c : srcCols) {
                srcNames.add(c.name().toLowerCase());
                choices.add(new ColumnChoice(c.name(), true, tgtNames.contains(c.name().toLowerCase())));
            }
            for (DatabaseSession.ColumnDef c : second) {
                if (!srcNames.contains(c.name().toLowerCase())) choices.add(new ColumnChoice(c.name(), false, true));
            }
            boolean keepPrevious = !previous.isEmpty() && choices.stream().anyMatch(c -> previous.containsKey(c.name.toLowerCase()));
            for (ColumnChoice c : choices) {
                Boolean[] before = previous.get(c.name.toLowerCase());
                if (!c.onBothSides()) continue;
                // nothing ticked by default; choices made for the same column names are kept
                if (keepPrevious && before != null) {
                    c.key.set(before[0]);
                    c.compare.set(before[1]);
                    c.show.set(before[2]);
                }
            }
            columnChoices.setAll(choices);
        }, ex -> dataStatus.setText(ex.getMessage())), ex -> dataStatus.setText(ex.getMessage()));
    }

    private void runDataCompare() {
        if (!dataSource.readyWithTable() || !dataTarget.readyWithTable()) {
            dataStatus.setText("Pick a connection, schema and table on both sides.");
            return;
        }
        List<String> keys = columnChoices.stream().filter(c -> c.key.get()).map(c -> c.name).toList();
        List<String> compared = columnChoices.stream().filter(c -> c.compare.get() && !c.key.get()).map(c -> c.name).toList();
        List<String> shownOnly = columnChoices.stream().filter(c -> c.show.get() && !c.key.get() && !c.compare.get())
                .map(c -> c.name).toList();
        if (keys.isEmpty()) {
            dataStatus.setText("Tick Key for the column(s) that identify a row (e.g. id).");
            return;
        }
        String filter = where.getText() == null ? "" : where.getText().trim();
        if (!filter.isEmpty() && !isPlainCondition(filter)) {
            dataStatus.setText("The Where filter must be one read-only condition (no ';', INSERT, UPDATE, DELETE, ...).");
            return;
        }
        int limit = rowLimit.getValue();
        String src = dataSource.label();
        String dst = dataTarget.label();
        DatabaseSession s1 = dataSource.session;
        DatabaseSession s2 = dataTarget.session;
        TableRef t1 = dataSource.table.getValue();
        TableRef t2 = dataTarget.table.getValue();
        dataStatus.setText("Reading " + t1.name() + " from " + src + " and " + dst + "…");
        dataGrid.getItems().clear();

        StatementResult[] read = new StatementResult[2];
        Consumer<Throwable> failed = ex -> dataStatus.setText("Could not compare: " + ex.getMessage());
        Runnable whenBoth = () -> {
            if (read[0] == null || read[1] == null) return;
            Async.run(() -> DataCompare.compare(read[0].data(), read[1].data(), keys, compared, shownOnly, MAX_LISTED_ROWS),
                    r -> showDataResult(r, src, dst, keys, filter, read[0].truncated() || read[1].truncated(), limit),
                    failed);
        };
        Async.run(s1, () -> s1.fetchOrdered(t1, keys, filter, limit), r -> {
            read[0] = r;
            whenBoth.run();
        }, failed);
        Async.run(s2, () -> s2.fetchOrdered(t2, keys, filter, limit), r -> {
            read[1] = r;
            whenBoth.run();
        }, failed);
    }

    /** The filter has to stay a condition of one SELECT: one statement, nothing that writes. */
    private static boolean isPlainCondition(String condition) {
        String sql = "select * from t where (" + condition + ")";
        return SqlSplitter.split(sql, null).size() == 1 && SqlSplitter.isReadOnlyQuery(sql);
    }

    private void showDataResult(DataCompare.Result r, String src, String dst, List<String> keys, String filter,
                                boolean truncated, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append(src).append(": ").append(r.sourceRows()).append(" rows · ").append(dst).append(": ").append(r.targetRows())
                .append(" rows  →  ").append(r.same()).append(" identical · ").append(r.changed()).append(" changed · ")
                .append(r.onlySource()).append(" only in ").append(src).append(" · ")
                .append(r.onlyTarget()).append(" only in ").append(dst);
        if (r.duplicateKeys() > 0) {
            sb.append("\n⚠ ").append(r.duplicateKeys()).append(" key value(s) match more than one row: ")
                    .append(String.join(", ", keys)).append(" does not identify rows uniquely.");
        }
        if (!filter.isEmpty()) sb.append("\nWhere: ").append(filter);
        sb.append("\nCompared columns: ").append(r.comparedColumns().isEmpty() ? "none (only which keys exist)"
                : String.join(", ", r.comparedColumns()));
        if (truncated) {
            sb.append("\n⚠ Only the first ").append(limit).append(" rows of each side (in key order) were compared; ")
                    .append("rows near that limit may wrongly show as only on one side. Raise \"Rows per side\" to compare more.");
        }
        if (r.rows().size() >= MAX_LISTED_ROWS) sb.append("\nListing the first ").append(r.rows().size()).append(" rows.");
        dataStatus.setText(sb.toString());

        // one column per key / compared / shown column; a changed cell shows "old → new"
        reportHeader = new ArrayList<>();
        reportHeader.add("Status");
        reportHeader.addAll(r.shownColumns());
        reportHeader.add("Note");
        reportRows.clear();
        Map<DataCompare.RowStatus, Integer> counts = new java.util.EnumMap<>(DataCompare.RowStatus.class);
        for (DataCompare.RowDiff d : r.rows()) {
            counts.merge(d.status(), 1, Integer::sum);
            List<Object> row = new ArrayList<>();
            row.add(new StatusValue(d.status().name(), statusLabel(d.status(), src, dst)));
            for (int c = 0; c < r.shownColumns().size(); c++) {
                Object a = d.sourceValues() == null ? null : d.sourceValues().get(c);
                Object b = d.targetValues() == null ? null : d.targetValues().get(c);
                boolean isCompared = r.comparedColumns().contains(r.shownColumns().get(c));
                row.add(new ComparedValue(a, b, d.sourceValues() != null, d.targetValues() != null, src, dst, isCompared));
            }
            row.add(d.status() == DataCompare.RowStatus.CHANGED || d.status() == DataCompare.RowStatus.MATCH ? ""
                    : d.details().replace("in source", "in " + src).replace("in target", "in " + dst));
            reportRows.add(Map.entry(d.status(), row));
        }
        statusFilters.forEach((status, box) -> box.setText(statusLabel(status, src, dst) + " (" + counts.getOrDefault(status, 0) + ")"));
        statusBar.setVisible(true);
        statusBar.setManaged(true);
        showReport();
    }

    private static String statusLabel(DataCompare.RowStatus status, String src, String dst) {
        return switch (status) {
            case MATCH -> "MATCH";
            case CHANGED -> "CHANGED";
            case ONLY_SOURCE -> "ONLY_IN_SOURCE";
            case ONLY_TARGET -> "ONLY_IN_TARGET";
            case DUPLICATE_KEY -> "DUPLICATE_KEY";
        };
    }

    /** The report rows whose status is ticked in the filter bar. */
    private void showReport() {
        List<List<Object>> rows = reportRows.stream()
                .filter(e -> statusFilters.get(e.getKey()).isSelected())
                .map(Map.Entry::getValue)
                .toList();
        dataGrid.setPlaceholder(new Label(reportRows.isEmpty() ? "No rows to compare." : "No rows with the ticked statuses."));
        dataGrid.show(new TableData(reportHeader, rows));
    }

    // ---- shared ------------------------------------------------------------

    /**
     * The target follows the source: a schema or table chosen on the source is chosen on the target
     * too, if it has one by that name and the user has not picked one there yet.
     */
    private static void followSource(Side source, Side target) {
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

    private Button swapButton(Side a, Side b) {
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

    /** One side of a comparison: connection, schema and (for data) table. */
    private class Side extends HBox {
        final ComboBox<ConnectionConfig> connection = new ComboBox<>();
        final ComboBox<Namespace> schema = new ComboBox<>();
        /** Picks a table by typing part of its name. */
        final SearchBox<TableRef> table = new SearchBox<>(TableRef::name, "search table…");
        final Supplier<String> preferredSchema;
        Supplier<String> preferredTable = () -> null;
        DatabaseSession session;
        private String pendingSchema;
        private String pendingTable;


        Side(String title, boolean withTable, Supplier<String> preferredSchema) {
            super(6);
            this.preferredSchema = preferredSchema;
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
            Side other = this == schemaSource ? schemaTarget : this == schemaTarget ? schemaSource
                    : this == dataSource ? dataTarget : dataSource;
            boolean sameConnection = other.connection.getValue() != null
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
                }, ex -> schemaStatus.setText(c.getName() + ": " + ex.getMessage()));
            }, ex -> {
                schemaStatus.setText("Could not connect to " + c.getName() + ": " + ex.getMessage());
                dataStatus.setText(schemaStatus.getText());
            });
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
            }, ex -> dataStatus.setText(ex.getMessage()));
        }
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
