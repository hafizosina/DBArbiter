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
import com.dbadmin.service.compare.MigrationSql;
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
    /** The file holds only the settings (Save keeps it that way); else settings and results. */
    private boolean fileSettingsOnly;
    /** Verdicts changed since the report was last saved. */
    private boolean unsaved;
    /** Compared again since its results file was saved: the file is out of date. */
    private boolean resultsUnsaved;
    /** The tab's name without the "*" of unsaved changes. */
    private String titleBase = "Data Compare";
    /** Clickable when there is something to save (always for a compare without a file yet). */
    private final Button saveButton = new Button("Save");
    private final MenuButton verdictForShown = new MenuButton("Verdict for shown rows");
    private final Label verdictSummary = new Label();
    private final Label fileStatus = new Label();
    /** Joined tables (for a key, or to compare an id column by the code it points to): the main table's alias, one {@link JoinRow} per join. */
    private final javafx.scene.control.TextField mainAlias = new javafx.scene.control.TextField();
    /** The alias last filled in automatically, replaced when the main table changes (unless the user typed one). */
    private String autoAlias = "";
    private final VBox joinRows = new VBox(4);
    /** Several rows per key: a key's rows are matched by their compared values (see DataCompare). */
    private final CheckBox oneToMany = new CheckBox("Several rows per key (one-to-many)");

    private final ObservableList<ConnectionConfig> connections;
    private final SessionProvider sessions;

    DataCompareTab(ObservableList<ConnectionConfig> connections, SessionProvider sessions, ConnectionConfig initial) {
        super("Data Compare");
        this.connections = connections;
        this.sessions = sessions;
        dataSource = new CompareSide("Source", true, connections, sessions, dataStatus::setText);
        dataTarget = new CompareSide("Target", true, connections, sessions, dataStatus::setText);
        dataTarget.preferredTable = dataSource::tableName;
        CompareSide.pair(dataSource, dataTarget);
        setContent(dataPage());
        getContent().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (SchemaCompareTab.SAVE_AS_KEY.match(e)) {
                e.consume();
                saveAs();
            } else if (SchemaCompareTab.SAVE_KEY.match(e)) {
                e.consume();
                save();
            }
        });
        if (initial != null) dataSource.connection.setValue(initial);
        setOnCloseRequest(e -> {
            if (!confirmClose()) e.consume();
        });
    }

    /** Shows a report opened from a file; a new compare can still be run from the form above. */
    void showSaved(DataCompareReport saved, Path file) {
        reportFile = file;
        fileSettingsOnly = saved.settingsOnly;
        unsaved = false;
        resultsUnsaved = false;
        setTitle("Data Compare: " + file.getFileName());
        restoreSettings(saved);
        if (saved.settingsOnly) {
            autoCompareColumns = saved.shownColumns;
            dataStatus.setText("Settings from " + file.getFileName() + ": comparing as soon as both sides are ready…");
            return;
        }
        showReport(saved);
    }

    /** A reopened settings file: compare once these columns are listed (both sides connected, tables and joins picked). */
    private List<String> autoCompareColumns;

    private void maybeAutoCompare() {
        if (autoCompareColumns == null || !dataSource.readyWithTable() || !dataTarget.readyWithTable()) return;
        if (joinRowList().stream().anyMatch(r -> r.pendingTable != null)) return;
        java.util.Set<String> listed = new java.util.HashSet<>();
        columnChoices.forEach(c -> listed.add(c.name.toLowerCase()));
        if (!autoCompareColumns.stream().allMatch(c -> listed.contains(c.toLowerCase()))) return;
        autoCompareColumns = null;
        runDataCompare();
    }

    /**
     * The form's settings as a file that compares again when opened; null when incomplete (said in the status
     * line unless {@code quiet}).
     */
    private DataCompareReport formSettings(boolean quiet) {
        if (!dataSource.readyWithTable() || !dataTarget.readyWithTable()) {
            if (!quiet) dataStatus.setText("Pick a connection, schema and table on both sides first.");
            return null;
        }
        List<String> keys = columnChoices.stream().filter(c -> c.key.get()).map(c -> c.name).toList();
        if (keys.isEmpty()) {
            if (!quiet) dataStatus.setText("Tick Key for the column(s) that identify a row first.");
            return null;
        }
        List<String> compared = columnChoices.stream().filter(c -> c.compare.get() && !c.key.get()).map(c -> c.name).toList();
        List<String> shown = new ArrayList<>(keys);
        shown.addAll(compared);
        columnChoices.stream().filter(c -> c.show.get() && !c.key.get() && !c.compare.get()).forEach(c -> shown.add(c.name));
        DataCompareReport r = new DataCompareReport();
        r.settingsOnly = true;
        r.created = java.time.LocalDateTime.now().withNano(0).toString();
        r.appVersion = "v" + AppFooter.version();
        r.source = new DataCompareReport.Side(dataSource.connection.getValue().getName(), dataSource.schemaLabel(),
                dataSource.tableName(), dataSource.label());
        r.target = new DataCompareReport.Side(dataTarget.connection.getValue().getName(), dataTarget.schemaLabel(),
                dataTarget.tableName(), dataTarget.label());
        r.keyColumns = keys;
        r.comparedColumns = compared;
        r.shownColumns = shown;
        r.where = where.getText() == null ? "" : where.getText().trim();
        r.rowsPerSide = rowLimit.getValue();
        r.oneToMany = oneToMany.isSelected();
        if (!activeJoinRows().isEmpty()) {
            r.mainAlias = mainAlias.getText().strip();
            r.joins = activeJoinRows().stream().map(JoinRow::join).toList();
        }
        return r;
    }

    /**
     * Save (Ctrl+S): to the compare's file, the same kind as it is (results, or settings only); without a file
     * yet, as {@link #saveAs}. Returns true if the results (and verdicts) were saved.
     */
    private boolean save() {
        if (reportFile == null) return saveAs();
        if (!fileSettingsOnly && report != null && !report.settingsOnly) return writeResults(reportFile);
        DataCompareReport settings = formSettings(false);
        if (settings == null) return false; // formSettings said what is missing
        return writeSettings(reportFile, settings);
    }

    /**
     * Save As… (Ctrl+Shift+S): the in-app dialog asks where, and what: the settings and results (the default) or
     * the settings only. Returns true if the results (and verdicts) were saved.
     */
    private boolean saveAs() {
        boolean hasResults = report != null && !report.settingsOnly;
        DataCompareReport settings = formSettings(hasResults);
        if (!hasResults && settings == null) return false; // formSettings said what is missing
        DataCompareReport named = hasResults ? report : settings;
        CompareReportChooser.SaveChoice choice = CompareReportChooser.saveCompare(getTabPane().getScene().getWindow(), reportFile,
                CompareReportChooser.defaultName("DataCompare-" + named.source.table(), named.source.label(), named.target.label()),
                hasResults, settings != null);
        if (choice == null) return false;
        return choice.withResults() ? writeResults(choice.file()) : writeSettings(choice.file(), settings);
    }

    /** Writes the settings only; with verdicts set, offers to save them too (the results). */
    private boolean writeSettings(Path file, DataCompareReport settings) {
        long verdicts = report != null && !report.settingsOnly ? report.rows.stream().filter(x -> x.verdict != null).count() : 0;
        if (verdicts > 0) {
            // settings only would leave the verdicts out
            ButtonType withVerdicts = new ButtonType("Save with verdicts", ButtonBar.ButtonData.YES);
            ButtonType settingsOnly = new ButtonType("Settings only", ButtonBar.ButtonData.NO);
            Alert ask = new Alert(Alert.AlertType.WARNING, verdicts + " verdict(s) are set. Saving the settings only leaves them out: "
                    + "the file compares again when opened, without verdicts.", withVerdicts, settingsOnly, ButtonType.CANCEL);
            ask.initOwner(getTabPane().getScene().getWindow());
            ask.setHeaderText("Save the verdicts too?");
            ask.getDialogPane().setPrefWidth(560);
            ButtonType answer = InAppDialogs.showAndWait(ask).orElse(ButtonType.CANCEL);
            if (answer == withVerdicts) return writeResults(file);
            if (answer != settingsOnly) return false;
        }
        try {
            CompareReportFiles.save(file, settings);
        } catch (IOException e) {
            error("Could not save the settings", e);
            return false;
        }
        reportFile = file;
        fileSettingsOnly = true;
        unsaved = false; // chosen: the settings only (the verdicts were offered)
        resultsUnsaved = false;
        setTitle("Data Compare: " + file.getFileName());
        dataStatus.setText("Settings saved to " + file + " · opening it compares again");
        return false;
    }

    private boolean writeResults(Path file) {
        try {
            CompareReportFiles.save(file, report);
        } catch (IOException e) {
            error("Could not save the report", e);
            return false;
        }
        reportFile = file;
        fileSettingsOnly = false;
        unsaved = false;
        resultsUnsaved = false;
        setTitle("Data Compare: " + file.getFileName());
        fileStatus.setText("Saved to " + file);
        return true;
    }

    /**
     * Puts a saved report's settings back in the form (connections by name, schemas, tables, joins, ticked
     * columns, Where, rows per side), so it can be compared again. Connects to both sides, as picking them does.
     */
    private void restoreSettings(DataCompareReport r) {
        where.setText(r.where == null ? "" : r.where);
        if (r.rowsPerSide > 0) rowLimit.getValueFactory().setValue(r.rowsPerSide);
        oneToMany.setSelected(r.oneToMany);
        // the ticks: kept by name when the tables' columns arrive (see setColumns)
        List<ColumnChoice> ticks = new ArrayList<>();
        for (String c : r.shownColumns) {
            ColumnChoice choice = new ColumnChoice(c, true, true);
            choice.key.set(r.keyColumns.contains(c));
            choice.compare.set(!choice.key.get() && r.comparedColumns.contains(c));
            choice.show.set(!choice.key.get() && !choice.compare.get());
            ticks.add(choice);
        }
        columnChoices.setAll(ticks);
        joinRows.getChildren().clear();
        if (r.joins != null && !r.joins.isEmpty()) {
            mainAlias.setText(r.mainAlias == null ? "" : r.mainAlias);
            for (KeyJoins.Join j : r.joins) {
                JoinRow row = new JoinRow();
                row.type.setValue(j.left() ? "left join" : "join");
                row.alias.setText(j.alias());
                row.on.setText(j.on());
                row.pendingTable = j.table(); // picked once the source schema's tables are listed
                joinRows.getChildren().add(row);
            }
            advanced.setSelected(true);
        }
        ConnectionConfig source = connectionNamed(r.source.connection());
        ConnectionConfig target = connectionNamed(r.target.connection());
        if (source == null || target == null) {
            dataStatus.setText("The connection " + (source == null ? r.source.connection() : r.target.connection())
                    + " no longer exists; pick the sides again to compare.");
        }
        if (source != null) dataSource.select(source, r.source.schema(), r.source.table());
        if (target != null) dataTarget.select(target, r.target.schema(), r.target.table());
    }

    private ConnectionConfig connectionNamed(String name) {
        return connections.stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
    }

    /** Chooses both sides' connection, schema and the table; each list loads first. */
    void select(ConnectionConfig source, String sourceSchema, ConnectionConfig target, String targetSchema, String table) {
        dataSource.select(source, sourceSchema, table);
        dataTarget.select(target, targetSchema, table);
    }

    /**
     * One column of the compared table, or of a joined table ("mpc.category_code": e.g. to compare category_id
     * by the code it points to, when the ids differ between the databases).
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
        Label joinHint = new Label("Joined columns appear as alias.column: tick them as Key, Compare or Show "
                + "(e.g. compare mpc.category_code instead of category_id).");
        joinHint.getStyleClass().add("hint-small");
        HBox joinHeader = new HBox(8, new Label("Joined tables:"), new Label("main table alias"), mainAlias, addJoin, joinHint);
        joinHeader.setAlignment(Pos.CENTER_LEFT);
        VBox joins = new VBox(4, joinHeader, joinRows);
        joins.visibleProperty().bind(advanced.selectedProperty());
        joins.managedProperty().bind(advanced.selectedProperty());
        advanced.setTooltip(new Tooltip("Advanced options: take the key from other tables (joins)"));
        advanced.selectedProperty().addListener((o, a, b) -> loadColumns()); // joined columns come and go
        where.setPromptText("optional, e.g.  is_deleted = false and type = 'EMAS'");
        where.setTooltip(new Tooltip("A SQL condition applied to both sides; only matching rows are compared"));
        where.setOnAction(e -> runDataCompare());
        // column suggestions: the table's own by name, joined ones as "alias.column" (how the filter sees them)
        ConditionCompletion.install(where, () -> columnChoices.stream().map(c -> c.name).toList(), name -> {
            if (!KeyJoins.isJoinedColumn(name)) return name;
            String q = dataSource.connection.getValue() != null
                    && dataSource.connection.getValue().getType() == com.dbadmin.model.DbType.MYSQL ? "`" : "\"";
            return q + name + q;
        });
        HBox.setHgrow(where, Priority.ALWAYS);
        // Save: the settings (opening the file compares again); the arrow: with the results and verdicts too
        Button save = saveButton;
        save.setTooltip(new Tooltip("Save this compare to its file (Ctrl+S); the first time, asks where and what"));
        save.setOnAction(e -> save());
        Button saveAs = new Button("Save As…");
        saveAs.setTooltip(new Tooltip("Save to another file, with the results (rows and verdicts) or the settings only (Ctrl+Shift+S)"));
        saveAs.setOnAction(e -> saveAs());
        Button sql = new Button("SQL…");
        sql.setTooltip(new Tooltip("The queries Compare data runs on each side, to copy and run yourself"));
        sql.setOnAction(e -> showQueries());
        HBox options = new HBox(8, new Label("Where:"), where, new Label("Rows per side:"), rowLimit, compare, sql, save, saveAs,
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
        Button suggest = new Button("Suggest verdicts");
        suggest.setTooltip(new Tooltip("Fills the rows without a verdict: only in source → INSERT, changed → UPDATE, "
                + "only in target → SKIP (DELETE only by hand). Rows already decided are kept."));
        suggest.setOnAction(e -> suggestVerdicts());
        // with rows selected: I U D S set a verdict, Backspace clears it, Ctrl+V pastes a copied one
        dataGrid.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::verdictKeys);
        verdictForShown.setTooltip(new Tooltip("Sets the verdict of every row shown now (use the status boxes to narrow them down)."
                + "\nRight-click selected rows to set theirs."));
        Button generate = new Button("Generate SQL…");
        generate.setTooltip(new Tooltip("A PostgreSQL script for the target from the verdicts: INSERT / UPDATE / DELETE, ids found "
                + "again by the joined columns (e.g. product_id by mpv.product_code), parents first, safe to run again"));
        generate.setOnAction(e -> generateSql());
        Button html = new Button("Export HTML…");
        html.setTooltip(new Tooltip("A page with the summary and the differences, to share with people who do not use DBAdmin"));
        html.setOnAction(e -> exportHtml());
        verdictSummary.getStyleClass().add("hint-small");
        fileStatus.getStyleClass().add("hint-small");
        statusBar.getChildren().addAll(new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL),
                verdictForShown, suggest, generate, html, verdictSummary, fileStatus);
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
        all.setOnAction(e -> columnChoices.forEach(c -> c.compare.set(c.onBothSides() && !c.key.get() && !c.joined)));
        Button showAll = new Button("Show all");
        showAll.setOnAction(e -> columnChoices.forEach(c -> c.show.set(c.onBothSides())));
        Button none = new Button("Clear");
        none.setOnAction(e -> columnChoices.forEach(c -> {
            c.key.set(false);
            c.compare.set(false);
            c.show.set(false);
        }));
        HBox buttons = new HBox(6, all, showAll, none);
        oneToMany.setTooltip(new Tooltip("For a key with several rows (e.g. the images of a tier code): each row is matched by the "
                + "compared columns.\nOn both sides: MATCH · only in the source: new, to add · only in the target: old, to remove."));
        VBox pane = new VBox(6, oneToMany, columnChooser, buttons);
        pane.setPadding(new Insets(6));
        VBox.setVgrow(columnChooser, Priority.ALWAYS);
        return pane;
    }

    /** A checkbox, disabled for a column that is not on both sides. */
    /** The last checkbox clicked in the column picker: a shift-click sets every row between it and the clicked one. */
    private int anchorRow = -1;
    private TableColumn<ColumnChoice, ?> anchorColumn;

    /**
     * A checkbox; the whole cell is the click target, and shift-click ticks (or unticks) the rows from the last
     * clicked one in the same column. Disabled for a column that is not on both sides.
     */
    private class ChoiceCheckCell extends javafx.scene.control.cell.CheckBoxTableCell<ColumnChoice, Boolean> {
        /** The press of a shift-click was a range: its release and click do nothing more. */
        private boolean range;

        ChoiceCheckCell() {
            // before the box sees it: a shift-click is a range, not a toggle of this one box
            addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                range = e.isShiftDown() && e.getButton() == javafx.scene.input.MouseButton.PRIMARY && !isEmpty() && !isDisabled()
                        && anchorRow >= 0 && anchorColumn == getTableColumn();
                if (range) {
                    setRange(!value(getIndex()).get());
                    e.consume();
                }
            });
            addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
                if (range) e.consume();
            });
            setOnMouseClicked(e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || isEmpty() || isDisabled()) return;
                if (range) {
                    range = false;
                    return;
                }
                anchorRow = getIndex();
                anchorColumn = getTableColumn();
                for (Node n = e.getPickResult().getIntersectedNode(); n != null && n != this; n = n.getParent()) {
                    if (n == getGraphic()) return; // on the box itself: it toggles on its own
                }
                BooleanProperty p = value(getIndex());
                p.set(!p.get());
            });
        }

        private BooleanProperty value(int row) {
            return (BooleanProperty) getTableColumn().getCellObservableValue(row);
        }

        /** Sets this column for every row from the anchor to this one (rows not on both sides are left alone). */
        private void setRange(boolean on) {
            int from = Math.min(anchorRow, getIndex());
            int to = Math.max(anchorRow, getIndex());
            for (int i = from; i <= to && i < getTableView().getItems().size(); i++) {
                if (getTableView().getItems().get(i).onBothSides()) value(i).set(on);
            }
            anchorRow = getIndex();
        }

        @Override
        public void updateItem(Boolean value, boolean empty) {
            super.updateItem(value, empty);
            ColumnChoice c = empty || getTableRow() == null ? null : getTableRow().getItem();
            boolean usable = c != null && c.onBothSides();
            setDisable(!usable);
            setEditable(usable);
        }
    }

    /** One join of the "Joined tables" editor: [left] join table alias on condition. */
    private final class JoinRow extends HBox {
        final ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("join", "left join"));
        final SearchBox<TableRef> table = new SearchBox<>(TableRef::name, "join table…");
        final javafx.scene.control.TextField alias = new javafx.scene.control.TextField();
        final javafx.scene.control.TextField on = new javafx.scene.control.TextField();
        /** A table to pick by name once the list is there (a reopened report). */
        String pendingTable;
        /** Set while the table is picked by the code: the caller loads the columns. */
        private boolean quiet;

        JoinRow() {
            super(6);
            setAlignment(Pos.CENTER_LEFT);
            type.setValue("left join"); // keeps main rows whose joined row is missing (a NULL id)
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
                if (!quiet) loadColumns();
            });
            alias.setOnAction(e -> loadColumns());
            alias.focusedProperty().addListener((o, was, focused) -> {
                if (!focused) loadColumns();
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
        joinRowList().forEach(r -> {
            r.table.setItems(dataSource.table.getItems());
            r.applyPending(); // a reopened report's join tables, now that the list is there
        });
        List<JoinRow> rows = activeJoinRows().stream().filter(JoinRow::ready).toList();
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
        maybeAutoCompare();
    }

    /** What a compare reads, taken from the form. */
    private record Plan(List<String> keys, List<String> compared, List<String> shownOnly, String filter,
                        List<String> joinedColumns, List<KeyJoins.Join> joins, String alias,
                        List<TableRef> srcJoins, List<TableRef> dstJoins, int limit, boolean oneToMany) {
    }

    /** The form as a {@link Plan}; null (with the reason in the status line) when something is missing. */
    private Plan plan() {
        if (!dataSource.readyWithTable() || !dataTarget.readyWithTable()) {
            dataStatus.setText("Pick a connection, schema and table on both sides.");
            return null;
        }
        List<String> keys = columnChoices.stream().filter(c -> c.key.get()).map(c -> c.name).toList();
        List<String> compared = columnChoices.stream().filter(c -> c.compare.get() && !c.key.get()).map(c -> c.name).toList();
        List<String> shownOnly = columnChoices.stream().filter(c -> c.show.get() && !c.key.get() && !c.compare.get())
                .map(c -> c.name).toList();
        if (keys.isEmpty()) {
            dataStatus.setText("Tick Key for the column(s) that identify a row (e.g. id).");
            return null;
        }
        String filter = where.getText() == null ? "" : where.getText().trim();
        if (!filter.isEmpty() && !isPlainCondition(filter)) {
            dataStatus.setText("The Where filter must be one read-only condition (no ';', INSERT, UPDATE, DELETE, ...).");
            return null;
        }
        // joins only matter when a joined column is used (as key, compared or shown)
        List<String> joinedColumns = java.util.stream.Stream.of(keys, compared, shownOnly).flatMap(List::stream)
                .filter(KeyJoins::isJoinedColumn).distinct().toList();
        List<KeyJoins.Join> joins = joinedColumns.isEmpty() ? List.of() : activeJoinRows().stream().map(JoinRow::join).toList();
        String alias = mainAlias.getText().strip();
        String joinProblem = joins.isEmpty() ? null : KeyJoins.problem(alias, joins);
        if (joinProblem != null) {
            dataStatus.setText(joinProblem);
            return null;
        }
        List<TableRef> srcJoins = joins.isEmpty() ? List.of() : activeJoinRows().stream().map(r -> r.table.getValue()).toList();
        List<TableRef> dstJoins = new ArrayList<>();
        for (KeyJoins.Join j : joins) {
            TableRef t = dataTarget.table.getItems().stream().filter(x -> x.name().equalsIgnoreCase(j.table())).findFirst().orElse(null);
            if (t == null) {
                dataStatus.setText(j.table() + " is not in the target schema " + dataTarget.schemaLabel() + ".");
                return null;
            }
            dstJoins.add(t);
        }
        return new Plan(keys, compared, shownOnly, filter, joinedColumns, joins, alias, srcJoins, dstJoins, rowLimit.getValue(),
                oneToMany.isSelected());
    }

    private void runDataCompare() {
        Plan p = plan();
        if (p == null) return;
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
            Async.run(() -> DataCompareReport.of(DataCompare.compare(read[0].data(), read[1].data(), p.keys(), p.compared(),
                            p.shownOnly(), MAX_LISTED_ROWS, p.oneToMany()), srcSide, dstSide, p.keys(), p.filter(), p.limit(), truncated,
                            "v" + AppFooter.version()),
                    r -> {
                        if (!confirmDiscardVerdicts()) return;
                        r.oneToMany = p.oneToMany();
                        r.mainAlias = p.joins().isEmpty() ? null : p.alias();
                        r.joins = p.joins();
                        unsaved = false;
                        // a results file is now out of date (Save writes the new results to it); a settings file is not
                        resultsUnsaved = reportFile != null && !fileSettingsOnly;
                        showReport(r);
                        refreshDirty();
                    }, failed);
        };
        Async.run(s1, () -> s1.execute(sideSql(s1, t1, p.srcJoins(), p), p.limit()), r -> {
            read[0] = r;
            whenBoth.run();
        }, failed);
        Async.run(s2, () -> s2.execute(sideSql(s2, t2, p.dstJoins(), p), p.limit()), r -> {
            read[1] = r;
            whenBoth.run();
        }, failed);
    }

    /** The query one side runs: its rows in key order, alone or with the joins. Session thread. */
    private static String sideSql(DatabaseSession s, TableRef main, List<TableRef> joinTables, Plan p) throws java.sql.SQLException {
        if (p.joins().isEmpty()) return s.orderedSql(main, p.keys(), p.filter());
        List<String> qualified = new ArrayList<>();
        for (TableRef t : joinTables) qualified.add(s.qualifiedName(t));
        return KeyJoins.select(s.qualifiedName(main), p.alias(), p.joins(), qualified, p.joinedColumns(), p.filter(), p.keys(),
                s.identifierQuote());
    }

    /** SQL…: the two queries Compare data runs, to read or run by hand. */
    private void showQueries() {
        Plan p = plan();
        if (p == null) return;
        DatabaseSession s1 = dataSource.session;
        DatabaseSession s2 = dataTarget.session;
        TableRef t1 = dataSource.table.getValue();
        TableRef t2 = dataTarget.table.getValue();
        Consumer<Throwable> failed = ex -> dataStatus.setText("Could not build the queries: " + ex.getMessage());
        Async.run(s1, () -> sideSql(s1, t1, p.srcJoins(), p), sql1 -> Async.run(s2, () -> sideSql(s2, t2, p.dstJoins(), p),
                sql2 -> InAppDialogs.show(new CompareQueriesDialog(getTabPane().getScene().getWindow(),
                        dataSource.label(), sql1, s1.getConfig().getType(), dataTarget.label(), sql2, s2.getConfig().getType(),
                        p.limit())), failed), failed);
    }

    /**
     * Generate SQL…: the verdicts as a script for the target. Reads the target's columns of the compared and joined
     * tables (connecting to the target if needed), then shows the script to copy or open in a script tab.
     */
    private void generateSql() {
        DataCompareReport r = report;
        if (r == null || r.settingsOnly) {
            dataStatus.setText("Compare first: the SQL is made from the verdicts of the results.");
            return;
        }
        if (r.count(Verdict.INSERT) + r.count(Verdict.UPDATE) + r.count(Verdict.DELETE) == 0) {
            dataStatus.setText("No row has an INSERT, UPDATE or DELETE verdict yet.");
            return;
        }
        ConnectionConfig target = connections.stream().filter(c -> c.getName().equals(r.target.connection())).findFirst().orElse(null);
        if (target == null) {
            dataStatus.setText("The target connection " + r.target.connection() + " is not in the connection list.");
            return;
        }
        if (target.getType() != com.dbadmin.model.DbType.POSTGRESQL) {
            dataStatus.setText("Generate SQL writes PostgreSQL: the target " + r.target.label() + " is " + target.getType() + ".");
            return;
        }
        Consumer<Throwable> failed = ex -> dataStatus.setText("Could not generate the SQL: " + ex.getMessage());
        String file = reportFile == null ? null : reportFile.getFileName().toString();
        dataStatus.setText("Reading the columns of " + r.target.table() + " on " + r.target.label() + "…");
        sessions.withSession(target, s -> Async.run(s, () -> MigrationSql.targetColumns(s, r), tables -> {
            if (!tables.containsKey(r.target.table().toLowerCase(java.util.Locale.ROOT))) {
                dataStatus.setText(r.target.table() + " is not in " + r.target.schema() + " on " + r.target.label() + ".");
                return;
            }
            dataStatus.setText("");
            InAppDialogs.showAndWait(new GenerateSqlDialog(getTabPane().getScene().getWindow(), r, tables, file))
                    .ifPresent(sql -> sessions.newScript(target, sql));
        }, failed), failed);
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
        fileStatus.setText(reportFile == null ? "" : "File: " + reportFile.getFileName());
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
        @SuppressWarnings("unchecked")
        TableColumn<List<Object>, Object> verdictColumn = (TableColumn<List<Object>, Object>) dataGrid.column(1);
        if (verdictColumn != null) verdictColumn.setCellFactory(c -> new VerdictDropdownCell());
    }

    /** The Verdict cell: its text and a ▾; a click opens the verdicts that fit the row's status. */
    private final class VerdictDropdownCell extends javafx.scene.control.TableCell<List<Object>, Object> {
        private final Label text = new Label();
        private final Label arrow = new Label("▾");
        private final HBox box;

        VerdictDropdownCell() {
            arrow.getStyleClass().add("hint-small");
            javafx.scene.layout.Region gap = new javafx.scene.layout.Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            box = new HBox(4, text, gap, arrow);
            box.setAlignment(Pos.CENTER_LEFT);
            setOnMouseClicked(e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || !(getItem() instanceof VerdictCell v)) return;
                List<Verdict> allowed = Verdict.allowed(v.row.status);
                if (allowed.isEmpty()) return;
                ContextMenu menu = new ContextMenu();
                for (Verdict choice : allowed) {
                    MenuItem item = verdictItem(choice);
                    item.setOnAction(a -> applyVerdict(choice, List.of(getTableRow().getItem())));
                    menu.getItems().add(item);
                }
                MenuItem clear = new MenuItem("CLEAR");
                clear.setOnAction(a -> applyVerdict(null, List.of(getTableRow().getItem())));
                menu.getItems().addAll(new SeparatorMenuItem(), clear);
                menu.show(this, javafx.geometry.Side.BOTTOM, 0, 0);
                e.consume();
            });
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            boolean choosable = !empty && item instanceof VerdictCell v && !Verdict.allowed(v.row.status).isEmpty();
            text.setText(empty || item == null ? "" : item.toString());
            Verdict chosen = !empty && item instanceof VerdictCell v ? v.row.verdict : null;
            // a colored word, like the status cells
            text.setStyle(chosen == null ? null : "-fx-background-color: " + chosen.color() + "; -fx-text-fill: "
                    + StatusValue.textColorOn(chosen.color()) + "; -fx-font-weight: bold; -fx-padding: 0 6 0 6; -fx-background-radius: 3;");
            setTooltip(chosen == null ? null : new Tooltip(chosen.label(report.target.label())));
            arrow.setVisible(choosable);
            setGraphic(empty ? null : box);
            setCursor(choosable ? javafx.scene.Cursor.HAND : null);
        }
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
            if (row.verdict != null) return row.verdict.name();
            return Verdict.allowed(row.status).isEmpty() ? "" : "–";
        }
    }

    /** "INSERT" with a swatch of its color; what it does ("Insert into Prod") in the hover text of the cell. */
    private MenuItem verdictItem(Verdict v) {
        javafx.scene.shape.Rectangle swatch = new javafx.scene.shape.Rectangle(10, 10, javafx.scene.paint.Color.web(v.color()));
        swatch.setArcWidth(3);
        swatch.setArcHeight(3);
        return new MenuItem(v.name(), swatch);
    }

    /** INSERT / UPDATE / DELETE / SKIP / CLEAR, each applying to the rows given when it is clicked. */
    private List<MenuItem> verdictItems(Consumer<Verdict> apply) {
        List<MenuItem> items = new ArrayList<>();
        for (Verdict v : Verdict.values()) {
            MenuItem item = verdictItem(v);
            item.setOnAction(e -> apply.accept(v));
            items.add(item);
        }
        MenuItem clear = new MenuItem("CLEAR");
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
    /** Every row without a verdict gets the usual one for its status; DELETE is never suggested. */
    private void suggestVerdicts() {
        if (report == null) return;
        int set = 0;
        for (Map.Entry<DataCompare.RowStatus, List<Object>> e : reportRows) {
            DataCompareReport.Row r = ((VerdictCell) e.getValue().get(1)).row;
            if (r.verdict != null) continue;
            Verdict v = switch (r.status) {
                case ONLY_SOURCE -> Verdict.INSERT;
                case CHANGED -> Verdict.UPDATE;
                case ONLY_TARGET -> Verdict.SKIP;
                case MATCH, DUPLICATE_KEY -> null;
            };
            if (v == null) continue;
            r.verdict = v;
            set++;
        }
        if (set > 0) unsaved = true;
        refreshDirty();
        dataGrid.refresh();
        updateVerdictSummary();
        fileStatus.setText(set + " verdict(s) suggested; rows already decided were kept" + (unsaved ? " · not saved" : ""));
    }

    private void verdictKeys(javafx.scene.input.KeyEvent e) {
        if (report == null || e.isAltDown()) return;
        if (e.isShortcutDown()) {
            if (e.getCode() == javafx.scene.input.KeyCode.V) {
                pasteVerdicts();
                e.consume();
            }
            return;
        }
        Verdict v = switch (e.getCode()) {
            case I -> Verdict.INSERT;
            case U -> Verdict.UPDATE;
            case D -> Verdict.DELETE;
            case S -> Verdict.SKIP;
            default -> null;
        };
        if (v != null || e.getCode() == javafx.scene.input.KeyCode.BACK_SPACE) {
            applyVerdict(v, selectedRows());
            e.consume();
        }
    }

    /**
     * Ctrl+V: a copied verdict (e.g. a Verdict cell copied with Ctrl+C) on each selected row it fits; several
     * copied verdicts onto as many selected rows go one by one.
     */
    private void pasteVerdicts() {
        String text = javafx.scene.input.Clipboard.getSystemClipboard().getString();
        if (text == null) return;
        List<String> words = text.lines().map(String::strip).filter(w -> !w.isEmpty()).toList();
        List<List<Object>> rows = dataGrid.getSelectionModel().getSelectedCells().stream()
                .map(c -> c.getRow()).distinct().sorted().map(i -> dataGrid.getItems().get(i)).toList();
        if (words.isEmpty() || rows.isEmpty()) return;
        if (words.size() == 1) {
            Verdict v = parseVerdict(words.get(0));
            if (v == null && !isClear(words.get(0))) {
                fileStatus.setText("The clipboard holds no verdict (INSERT, UPDATE, DELETE or SKIP).");
                return;
            }
            applyVerdict(v, rows);
            return;
        }
        if (words.size() != rows.size()) {
            fileStatus.setText(words.size() + " verdicts copied but " + rows.size() + " rows selected: select as many rows, or copy one verdict.");
            return;
        }
        int set = 0;
        int skipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            Verdict v = parseVerdict(words.get(i));
            DataCompareReport.Row r = ((VerdictCell) rows.get(i).get(1)).row;
            if (v == null && !isClear(words.get(i)) || v != null && !Verdict.allowed(r.status).contains(v)) {
                skipped++;
                continue;
            }
            if (r.verdict != v) {
                r.verdict = v;
                set++;
            }
        }
        if (set > 0) unsaved = true;
        refreshDirty();
        dataGrid.refresh();
        updateVerdictSummary();
        fileStatus.setText(set + " verdict(s) pasted" + (skipped > 0 ? "; " + skipped + " row(s) left alone (not a verdict for their status)" : "")
                + (unsaved ? " · not saved" : ""));
    }

    private static Verdict parseVerdict(String word) {
        try {
            return Verdict.valueOf(word.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An undecided verdict cell copied ("–") or the word CLEAR: pasting it clears the verdict. */
    private static boolean isClear(String word) {
        return word.equals("–") || word.equalsIgnoreCase("CLEAR");
    }

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
        refreshDirty();
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

    private void setTitle(String base) {
        titleBase = base;
        refreshDirty();
    }

    /** Unsaved verdicts, or results newer than the compare's results file. */
    boolean isDirty() {
        return unsaved || resultsUnsaved;
    }

    /** "*" before the tab's name while there are unsaved changes; Save clickable when there is something to save. */
    private void refreshDirty() {
        setText((isDirty() ? "*" : "") + titleBase);
        saveButton.setDisable(reportFile != null && !isDirty());
    }

    /** Asks about unsaved changes before the tab (or the app) closes. Returns false if the user cancelled. */
    boolean confirmClose() {
        if (!isDirty()) return true;
        if (getTabPane() != null) getTabPane().getSelectionModel().select(this);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "\"" + titleBase + "\" has unsaved changes"
                + (unsaved ? " (verdicts)." : " (the results of the last compare)."), save, discard, ButtonType.CANCEL);
        alert.initOwner(getTabPane().getScene().getWindow());
        alert.setHeaderText("Save changes?");
        ButtonType answer = InAppDialogs.showAndWait(alert).orElse(ButtonType.CANCEL);
        if (answer == save) return save() || !isDirty();
        return answer == discard;
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
        if (answer == save) return save();
        if (answer == discard) {
            unsaved = false;
            refreshDirty();
        }
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
