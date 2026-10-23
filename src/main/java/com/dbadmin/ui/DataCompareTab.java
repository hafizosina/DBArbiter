package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.StatementResult;
import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.SqlSplitter;
import com.dbadmin.service.compare.CompareExcel;
import com.dbadmin.service.compare.CompareHtml;
import com.dbadmin.service.compare.CompareReportFiles;
import com.dbadmin.service.compare.DataCompare;
import com.dbadmin.service.compare.DataCompareReport;
import com.dbadmin.service.compare.KeyJoins;
import com.dbadmin.service.compare.MigrationSql;
import com.dbadmin.service.compare.Verdict;
import com.dbadmin.service.compare.Verdicts;
import com.dbadmin.service.Job;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Tools → Compare Data: lists the row differences of one table in two databases, e.g. UAT (source)
 * against Prod (target), with a verdict per row (insert, update, delete or skip). Only reads. The report
 * can be saved, reopened later and exported as HTML.
 */
class DataCompareTab extends Tab implements UnsavedWork {

    /** Rows listed in a data-compare report at most (the counts cover everything). */
    private static final int MAX_LISTED_ROWS = 50_000;

    private final CompareSide dataSource;
    private final CompareSide dataTarget;
    /** Columns of the compared table: which identify a row (Key) and which are compared. */
    private final ColumnPicker picker = new ColumnPicker();
    private final javafx.scene.control.TextField where = new javafx.scene.control.TextField();
    private final javafx.scene.control.ToggleButton columnsToggle = new javafx.scene.control.ToggleButton();
    /** A report is shown: the results bar and Export HTML are there. */
    private final javafx.beans.property.BooleanProperty reportShown = new javafx.beans.property.SimpleBooleanProperty();
    private final Spinner<Integer> rowLimit = new Spinner<>(100, 1_000_000, 10_000, 1_000);
    private final Label dataStatus = new Label();
    /** The last data-compare report: each row with its status, filtered by the status check boxes. */
    private final List<Map.Entry<DataCompare.RowStatus, List<Object>>> reportRows = new ArrayList<>();
    private List<String> reportHeader = List.of();
    private final Map<DataCompare.RowStatus, CheckBox> statusFilters = new java.util.EnumMap<>(DataCompare.RowStatus.class);
    private final javafx.scene.layout.FlowPane statusBar = new javafx.scene.layout.FlowPane(10, 4);
    private final DataView dataView = new DataView();
    private final DataGrid dataGrid = dataView.grid();
    /** The report shown now (a compare just run, or one opened from a file); null before the first. */
    private DataCompareReport report;
    /** The compare's file and whether the tab differs from it (verdicts not saved, results newer than the file). */
    private final CompareFile compareFile;
    /** Clickable when there is something to save (always for a compare without a file yet). */
    private final Button saveButton = new Button("Save");
    /** Compare, or Cancel while a compare runs. */
    private final Button compareButton = new Button("Compare");
    private final MenuButton verdictForShown = new MenuButton();
    private final Label verdictSummary = new Label();
    /** Joined tables (for a key, or to compare an id column by the code it points to). */
    private final JoinEditor joins;
    /** Several rows per key: a key's rows are matched by their compared values (see DataCompare). */
    private final CheckBox oneToMany = new CheckBox("One-to-many");
    private final CheckBox ignoreLineEndings = new CheckBox("Ignore line endings & empty");

    private final ObservableList<ConnectionConfig> connections;
    private final SessionProvider sessions;

