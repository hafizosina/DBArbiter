package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.StatementResult;
import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.SqlSplitter;
import com.dbadmin.service.compare.DataCompare;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
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
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tools → Compare Data: lists the row differences of one table in two databases, e.g. UAT (source)
 * against Prod (target). Only reads.
 */
class DataCompareTab extends Tab {

    /** Rows listed in a data-compare report at most (the counts cover everything). */
    private static final int MAX_LISTED_ROWS = 50_000;

    private final CompareSide dataSource;
    private final CompareSide dataTarget;
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

    DataCompareTab(ObservableList<ConnectionConfig> connections, SessionProvider sessions, ConnectionConfig initial) {
        super("Data Compare");
        dataSource = new CompareSide("Source", true, connections, sessions, dataStatus::setText);
        dataTarget = new CompareSide("Target", true, connections, sessions, dataStatus::setText);
        dataTarget.preferredTable = dataSource::tableName;
        CompareSide.pair(dataSource, dataTarget);
        setContent(dataPage());
        if (initial != null) dataSource.connection.setValue(initial);
    }

    /** Chooses both sides' connection, schema and the table; each list loads first. */
    void select(ConnectionConfig source, String sourceSchema, ConnectionConfig target, String targetSchema, String table) {
        dataSource.select(source, sourceSchema, table);
        dataTarget.select(target, targetSchema, table);
    }

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
        Button swap = CompareSide.swapButton(dataSource, dataTarget);
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
}
