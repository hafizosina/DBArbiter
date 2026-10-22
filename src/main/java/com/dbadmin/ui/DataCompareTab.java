package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.StatementResult;
import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.SqlSplitter;
import com.dbadmin.service.compare.CompareHtml;
import com.dbadmin.service.compare.CompareReportFiles;
import com.dbadmin.service.compare.DataCompare;
import com.dbadmin.service.compare.DataCompareReport;
import com.dbadmin.service.compare.KeyJoins;
import com.dbadmin.service.compare.Verdict;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tools → Compare Data: lists the row differences of one table in two databases, e.g. UAT (source)
 * against Prod (target), with a verdict per row (insert, update, delete or skip). Only reads. The report
 * can be saved, reopened later and exported as HTML.
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
    /** Shows the advanced options (key from other tables); while off, no joins are used. */
    private final javafx.scene.control.ToggleButton advanced = new javafx.scene.control.ToggleButton("Advanced");
    private final Spinner<Integer> rowLimit = new Spinner<>(100, 1_000_000, 10_000, 1_000);
    private final Label dataStatus = new Label();
    /** The last data-compare report: each row with its status, filtered by the status check boxes. */
    private final List<Map.Entry<DataCompare.RowStatus, List<Object>>> reportRows = new ArrayList<>();
    private List<String> reportHeader = List.of();
    private final Map<DataCompare.RowStatus, CheckBox> statusFilters = new java.util.EnumMap<>(DataCompare.RowStatus.class);
    private final FlowPane statusBar = new FlowPane(12, 4);
    private final DataView dataView = new DataView();
    private final DataGrid dataGrid = dataView.grid();
    /** The report shown now (a compare just run, or one opened from a file); null before the first. */
    private DataCompareReport report;
    private Path reportFile;
    /** Verdicts changed since the report was last saved. */
    private boolean unsaved;
    private final MenuButton verdictForShown = new MenuButton("Verdict for shown rows");
    private final Label verdictSummary = new Label();
    private final Label fileStatus = new Label();
    /** Key from other tables: the main table's alias, and one {@link JoinRow} per join. */
    private final javafx.scene.control.TextField mainAlias = new javafx.scene.control.TextField();
    /** The alias last filled in automatically, replaced when the main table changes (unless the user typed one). */
    private String autoAlias = "";
    private final VBox joinRows = new VBox(4);

    DataCompareTab(ObservableList<ConnectionConfig> connections, SessionProvider sessions, ConnectionConfig initial) {
        super("Data Compare");
        dataSource = new CompareSide("Source", true, connections, sessions, dataStatus::setText);
        dataTarget = new CompareSide("Target", true, connections, sessions, dataStatus::setText);
        dataTarget.preferredTable = dataSource::tableName;
        CompareSide.pair(dataSource, dataTarget);
        setContent(dataPage());
        if (initial != null) dataSource.connection.setValue(initial);
        setOnCloseRequest(e -> {
            if (!confirmClose()) e.consume();
        });
    }

    /** Shows a report opened from a file; a new compare can still be run from the form above. */
    void showSaved(DataCompareReport saved, Path file) {
        reportFile = file;
        unsaved = false;
        setText("Data Compare: " + file.getFileName());
        showReport(saved);
    }

    /** Chooses both sides' connection, schema and the table; each list loads first. */
    void select(ConnectionConfig source, String sourceSchema, ConnectionConfig target, String targetSchema, String table) {
        dataSource.select(source, sourceSchema, table);
        dataTarget.select(target, targetSchema, table);
    }

    /**
     * One column of the compared table, or of a joined table ("mpt.code": a key or shown, never compared).
     * Columns missing on one side can be neither key nor compared.
     */
    static final class ColumnChoice {
        final String name;
        final boolean inSource;
        final boolean inTarget;
        final boolean joined;
        final BooleanProperty key = new SimpleBooleanProperty();
        final BooleanProperty compare = new SimpleBooleanProperty();
        /** Shown in the report only: its differences do not change a row's status. */
        final BooleanProperty show = new SimpleBooleanProperty();

        ColumnChoice(String name, boolean inSource, boolean inTarget) {
            this.name = name;
            this.inSource = inSource;
            this.inTarget = inTarget;
            this.joined = KeyJoins.isJoinedColumn(name);
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
        mainAlias.setPrefColumnCount(5);
        mainAlias.setPromptText("alias");
        mainAlias.setTooltip(new Tooltip("The compared table's alias, used in the joins' ON conditions"));
        dataSource.table.valueProperty().addListener((o, a, t) -> {
            if (t == null || !mainAlias.getText().equals(autoAlias)) return;
            autoAlias = SqlCompleter.aliasFor(t.name(), java.util.Set.of());
            mainAlias.setText(autoAlias);
        });
        Button addJoin = new Button("+ Add join");
        addJoin.setTooltip(new Tooltip("Join another table to take the key from it, e.g. a product code when the ids differ"
                + "\nbetween the databases. Only the main table is compared."));
        addJoin.setOnAction(e -> joinRows.getChildren().add(new JoinRow()));
        Label joinHint = new Label("Joined columns appear as alias.column: tick them as Key (or Show).");
        joinHint.getStyleClass().add("hint-small");
        HBox joinHeader = new HBox(8, new Label("Key from other tables:"), new Label("main table alias"), mainAlias, addJoin, joinHint);
        joinHeader.setAlignment(Pos.CENTER_LEFT);
        VBox joins = new VBox(4, joinHeader, joinRows);
        joins.visibleProperty().bind(advanced.selectedProperty());
        joins.managedProperty().bind(advanced.selectedProperty());
        advanced.setTooltip(new Tooltip("Advanced options: take the key from other tables (joins)"));
        advanced.selectedProperty().addListener((o, a, b) -> loadColumns()); // joined columns come and go
        where.setPromptText("optional, e.g.  is_deleted = false and type = 'EMAS'");
        where.setTooltip(new Tooltip("A SQL condition applied to both sides; only matching rows are compared"));
        where.setOnAction(e -> runDataCompare());
        HBox.setHgrow(where, Priority.ALWAYS);
        HBox options = new HBox(8, new Label("Where:"), where, new Label("Rows per side:"), rowLimit, compare,
                columnsToggle, advanced);
        options.setAlignment(Pos.CENTER_LEFT);
        dataStatus.setWrapText(true);
        dataStatus.getStyleClass().add("hint-small");
        VBox top = new VBox(6, sides, joins, options, dataStatus);
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
            box.selectedProperty().addListener((o, a, b) -> refreshGrid());
            statusFilters.put(status, box);
            statusBar.getChildren().add(box);
        }
        verdictForShown.setTooltip(new Tooltip("Sets the verdict of every row shown now (use the status boxes to narrow them down)."
                + "\nRight-click selected rows to set theirs."));
        Button save = new Button("Save report…");
        save.setOnAction(e -> saveReport());
        Button html = new Button("Export HTML…");
        html.setTooltip(new Tooltip("A page with the summary and the differences, to share with people who do not use DBAdmin"));
        html.setOnAction(e -> exportHtml());
        verdictSummary.getStyleClass().add("hint-small");
        fileStatus.getStyleClass().add("hint-small");
        statusBar.getChildren().addAll(new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL),
                verdictForShown, save, html, verdictSummary, fileStatus);
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
        key.setCellFactory(col -> new ChoiceCheckCell(false));
        key.setPrefWidth(40);
        TableColumn<ColumnChoice, Boolean> compare = new TableColumn<>("Compare");
        compare.setCellValueFactory(c -> c.getValue().compare);
        compare.setCellFactory(col -> new ChoiceCheckCell(true));
        compare.setPrefWidth(72);
        TableColumn<ColumnChoice, Boolean> show = new TableColumn<>("Show");
        show.setCellValueFactory(c -> c.getValue().show);
        show.setCellFactory(col -> new ChoiceCheckCell(false));
        show.setPrefWidth(52);
        columnChooser.getColumns().addAll(List.of(name, key, compare, show));
        columnChooser.setEditable(true);
        columnChooser.setPlaceholder(new Label("The table's columns appear here."));

        Button all = new Button("Compare all");
        all.setOnAction(e -> columnChoices.forEach(c -> c.compare.set(c.onBothSides() && !c.key.get() && !c.joined)));
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

    /** A checkbox, disabled for a column that is not on both sides, and in the Compare column for joined columns. */
    private static class ChoiceCheckCell extends javafx.scene.control.cell.CheckBoxTableCell<ColumnChoice, Boolean> {
        private final boolean compareColumn;

        ChoiceCheckCell(boolean compareColumn) {
            this.compareColumn = compareColumn;
            // the whole cell is the click target, not just the small box
            setOnMouseClicked(e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || isEmpty() || isDisabled()) return;
                for (Node n = e.getPickResult().getIntersectedNode(); n != null && n != this; n = n.getParent()) {
                    if (n == getGraphic()) return; // on the box itself: it toggles on its own
                }
                if (getTableColumn().getCellObservableValue(getIndex()) instanceof BooleanProperty p) p.set(!p.get());
            });
        }

        @Override
        public void updateItem(Boolean value, boolean empty) {
            super.updateItem(value, empty);
            ColumnChoice c = empty || getTableRow() == null ? null : getTableRow().getItem();
            boolean usable = c != null && c.onBothSides() && !(compareColumn && c.joined);
            setDisable(!usable);
            setEditable(usable);
        }
    }

    /** One join of the "Key from other tables" editor: [left] join table alias on condition. */
    private final class JoinRow extends HBox {
        final ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("join", "left join"));
        final SearchBox<TableRef> table = new SearchBox<>(TableRef::name, "join table…");
        final javafx.scene.control.TextField alias = new javafx.scene.control.TextField();
        final javafx.scene.control.TextField on = new javafx.scene.control.TextField();

        JoinRow() {
            super(6);
            setAlignment(Pos.CENTER_LEFT);
            type.setValue("join");
            table.setItems(dataSource.table.getItems());
            alias.setPrefColumnCount(5);
            alias.setPromptText("alias");
            on.setPromptText("e.g. mpe.id = " + (mainAlias.getText().isBlank() ? "mpi" : mainAlias.getText()) + ".etalase_id");
            HBox.setHgrow(on, Priority.ALWAYS);
            Button remove = new Button("✕");
            remove.setTooltip(new Tooltip("Remove this join"));
            remove.setOnAction(e -> {
                joinRows.getChildren().remove(this);
                loadColumns();
            });
            table.valueProperty().addListener((o, a, t) -> {
                if (t == null) return;
                if (alias.getText().isBlank()) alias.setText(SqlCompleter.aliasFor(t.name(), takenAliases()));
                suggestOn(this);
                loadColumns();
            });
            alias.setOnAction(e -> loadColumns());
            alias.focusedProperty().addListener((o, was, focused) -> {
                if (!focused) loadColumns();
            });
            getChildren().addAll(type, table, alias, new Label("on"), on, remove);
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

    private List<JoinRow> joinRowList() {
        return joinRows.getChildren().stream().map(n -> (JoinRow) n).toList();
    }

    /** The joins in use: none while the advanced options are hidden. */
    private List<JoinRow> activeJoinRows() {
        return advanced.isSelected() ? joinRowList() : List.of();
    }

    private java.util.Set<String> takenAliases() {
        java.util.Set<String> taken = new java.util.HashSet<>();
        taken.add(mainAlias.getText().strip());
        joinRowList().forEach(r -> taken.add(r.alias.getText().strip()));
        return taken;
    }

    /**
     * Fills an empty ON condition from a foreign key: of the main table or an earlier join to the picked table
     * (e.g. mpi.etalase_id → m_product_etalase.id gives "mpe.id = mpi.etalase_id"). Only reads metadata.
     */
    private void suggestOn(JoinRow row) {
        DatabaseSession s = dataSource.session;
        TableRef main = dataSource.table.getValue();
        if (s == null || main == null || !row.on.getText().isBlank()) return;
        List<Map.Entry<String, TableRef>> from = new ArrayList<>();
        from.add(Map.entry(mainAlias.getText().strip(), main));
        for (JoinRow r : joinRowList()) {
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

    /** Lists the columns of both tables once both are chosen, with the joined tables' columns (see {@link #setColumns}). */
    private void loadColumns() {
        TableRef t1 = dataSource.table.getValue();
        TableRef t2 = dataTarget.table.getValue();
        if (t1 == null || t2 == null || dataSource.session == null || dataTarget.session == null) return;
        DatabaseSession s1 = dataSource.session;
        DatabaseSession s2 = dataTarget.session;
        // joined tables: picked from the source schema, found by name in the target schema
        List<JoinRow> rows = activeJoinRows().stream().filter(JoinRow::ready).toList();
        joinRowList().forEach(r -> r.table.setItems(dataSource.table.getItems()));
        List<String> aliases = rows.stream().map(r -> r.alias.getText().strip()).toList();
        List<TableRef> srcJoins = rows.stream().map(r -> r.table.getValue()).toList();
        List<TableRef> dstJoins = srcJoins.stream().map(t -> dataTarget.table.getItems().stream()
                .filter(x -> x.name().equalsIgnoreCase(t.name())).findFirst().orElse(null)).toList();
        Async.run(s1, () -> columnNames(s1, t1, srcJoins, aliases), first -> Async.run(s2, () -> columnNames(s2, t2, dstJoins, aliases), second -> {
            if (dataSource.table.getValue() != t1 || dataTarget.table.getValue() != t2) return;
            setColumns(first, second);
        }, ex -> dataStatus.setText(ex.getMessage())), ex -> dataStatus.setText(ex.getMessage()));
    }

    /** The main table's column names, then "alias.column" for each joined table (a missing table adds none). Session thread. */
    private static List<String> columnNames(DatabaseSession s, TableRef main, List<TableRef> joins, List<String> aliases)
            throws java.sql.SQLException {
        List<String> names = new ArrayList<>();
        s.columnDefs(main).forEach(c -> names.add(c.name()));
        for (int i = 0; i < joins.size(); i++) {
            if (joins.get(i) == null) continue;
            for (DatabaseSession.ColumnDef c : s.columnDefs(joins.get(i))) names.add(aliases.get(i) + "." + c.name());
        }
        return names;
    }

    /** Fills the column picker; choices already made for a column name are kept. */
    private void setColumns(List<String> srcCols, List<String> dstCols) {
        Map<String, Boolean[]> previous = new java.util.HashMap<>();
        columnChoices.forEach(c -> previous.put(c.name.toLowerCase(), new Boolean[]{c.key.get(), c.compare.get(), c.show.get()}));
        java.util.Set<String> tgtNames = new java.util.HashSet<>();
        dstCols.forEach(c -> tgtNames.add(c.toLowerCase()));
        java.util.Set<String> srcNames = new java.util.HashSet<>();
        List<ColumnChoice> choices = new ArrayList<>();
        for (String c : srcCols) {
            srcNames.add(c.toLowerCase());
            choices.add(new ColumnChoice(c, true, tgtNames.contains(c.toLowerCase())));
        }
        for (String c : dstCols) {
            if (!srcNames.contains(c.toLowerCase())) choices.add(new ColumnChoice(c, false, true));
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
    }

    private void runDataCompare() {
        if (!dataSource.readyWithTable() || !dataTarget.readyWithTable()) {
            dataStatus.setText("Pick a connection, schema and table on both sides.");
            return;
        }
        List<String> keys = columnChoices.stream().filter(c -> c.key.get()).map(c -> c.name).toList();
        List<String> compared = columnChoices.stream().filter(c -> c.compare.get() && !c.key.get() && !c.joined).map(c -> c.name).toList();
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
        // joins only matter when a joined column is used (as key or shown)
        List<String> joinedColumns = java.util.stream.Stream.concat(keys.stream(), shownOnly.stream())
                .filter(KeyJoins::isJoinedColumn).distinct().toList();
        List<KeyJoins.Join> joins = joinedColumns.isEmpty() ? List.of() : activeJoinRows().stream().map(JoinRow::join).toList();
        String alias = mainAlias.getText().strip();
        String joinProblem = joins.isEmpty() ? null : KeyJoins.problem(alias, joins);
        if (joinProblem != null) {
            dataStatus.setText(joinProblem);
            return;
        }
        List<TableRef> srcJoins = joins.isEmpty() ? List.of() : activeJoinRows().stream().map(r -> r.table.getValue()).toList();
        List<TableRef> dstJoins = new ArrayList<>();
        for (KeyJoins.Join j : joins) {
            TableRef t = dataTarget.table.getItems().stream().filter(x -> x.name().equalsIgnoreCase(j.table())).findFirst().orElse(null);
            if (t == null) {
                dataStatus.setText(j.table() + " is not in the target schema " + dataTarget.schemaLabel() + ".");
                return;
            }
            dstJoins.add(t);
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
        DataCompareReport.Side srcSide = new DataCompareReport.Side(dataSource.connection.getValue().getName(),
                dataSource.schemaLabel(), t1.name(), src);
        DataCompareReport.Side dstSide = new DataCompareReport.Side(dataTarget.connection.getValue().getName(),
                dataTarget.schemaLabel(), t2.name(), dst);
        Runnable whenBoth = () -> {
            if (read[0] == null || read[1] == null) return;
            boolean truncated = read[0].truncated() || read[1].truncated();
            Async.run(() -> DataCompareReport.of(DataCompare.compare(read[0].data(), read[1].data(), keys, compared, shownOnly,
                            MAX_LISTED_ROWS), srcSide, dstSide, keys, filter, limit, truncated, "v" + AppFooter.version()),
                    r -> {
                        if (!confirmDiscardVerdicts()) return;
                        r.mainAlias = joins.isEmpty() ? null : alias;
                        r.joins = joins;
                        reportFile = null;
                        unsaved = false;
                        showReport(r);
                    }, failed);
        };
        Async.run(s1, () -> read(s1, t1, alias, joins, srcJoins, joinedColumns, filter, keys, limit), r -> {
            read[0] = r;
            whenBoth.run();
        }, failed);
        Async.run(s2, () -> read(s2, t2, alias, joins, dstJoins, joinedColumns, filter, keys, limit), r -> {
            read[1] = r;
            whenBoth.run();
        }, failed);
    }

    /** One side's rows in key order: the table alone, or with the joins that give its key. Session thread. */
    private static StatementResult read(DatabaseSession s, TableRef main, String alias, List<KeyJoins.Join> joins,
                                        List<TableRef> joinTables, List<String> joinedColumns, String filter,
                                        List<String> keys, int limit) throws java.sql.SQLException {
        if (joins.isEmpty()) return s.fetchOrdered(main, keys, filter, limit);
        List<String> qualified = new ArrayList<>();
        for (TableRef t : joinTables) qualified.add(s.qualifiedName(t));
        return s.execute(KeyJoins.select(s.qualifiedName(main), alias, joins, qualified, joinedColumns, filter, keys,
                s.identifierQuote()), limit);
    }

    /** The filter has to stay a condition of one SELECT: one statement, nothing that writes. */
    private static boolean isPlainCondition(String condition) {
        String sql = "select * from t where (" + condition + ")";
        return SqlSplitter.split(sql, null).size() == 1 && SqlSplitter.isReadOnlyQuery(sql);
    }

    /** Shows a report: the summary above, one grid row per listed row with its verdict. */
    private void showReport(DataCompareReport r) {
        report = r;
        String src = r.source.label();
        String dst = r.target.label();
        StringBuilder sb = new StringBuilder();
        sb.append(src).append(": ").append(r.sourceRows).append(" rows · ").append(dst).append(": ").append(r.targetRows)
                .append(" rows  →  ").append(r.same).append(" identical · ").append(r.changed).append(" changed · ")
                .append(r.onlySource).append(" only in ").append(src).append(" · ")
                .append(r.onlyTarget).append(" only in ").append(dst);
        if (r.duplicateKeys > 0) {
            sb.append("\n⚠ ").append(r.duplicateKeys).append(" key value(s) match more than one row: ")
                    .append(String.join(", ", r.keyColumns)).append(" does not identify rows uniquely.");
        }
        if (r.joins != null && !r.joins.isEmpty()) {
            sb.append("\nKey joins: ").append(r.source.table()).append(' ').append(r.mainAlias).append(' ')
                    .append(String.join(" ", r.joins.stream().map(KeyJoins::describe).toList()));
        }
        if (r.where != null && !r.where.isEmpty()) sb.append("\nWhere: ").append(r.where);
        if (r.truncated) {
            sb.append("\n⚠ Only the first ").append(r.rowsPerSide).append(" rows of each side (in key order) were compared; ")
                    .append("rows near that limit may wrongly show as only on one side. Raise \"Rows per side\" to compare more.");
        }
        if (r.rows.size() >= MAX_LISTED_ROWS) sb.append("\nListing the first ").append(r.rows.size()).append(" rows.");
        dataStatus.setText(sb.toString());

        // one column per key / compared / shown column; a changed cell shows "old → new"
        reportHeader = new ArrayList<>();
        reportHeader.add("Status");
        reportHeader.add("Verdict");
        reportHeader.addAll(r.shownColumns);
        reportHeader.add("Note");
        reportRows.clear();
        Map<DataCompare.RowStatus, Integer> counts = new java.util.EnumMap<>(DataCompare.RowStatus.class);
        for (DataCompareReport.Row d : r.rows) {
            counts.merge(d.status, 1, Integer::sum);
            List<Object> row = new ArrayList<>();
            row.add(new StatusValue(d.status.name(), statusLabel(d.status, src, dst)));
            row.add(new VerdictCell(d));
            for (int c = 0; c < r.shownColumns.size(); c++) {
                Object a = d.source == null ? null : d.source.get(c);
                Object b = d.target == null ? null : d.target.get(c);
                boolean isCompared = r.comparedColumns.contains(r.shownColumns.get(c));
                row.add(new ComparedValue(a, b, d.source != null, d.target != null, src, dst, isCompared));
            }
            row.add(d.status == DataCompare.RowStatus.CHANGED || d.status == DataCompare.RowStatus.MATCH ? ""
                    : d.note.replace("in source", "in " + src).replace("in target", "in " + dst));
            reportRows.add(Map.entry(d.status, row));
        }
        statusFilters.forEach((status, box) -> box.setText(statusLabel(status, src, dst) + " (" + counts.getOrDefault(status, 0) + ")"));
        verdictForShown.getItems().setAll(verdictItems(v -> applyVerdict(v, List.copyOf(dataGrid.getItems()))));
        fileStatus.setText(reportFile == null ? "" : "Saved as " + reportFile.getFileName());
        statusBar.setVisible(true);
        statusBar.setManaged(true);
        updateVerdictSummary();
        refreshGrid();
    }

    /** The report rows whose status is ticked in the filter bar. */
    private void refreshGrid() {
        List<List<Object>> rows = reportRows.stream()
                .filter(e -> statusFilters.get(e.getKey()).isSelected())
                .map(Map.Entry::getValue)
                .toList();
        dataGrid.setPlaceholder(new Label(reportRows.isEmpty() ? "No rows to compare." : "No rows with the ticked statuses."));
        dataGrid.show(new TableData(reportHeader, rows));
        // show() starts without a menu: right-click sets the verdict of the selected rows
        dataGrid.setContextMenu(report == null ? null : new ContextMenu(verdictItems(v -> applyVerdict(v, selectedRows()))
                .toArray(MenuItem[]::new)));
    }

    // ---- verdicts ----------------------------------------------------------

    /** The Verdict cell of a grid row: shows the report row's verdict, "–" while undecided. */
    private final class VerdictCell {
        final DataCompareReport.Row row;

        VerdictCell(DataCompareReport.Row row) {
            this.row = row;
        }

        @Override
        public String toString() {
            if (row.verdict != null) return row.verdict.label(report.target.label());
            return Verdict.allowed(row.status).isEmpty() ? "" : "–";
        }
    }

    /** Insert / Update / Delete / Skip / Clear, each applying to the rows given when it is clicked. */
    private List<MenuItem> verdictItems(Consumer<Verdict> apply) {
        List<MenuItem> items = new ArrayList<>();
        for (Verdict v : Verdict.values()) {
            MenuItem item = new MenuItem(v.label(report.target.label()));
            item.setOnAction(e -> apply.accept(v));
            items.add(item);
        }
        MenuItem clear = new MenuItem("Clear verdict");
        clear.setOnAction(e -> apply.accept(null));
        items.add(new SeparatorMenuItem());
        items.add(clear);
        return items;
    }

    /** The rows with a selected cell, each once. */
    private Collection<List<Object>> selectedRows() {
        Set<List<Object>> rows = Collections.newSetFromMap(new IdentityHashMap<>());
        rows.addAll(dataGrid.getSelectionModel().getSelectedItems());
        return rows;
    }

    /** Sets (null: clears) the verdict of the given grid rows where it fits their status; other rows are left alone. */
    private void applyVerdict(Verdict v, Collection<List<Object>> rows) {
        int changed = 0;
        int skipped = 0;
        for (List<Object> row : rows) {
            DataCompareReport.Row r = ((VerdictCell) row.get(1)).row;
            if (v != null && !Verdict.allowed(r.status).contains(v)) {
                skipped++;
                continue;
            }
            if (r.verdict != v) {
                r.verdict = v;
                changed++;
            }
        }
        if (changed > 0) unsaved = true;
        dataGrid.refresh();
        updateVerdictSummary();
        fileStatus.setText(changed + " verdict(s) set" + (skipped > 0 ? "; " + skipped + " row(s) left alone (" + (v == null ? "" : v.label())
                + " does not fit their status)" : "") + (unsaved ? " · not saved" : ""));
    }

    private void updateVerdictSummary() {
        if (report == null) return;
        verdictSummary.setText(report.count(Verdict.INSERT) + " insert · " + report.count(Verdict.UPDATE) + " update · "
                + report.count(Verdict.DELETE) + " delete · " + report.count(Verdict.SKIP) + " skip · "
                + report.undecided() + " not decided");
    }

    // ---- saving --------------------------------------------------------------

    private boolean saveReport() {
        if (report == null) return false;
        Path file = CompareReportChooser.saveReport(getTabPane().getScene().getWindow(), reportFile, CompareReportChooser.defaultName(
                "DataCompare-" + report.source.table(), report.source.label(), report.target.label()));
        if (file == null) return false;
        try {
            CompareReportFiles.save(file, report);
        } catch (IOException e) {
            error("Could not save the report", e);
            return false;
        }
        reportFile = file;
        unsaved = false;
        setText("Data Compare: " + file.getFileName());
        fileStatus.setText("Saved as " + file);
        return true;
    }

    private void exportHtml() {
        if (report == null) return;
        Path file = CompareReportChooser.saveHtml(getTabPane().getScene().getWindow(), reportFile, CompareReportChooser.defaultName(
                "DataCompare-" + report.source.table(), report.source.label(), report.target.label()));
        if (file == null) return;
        try {
            Files.writeString(file, CompareHtml.data(report), StandardCharsets.UTF_8);
            fileStatus.setText("HTML saved as " + file);
        } catch (IOException e) {
            error("Could not save the HTML report", e);
        }
    }

    /** Asks about unsaved verdicts before the tab closes. Returns false if the user cancelled. */
    boolean confirmClose() {
        return confirmDiscardVerdicts();
    }

    /** Asks before unsaved verdicts are lost (closing, or a new compare replacing the report). */
    private boolean confirmDiscardVerdicts() {
        if (!unsaved) return true;
        if (getTabPane() != null) getTabPane().getSelectionModel().select(this);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "The verdicts of this compare are not saved.", save, discard, ButtonType.CANCEL);
        alert.initOwner(getTabPane().getScene().getWindow());
        alert.setHeaderText("Save the report?");
        ButtonType answer = InAppDialogs.showAndWait(alert).orElse(ButtonType.CANCEL);
        if (answer == save) return saveReport();
        if (answer == discard) unsaved = false;
        return answer == discard;
    }

    private void error(String header, Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(e.getMessage()), ButtonType.OK);
        alert.initOwner(getTabPane().getScene().getWindow());
        alert.setHeaderText(header);
        InAppDialogs.showAndWait(alert);
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

}