    DataCompareTab(ObservableList<ConnectionConfig> connections, SessionProvider sessions, ConnectionConfig initial) {
        super("Data Compare");
        setGraphic(TabBadge.of(TabBadge.Kind.COMPARE));
        this.connections = connections;
        this.sessions = sessions;
        dataSource = new CompareSide("Source", true, connections, sessions, dataStatus::setText);
        dataTarget = new CompareSide("Target", true, connections, sessions, dataStatus::setText);
        dataTarget.preferredTable = dataSource::tableName;
        CompareSide.pair(dataSource, dataTarget);
        joins = new JoinEditor(dataSource, this::loadColumns);
        setContent(dataPage());
        compareFile = new CompareFile(this, saveButton, "Data Compare");
        getContent().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (SchemaCompareTab.SAVE_AS_KEY.match(e)) {
                e.consume();
                saveAs();
            } else if (SchemaCompareTab.SAVE_KEY.match(e)) {
                e.consume();
                save();
            } else if (SchemaCompareTab.COMPARE_KEY.match(e)) {
                e.consume();
                runDataCompare();
            }
        });
        if (initial != null) dataSource.connection.setValue(initial);
        setOnCloseRequest(e -> {
            if (!confirmClose()) e.consume();
        });
        setOnClosed(e -> {
            if (running != null) running.cancel(); // its connections close with it
        });
    }

    /** The results shown now, null before a compare (for tests). */
    DataCompareReport report() {
        return report;
    }

    /** Runs Compare as the button does (for tests). */
    void compareNow() {
        compareOrCancel();
    }

    /** Shows a report opened from a file; a new compare can still be run from the form above. */
    void showSaved(DataCompareReport saved, Path file) {
        compareFile.at(file, saved.settingsOnly);
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
        if (joins.pending()) return;
        if (!picker.lists(autoCompareColumns)) return;
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
        List<String> keys = picker.keys();
        if (keys.isEmpty()) {
            if (!quiet) dataStatus.setText("Tick Key for the column(s) that identify a row first.");
            return null;
        }
        List<String> compared = picker.compared();
        List<String> shown = new ArrayList<>(keys);
        shown.addAll(compared);
        shown.addAll(picker.shownOnly());
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
        r.ignoreLineEndings = ignoreLineEndings.isSelected();
        if (!joins.isEmpty()) {
            r.mainAlias = joins.mainAlias();
            r.joins = joins.joins();
        }
        return r;
    }

    /**
     * Save (Ctrl+S): to the compare's file, the same kind as it is (results, or settings only); without a file
     * yet, as {@link #saveAs}. Returns true if the results (and verdicts) were saved.
     */
    private boolean save() {
        Path file = compareFile.file();
        if (file == null) return saveAs();
        if (!compareFile.isSettingsOnly() && report != null && !report.settingsOnly) return writeResults(file);
        DataCompareReport settings = formSettings(false);
        if (settings == null) return false; // formSettings said what is missing
        return writeSettings(file, settings);
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
        CompareReportChooser.SaveChoice choice = CompareReportChooser.saveCompare(getTabPane().getScene().getWindow(), compareFile.file(),
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
        compareFile.at(file, true); // chosen: the settings only (the verdicts were offered)
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
        compareFile.at(file, false);
        dataStatus.setText("Saved to " + file);
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
        ignoreLineEndings.setSelected(r.ignoreLineEndings);
        // the ticks: kept by name when the tables' columns arrive (see setColumns)
        picker.restore(r.shownColumns, r.keyColumns, r.comparedColumns);
        joins.restore(r.mainAlias, r.joins);
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

    private Node dataPage() {
        Button compare = compareButton;
        compare.getStyleClass().add("default-look");
        compare.setTooltip(new Tooltip("Compare the rows of both tables (Ctrl+Enter); while it runs: Cancel"));
        compare.setOnAction(e -> compareOrCancel());
        Button swap = CompareSide.swapButton(dataSource, dataTarget);
        dataSource.table.valueProperty().addListener((o, a, t) -> loadColumns());
        dataTarget.table.valueProperty().addListener((o, a, t) -> loadColumns());
        rowLimit.setEditable(true);
        rowLimit.setPrefWidth(95);
        rowLimit.setTooltip(new Tooltip("Rows read from each side, in key order"));

        Button addJoin = new Button("+ Join");
        addJoin.setTooltip(new Tooltip("Join another table, e.g. to take the key from a code when the ids differ between the "
                + "databases;\nits columns appear as alias.column. Only the main table is compared."));
        addJoin.setOnAction(e -> joins.add());
        where.setPromptText("Where, e.g.  is_deleted = false");
        where.setTooltip(new Tooltip("A SQL condition applied to both sides; only matching rows are compared"));
        where.setOnAction(e -> runDataCompare());
        // column suggestions: the table's own by name, joined ones as "alias.column" (how the filter sees them)
        ConditionCompletion.install(where, picker::names, name -> {
            if (!KeyJoins.isJoinedColumn(name)) return name;
            com.dbadmin.model.DbType type = dataSource.connection.getValue() == null ? com.dbadmin.model.DbType.POSTGRESQL
                    : dataSource.connection.getValue().getType();
            return type.quote(name);
        });
        HBox.setHgrow(where, Priority.ALWAYS);
        oneToMany.setTooltip(new Tooltip("For a key with several rows (e.g. the images of a tier code): each row is matched by the "
                + "compared columns.\nOn both sides: MATCH · only in the source: new, to add · only in the target: old, to remove."));
        ignoreLineEndings.setTooltip(new Tooltip("Text that differs only in its line endings (Windows CRLF ␍ or LF) counts as the same,"
                + "\nand so do NULL, an empty text '' and a text of only spaces.\nApplies from the next Compare."));
        saveButton.setTooltip(new Tooltip("Save this compare to its file (Ctrl+S); the first time, asks where and what"));
        saveButton.setOnAction(e -> save());
        MenuItem saveAs = new MenuItem("Save As…");
        saveAs.setAccelerator(SchemaCompareTab.SAVE_AS_KEY);
        saveAs.setOnAction(e -> saveAs());
        MenuItem html = new MenuItem("Export HTML…");
        html.setOnAction(e -> exportHtml());
        html.disableProperty().bind(reportShown.not());
        MenuItem excel = new MenuItem("Export Excel…");
        excel.setOnAction(e -> exportExcel());
        excel.disableProperty().bind(reportShown.not());
        MenuItem importExcel = new MenuItem("Import Verdicts from Excel…");
        importExcel.setOnAction(e -> importExcel());
        importExcel.disableProperty().bind(reportShown.not());
        MenuItem sql = new MenuItem("Show queries…");
        sql.setOnAction(e -> showQueries());
        MenuButton more = new MenuButton("⋯", null, saveAs, html, new SeparatorMenuItem(), excel, importExcel,
                new SeparatorMenuItem(), sql);
        more.setTooltip(new Tooltip("Save As, Export HTML, Excel to decide verdicts in, the queries each side runs"));

        // the sides on the left, the options and actions beside them
        Label rowsLabel = new Label("Rows");
        HBox optionsTop = new HBox(6, where, rowsLabel, rowLimit);
        optionsTop.setAlignment(Pos.CENTER_LEFT);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox optionsBottom = new HBox(6, oneToMany, ignoreLineEndings, addJoin, gap, compare, saveButton, more);
        CompareSide.keepWidth(rowsLabel, rowLimit, oneToMany, ignoreLineEndings, addJoin, compare, saveButton, more);
        optionsBottom.setAlignment(Pos.CENTER_LEFT);
        VBox sideBoxes = new VBox(4, dataSource, dataTarget);
        VBox options = new VBox(4, optionsTop, optionsBottom);
        HBox.setHgrow(options, Priority.ALWAYS);
        HBox settings = new HBox(8, sideBoxes, swap, new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL), options);
        settings.setAlignment(Pos.CENTER_LEFT);
        dataStatus.setWrapText(true);
        dataStatus.getStyleClass().add("hint-small");
        dataStatus.managedProperty().bind(dataStatus.textProperty().isNotEmpty());
        dataStatus.visibleProperty().bind(dataStatus.managedProperty());
        VBox top = new VBox(6, settings, joins, dataStatus);
        top.setPadding(new Insets(6, 8, 6, 8));
        top.getStyleClass().add("compare-settings");

        dataGrid.setPlaceholder(new Label("Pick the table on both sides, tick the Key column(s), then Compare."));
        // the results bar: column picker toggle · status filters (also the legend) · verdicts
        columnsToggle.setText("◧");
        columnsToggle.setTooltip(new Tooltip("Show or hide the column picker"));
        statusBar.getChildren().addAll(columnsToggle, new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL));
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
        // with rows selected: I U D S set a verdict, Backspace clears it, Ctrl+V pastes a copied one
        dataGrid.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::verdictKeys);
        verdictForShown.setText("Verdict");
        verdictForShown.setTooltip(new Tooltip("Suggest verdicts, or set one on every row shown now (narrow them with the status boxes)."
                + "\nSelected rows: right-click, or the keys I U D S (Backspace clears)."));
        Button generate = new Button("Generate SQL…");
        generate.setTooltip(new Tooltip("A PostgreSQL script for the target from the verdicts: INSERT / UPDATE / DELETE, ids found "
                + "again by the joined columns (e.g. product_id by mpv.product_code), parents first, safe to run again"));
        generate.setOnAction(e -> generateSql());
        verdictSummary.getStyleClass().add("hint-small");
        // the verdict tools stay together; in a narrow bar they go to a second line
        HBox verdicts = new HBox(8, new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL), verdictForShown,
                generate, verdictSummary);
        verdicts.setAlignment(Pos.CENTER_LEFT);
        statusBar.getChildren().add(verdicts);
        CompareSide.keepWidth(columnsToggle, verdictForShown, generate);
        statusFilters.values().forEach(CompareSide::keepWidth);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 6, 4, 6));
        statusBar.visibleProperty().bind(reportShown);
        statusBar.managedProperty().bind(reportShown);
        // status cells take their colors from the theme settings
        Themes.shownSettings().addListener((o, a, b) -> dataGrid.refresh());
        BorderPane report = new BorderPane(dataView);
        report.setTop(statusBar);
        Node columns = picker;
        SplitPane split = new SplitPane(columns, report);
        CompareSide.fixedFirstPane(split, 300); // a wider window widens the report, not the picker
        // hide the column picker to give the report the whole width; showing it again restores its size
        columnsToggle.setSelected(true);
        double[] divider = {0.25};
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

    /** Lists the columns of both tables once both are chosen, with the joined tables' columns (see {@link #setColumns}). */
    private void loadColumns() {
        TableRef t1 = dataSource.table.getValue();
        TableRef t2 = dataTarget.table.getValue();
        if (t1 == null || t2 == null || dataSource.session == null || dataTarget.session == null) return;
        DatabaseSession s1 = dataSource.session;
        DatabaseSession s2 = dataTarget.session;
        // joined tables: picked from the source schema, found by name in the target schema
        List<JoinEditor.Ready> ready = joins.tablesListed();
        List<String> aliases = ready.stream().map(JoinEditor.Ready::alias).toList();
        List<TableRef> srcJoins = ready.stream().map(JoinEditor.Ready::table).toList();
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

    /** Fills the column picker (ticks made for the same names are kept), then maybe compares a reopened file. */
    private void setColumns(List<String> srcCols, List<String> dstCols) {
        picker.setColumns(srcCols, dstCols);
        maybeAutoCompare();
    }

    /** What a compare reads, taken from the form. */
    private record Plan(List<String> keys, List<String> compared, List<String> shownOnly, String filter,
                        List<String> joinedColumns, List<KeyJoins.Join> joins, String alias,
                        List<TableRef> srcJoins, List<TableRef> dstJoins, int limit, boolean oneToMany, boolean ignoreLineEndings) {
    }

    /** The form as a {@link Plan}; null (with the reason in the status line) when something is missing. */
    private Plan plan() {
        if (!dataSource.readyWithTable() || !dataTarget.readyWithTable()) {
            dataStatus.setText("Pick a connection, schema and table on both sides.");
            return null;
        }
        List<String> keys = picker.keys();
        List<String> compared = picker.compared();
        List<String> shownOnly = picker.shownOnly();
        if (keys.isEmpty()) {
            dataStatus.setText("Tick Key for the column(s) that identify a row (e.g. id).");
            return null;
        }
        String filter = where.getText() == null ? "" : where.getText().trim();
        if (!filter.isEmpty() && !isPlainCondition(filter, dataSource.connection.getValue().getType())) {
            dataStatus.setText("The Where filter must be one read-only condition (no ';', INSERT, UPDATE, DELETE, ...).");
            return null;
        }
        // joins only matter when a joined column is used (as key, compared or shown)
        List<String> joinedColumns = java.util.stream.Stream.of(keys, compared, shownOnly).flatMap(List::stream)
                .filter(KeyJoins::isJoinedColumn).distinct().toList();
        List<KeyJoins.Join> joinList = joinedColumns.isEmpty() ? List.of() : joins.joins();
        String alias = joins.mainAlias();
        String joinProblem = joinList.isEmpty() ? null : KeyJoins.problem(alias, joinList);
        if (joinProblem != null) {
            dataStatus.setText(joinProblem);
            return null;
        }
        List<TableRef> srcJoins = joinList.isEmpty() ? List.of() : joins.tables();
        List<TableRef> dstJoins = new ArrayList<>();
        for (KeyJoins.Join j : joinList) {
            TableRef t = dataTarget.table.getItems().stream().filter(x -> x.name().equalsIgnoreCase(j.table())).findFirst().orElse(null);
            if (t == null) {
                dataStatus.setText(j.table() + " is not in the target schema " + dataTarget.schemaLabel() + ".");
                return null;
            }
            dstJoins.add(t);
        }
        return new Plan(keys, compared, shownOnly, filter, joinedColumns, joinList, alias, srcJoins, dstJoins, rowLimit.getValue(),
                oneToMany.isSelected(), ignoreLineEndings.isSelected());
    }

    /**
     * Compare (Ctrl+Enter): reads both sides as a {@link Job}, each on a connection of its own (so the navigator and
     * scripts keep working), at the same time; while it runs the button is Cancel.
     */
    private void runDataCompare() {
        if (running != null) return;
        Plan p = plan();
        if (p == null) return;
        String src = dataSource.label();
        String dst = dataTarget.label();
        TableRef t1 = dataSource.table.getValue();
        TableRef t2 = dataTarget.table.getValue();
        DataCompareReport.Side srcSide = new DataCompareReport.Side(dataSource.connection.getValue().getName(),
                dataSource.schemaLabel(), t1.name(), src);
        DataCompareReport.Side dstSide = new DataCompareReport.Side(dataTarget.connection.getValue().getName(),
                dataTarget.schemaLabel(), t2.name(), dst);
        dataGrid.getItems().clear();
        compareButton.setText("Cancel");
        running = Job.start(List.of(dataSource.connection.getValue(), dataTarget.connection.getValue()), DatabaseSession::open,
                Async.background(), Platform::runLater, (sessions, job) -> {
                    DatabaseSession a = sessions.get(0);
                    DatabaseSession b = sessions.get(1);
                    job.progress("Reading " + t1.name() + " from " + src + " and " + dst + "…");
                    Future<StatementResult> readA = a.submit(() -> a.execute(sideSql(a, t1, p.srcJoins(), p), p.limit()));
                    Future<StatementResult> readB = b.submit(() -> b.execute(sideSql(b, t2, p.dstJoins(), p), p.limit()));
                    StatementResult ra = readA.get();
                    StatementResult rb = readB.get();
                    job.checkCancelled();
                    job.progress("Comparing " + ra.data().rows().size() + " and " + rb.data().rows().size() + " rows…");
                    return DataCompareReport.of(DataCompare.compare(ra.data(), rb.data(), p.keys(), p.compared(), p.shownOnly(),
                                    MAX_LISTED_ROWS, p.oneToMany(), p.ignoreLineEndings()), srcSide, dstSide, p.keys(), p.filter(), p.limit(),
                            ra.truncated() || rb.truncated(), "v" + AppFooter.version());
                }, new Job.Listener<>() {
                    @Override
                    public void progress(String text) {
                        dataStatus.setText(text);
                    }

                    @Override
                    public void done(DataCompareReport r) {
                        jobEnded();
                        // the verdicts of the results shown so far go on the same rows of the new ones
                        DataCompareReport earlier = report != null && !report.settingsOnly ? report : null;
                        Verdicts.Carried carried = earlier == null ? null : Verdicts.carryOver(earlier, r);
                        r.oneToMany = p.oneToMany();
                        r.ignoreLineEndings = p.ignoreLineEndings();
                        r.mainAlias = p.joins().isEmpty() ? null : p.alias();
                        r.joins = p.joins();
                        compareFile.compared();
                        showReport(r);
                        if (carried != null && carried.any()) dataStatus.setText(carriedText(carried));
                    }

                    @Override
                    public void failed(Throwable error) {
                        jobEnded();
                        Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null
                                ? error.getCause() : error;
                        dataStatus.setText("Could not compare: " + cause.getMessage());
                    }

                    @Override
                    public void cancelled() {
                        jobEnded();
                        dataStatus.setText("Compare cancelled");
                    }
                });
    }

    /** The compare running now (null when none): the Compare button cancels it. */
    private Job<DataCompareReport> running;

    private void jobEnded() {
        running = null;
        compareButton.setText("Compare");
    }

    /** The Compare button: compares, or cancels the compare that is running. */
    private void compareOrCancel() {
        if (running != null) running.cancel();
        else runDataCompare();
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
            Alert none = new Alert(Alert.AlertType.INFORMATION, "Set a verdict on the rows to change first: right-click rows, "
                    + "Suggest verdicts, or Import Verdicts from Excel. Rows with SKIP or no verdict are left out of the script.", ButtonType.OK);
            none.initOwner(getTabPane().getScene().getWindow());
            none.setHeaderText("No row has an INSERT, UPDATE or DELETE verdict yet");
            InAppDialogs.showAndWait(none);
            return;
        }
        if (!confirmUndecided(r)) return;
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
        String file = compareFile.file() == null ? null : compareFile.file().getFileName().toString();
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

    /**
     * Rows that could get a verdict but have none are left out of the script: say how many of each status, before
     * generating. Review first (the default) shows only the statuses that have such rows; true: generate anyway.
     */
    private boolean confirmUndecided(DataCompareReport r) {
        Map<DataCompare.RowStatus, Long> open = new java.util.EnumMap<>(DataCompare.RowStatus.class);
        for (DataCompareReport.Row row : r.rows) {
            if (row.verdict == null && !Verdict.allowed(row.status).isEmpty()) open.merge(row.status, 1L, Long::sum);
        }
        long check = r.rows.stream().filter(row -> row.warning != null && row.verdict != null).count();
        if (open.isEmpty() && check == 0) return true;
        long total = open.values().stream().mapToLong(Long::longValue).sum();
        StringBuilder text = new StringBuilder();
        open.forEach((status, n) -> text.append("• ").append(n).append(switch (status) {
            case CHANGED -> " changed (UPDATE or SKIP)";
            case ONLY_SOURCE -> " only in " + r.source.label() + " (INSERT or SKIP)";
            case ONLY_TARGET -> " only in " + r.target.label() + " (DELETE or SKIP)";
            default -> " " + status.name();
        }).append('\n'));
        if (!open.isEmpty()) text.append("\nThey are left out of the script, as if they had SKIP.");
        if (check > 0) {
            text.append(open.isEmpty() ? "" : "\n\n").append("⚠ ").append(check)
                    .append(" verdict(s) kept from an earlier compare whose values or status changed: check them (Note column).");
        }
        ButtonType review = new ButtonType("Review first", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType generate = new ButtonType("Generate anyway", ButtonBar.ButtonData.OK_DONE);
        Alert ask = new Alert(Alert.AlertType.WARNING, text.toString(), generate, review);
        ask.initOwner(getTabPane().getScene().getWindow());
        ask.setHeaderText(total > 0 ? total + " row(s) have no verdict yet" : check + " verdict(s) to check");
        ((Button) ask.getDialogPane().lookupButton(generate)).setDefaultButton(false);
        ((Button) ask.getDialogPane().lookupButton(review)).setDefaultButton(true);
        if (InAppDialogs.showAndWait(ask).orElse(review) == generate) return true;
        // review: only the statuses that still have rows to decide
        java.util.Set<DataCompare.RowStatus> shown = new java.util.HashSet<>(open.keySet());
        r.rows.stream().filter(row -> row.warning != null).forEach(row -> shown.add(row.status));
        statusFilters.forEach((status, box) -> box.setSelected(shown.contains(status)));
        dataStatus.setText((total > 0 ? total + " row(s) to decide" : "") + (total > 0 && check > 0 ? " · " : "")
                + (check > 0 ? "⚠ " + check + " to check" : "") + ": set their verdict (SKIP to leave one as it is), then Generate SQL again");
        return false;
    }

    /** The filter has to stay a condition of one SELECT: one statement (in the source database's syntax), nothing that writes. */
    private static boolean isPlainCondition(String condition, com.dbadmin.model.DbType type) {
        String sql = "select * from t where (" + condition + ")";
        return SqlSplitter.split(sql, type).size() == 1 && SqlSplitter.isReadOnlyQuery(sql);
    }

    /** Shows a report: the summary above, one grid row per listed row with its verdict. */
    private void showReport(DataCompareReport r) {
        report = r;
        String src = r.source.label();
        String dst = r.target.label();
        StringBuilder sb = new StringBuilder();
        // the counts per status are on the filter boxes
        sb.append(r.sourceRows).append(" rows in ").append(src).append(" · ").append(r.targetRows).append(" rows in ").append(dst);
        if (compareFile.file() != null) sb.append(" · ").append(compareFile.file().getFileName());
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
                row.add(new ComparedValue(a, b, d.source != null, d.target != null, src, dst, isCompared, r.ignoreLineEndings));
            }
            String note = d.status == DataCompare.RowStatus.CHANGED || d.status == DataCompare.RowStatus.MATCH ? ""
                    : d.note.replace("in source", "in " + src).replace("in target", "in " + dst);
            // a verdict from the earlier compare that needs a look comes first
            row.add(d.warning == null ? note : "⚠ " + d.warning + (note.isEmpty() ? "" : " · " + note));
            reportRows.add(Map.entry(d.status, row));
        }
        statusFilters.forEach((status, box) -> {
            int n = counts.getOrDefault(status, 0);
            box.setText(statusLabel(status, src, dst) + " " + n);
            // duplicate keys are a warning: only listed when there are some
            boolean listed = status != DataCompare.RowStatus.DUPLICATE_KEY || n > 0;
            box.setVisible(listed);
            box.setManaged(listed);
        });
        MenuItem suggest = new MenuItem("Suggest verdicts");
        suggest.setOnAction(e -> suggestVerdicts());
        javafx.scene.control.Menu forShown = new javafx.scene.control.Menu("Set on all shown rows");
        forShown.getItems().setAll(verdictItems(v -> applyVerdict(v, List.copyOf(dataGrid.getItems()))));
        verdictForShown.getItems().setAll(suggest, forShown);
        reportShown.set(true);
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

    /** Every row without a verdict gets the usual one for its status (see {@link Verdicts#suggest}). */
    private void suggestVerdicts() {
        if (report == null) return;
        int set = Verdicts.suggest(rowsOf(reportRows.stream().map(Map.Entry::getValue).toList()));
        verdictsChanged(set);
        dataStatus.setText(set + " verdict(s) suggested; rows already decided were kept" + (compareFile.hasUnsavedEdits() ? " · not saved" : ""));
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
     * copied verdicts onto as many selected rows go one by one (see {@link Verdicts#paste}).
     */
    private void pasteVerdicts() {
        List<List<Object>> rows = dataGrid.getSelectionModel().getSelectedCells().stream()
                .map(c -> c.getRow()).distinct().sorted().map(i -> dataGrid.getItems().get(i)).toList();
        Verdicts.Applied done;
        try {
            done = Verdicts.paste(javafx.scene.input.Clipboard.getSystemClipboard().getString(), rowsOf(rows));
        } catch (IllegalArgumentException e) {
            dataStatus.setText(e.getMessage());
            return;
        }
        verdictsChanged(done.changed());
        dataStatus.setText(done.changed() + " verdict(s) pasted" + (done.skipped() > 0 ? "; " + done.skipped()
                + " row(s) left alone (not a verdict for their status)" : "") + (compareFile.hasUnsavedEdits() ? " · not saved" : ""));
    }

    /** Sets (null: clears) the verdict of the given grid rows where it fits their status; other rows are left alone. */
    private void applyVerdict(Verdict v, Collection<List<Object>> rows) {
        Verdicts.Applied done = Verdicts.apply(v, rowsOf(rows));
        verdictsChanged(done.changed());
        dataStatus.setText(done.changed() + " verdict(s) set" + (done.skipped() > 0 ? "; " + done.skipped() + " row(s) left alone ("
                + (v == null ? "" : v.label()) + " does not fit their status)" : "") + (compareFile.hasUnsavedEdits() ? " · not saved" : ""));
    }

    /** The report rows behind grid rows (their Verdict cell holds the row). */
    private static List<DataCompareReport.Row> rowsOf(Collection<List<Object>> gridRows) {
        return gridRows.stream().map(row -> ((VerdictCell) row.get(1)).row).toList();
    }

    /** After verdicts were set: the dirty marker, the grid and the summary follow. */
    private void verdictsChanged(int changed) {
        if (changed > 0) compareFile.edited(true);
        dataGrid.refresh();
        updateVerdictSummary();
    }

    private void updateVerdictSummary() {
        if (report != null) verdictSummary.setText(Verdicts.summary(report));
    }

    // ---- saving --------------------------------------------------------------

    /** The rows as an Excel sheet whose Verdict column offers the verdicts that fit each row (see CompareExcel). */
    private void exportExcel() {
        if (report == null) return;
        Path file = CompareReportChooser.saveExcel(getTabPane().getScene().getWindow(), compareFile.file(), CompareReportChooser.defaultName(
                "DataCompare-" + report.source.table(), report.source.label(), report.target.label()));
        if (file == null) return;
        try (var out = Files.newOutputStream(file)) {
            CompareExcel.write(report, out);
            dataStatus.setText("Excel saved as " + file + " · pick verdicts in its Verdict column, then Import Verdicts from Excel");
        } catch (IOException e) {
            error("Could not save the Excel file", e);
        }
    }

    /** Verdicts from a sheet made by Export Excel: by key and status; what could not be used is listed. */
    private void importExcel() {
        if (report == null) return;
        Path file = CompareReportChooser.openExcel(getTabPane().getScene().getWindow(), compareFile.file());
        if (file == null) return;
        CompareExcel.Imported done;
        try (var in = Files.newInputStream(file)) {
            done = CompareExcel.read(report, com.dbadmin.service.transfer.Xlsx.read(in));
        } catch (IOException | IllegalArgumentException e) {
            error("Could not read " + file.getFileName(), e instanceof IOException io ? io : new IOException(e.getMessage(), e));
            return;
        }
        verdictsChanged(done.changed());
        String summary = done.changed() + " verdict(s) set or cleared, " + done.unchanged() + " already so"
                + (done.problems().isEmpty() ? "" : ", " + done.problems().size() + " row(s) left alone");
        dataStatus.setText(summary + (compareFile.hasUnsavedEdits() ? " · not saved" : ""));
        if (!done.problems().isEmpty()) {
            TextArea list = new TextArea(String.join("\n", done.problems()));
            list.setEditable(false);
            list.setWrapText(true);
            list.setPrefRowCount(Math.min(12, done.problems().size() + 1));
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "", ButtonType.OK);
            alert.initOwner(getTabPane().getScene().getWindow());
            alert.setHeaderText(summary);
            alert.getDialogPane().setContent(list);
            alert.getDialogPane().setPrefWidth(620);
            InAppDialogs.showAndWait(alert);
        }
    }

    private void exportHtml() {
        if (report == null) return;
        Path file = CompareReportChooser.saveHtml(getTabPane().getScene().getWindow(), compareFile.file(), CompareReportChooser.defaultName(
                "DataCompare-" + report.source.table(), report.source.label(), report.target.label()));
        if (file == null) return;
        try {
            Files.writeString(file, CompareHtml.data(report), StandardCharsets.UTF_8);
            dataStatus.setText("HTML saved as " + file);
        } catch (IOException e) {
            error("Could not save the HTML report", e);
        }
    }

    /** Asks about unsaved changes before the tab (or the app) closes. Returns false if the user cancelled. */
    @Override
    public boolean confirmClose() {
        return compareFile.confirmClose(this::save);
    }

    /** Asks before unsaved verdicts are lost (closing, or a new compare replacing the report). */
    /** "8 verdicts kept · ⚠ 2 to check · ⚠ 1 no longer fit · 1 row gone": what a new compare did to the verdicts. */
    private static String carriedText(Verdicts.Carried c) {
        List<String> parts = new ArrayList<>();
        if (c.kept() > 0) parts.add(c.kept() + " verdict(s) kept");
        if (c.toCheck() > 0) parts.add("⚠ " + c.toCheck() + " kept, to check (values or status changed)");
        if (c.notFitting() > 0) parts.add("⚠ " + c.notFitting() + " no longer fit: see the Note column");
        if (c.gone() > 0) parts.add(c.gone() + " decided row(s) no longer listed");
        return "Compared again · " + String.join(" · ", parts);
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
