package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableData;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.Job;
import com.dbadmin.service.scan.DataScan;
import com.dbadmin.service.scan.ScanColumn;
import com.dbadmin.service.scan.ScanFinding;
import com.dbadmin.service.scan.ScanRequest;
import com.dbadmin.service.scan.ScanTable;
import com.dbadmin.service.scan.TableResult;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scan Data: finds values in every table and column of a connection (todo #1, see docs/scan-plan.md). The form picks
 * the values, schemas, tables and columns; the scan runs as a {@link Job} on a connection of its own and lists
 * findings as each table is done; Coverage lists everything that was not searched, and why. Nothing is changed.
 */
class ScanTab extends Tab {

    /** A schema, table or column in a picker, and whether it is ticked. */
    record Pick<T>(T item, String label, BooleanProperty on) {
        Pick(T item, String label, boolean ticked) {
            this(item, label, new SimpleBooleanProperty(ticked));
        }
    }

    /** A column of a table in the columns list; shown while its table is ticked. */
    record ColumnPick(Pick<ScanTable> table, ScanColumn column, BooleanProperty on) {
        String label() {
            return table.item().table().name() + "." + column.name() + "   " + column.type();
        }
    }

    private final SessionProvider sessions;
    private final ComboBox<ConnectionConfig> connection = new ComboBox<>();
    private final TextArea values = new TextArea();
    private final ComboBox<String> match = new ComboBox<>();
    private final CheckBox ignoreCase = new CheckBox("Ignore case");
    private final TextField timeout = new TextField("60");
    private final TextField bigRows = new TextField("1000000");
    private final CheckList<Pick<Namespace>> schemas = new CheckList<>(Pick::on, Pick::label);
    /** Labelled when shown: the label depends on the big-table limit and on how many schemas are ticked. */
    private final CheckList<Pick<ScanTable>> tables = new CheckList<>(Pick::on, p -> tableLabel(p.item()));
    private final CheckList<ColumnPick> columns = new CheckList<>(ColumnPick::on, ColumnPick::label);
    private final Label tableCount = new Label();
    private final Label columnCount = new Label();
    private final Button scanButton = new Button("Scan");
    private final Label status = new Label();
    private final DataGrid findings = new DataGrid();
    private final DataGrid coverage = new DataGrid();
    private final Tab coverageTab = new Tab("Coverage");
    private final Button export = new Button("Export CSV…");
    private Job<List<TableResult>> running;
    /** The request of the last scan, for the findings' value headers and the coverage. */
    private ScanRequest lastRequest;
    private final List<TableResult> results = new ArrayList<>();

    ScanTab(ObservableList<ConnectionConfig> connections, SessionProvider sessions, ConnectionConfig initial) {
        super("Scan Data");
        this.sessions = sessions;
        connection.setItems(connections);
        connection.setPromptText("connection…");
        connection.valueProperty().addListener((o, a, c) -> loadSchemas());
        values.setPromptText("Values to find, one per line, exactly as stored (e.g. https://…/Facebook.png)");
        values.setPrefRowCount(4);
        values.setWrapText(false);
        match.getItems().addAll("contains", "whole value");
        match.setValue("contains");
        match.setTooltip(new Tooltip("contains: the value is part of the cell; whole value: the cell is the value"));
        ignoreCase.setSelected(true);
        ignoreCase.setTooltip(new Tooltip("Finds Facebook.png as facebook.png too. A replace changes only the case typed."));
        timeout.setPrefColumnCount(4);
        timeout.setTooltip(new Tooltip("A table that takes longer is left out and listed under Coverage"));
        bigRows.setPrefColumnCount(8);
        bigRows.setTooltip(new Tooltip("Tables with more rows (as the database estimates) are marked ⚠ and left out of All"));
        bigRows.textProperty().addListener((o, a, b) -> relabelTables());

        columns.setVisible(c -> c.table().on().get());

        Button allTables = new Button("All");
        allTables.setTooltip(new Tooltip("Ticks the tables shown, except big ones (⚠): tick those one by one"));
        allTables.setOnAction(e -> tables.shown().forEach(t -> t.on().set(!isBig(t.item()))));
        Button noTables = new Button("None");
        noTables.setOnAction(e -> tables.shown().forEach(t -> t.on().set(false)));
        Button allColumns = new Button("All");
        allColumns.setOnAction(e -> columns.shown().forEach(c -> c.on().set(true)));
        Button noColumns = new Button("None");
        noColumns.setOnAction(e -> columns.shown().forEach(c -> c.on().set(false)));
        tableCount.getStyleClass().add("hint-small");
        columnCount.getStyleClass().add("hint-small");

        scanButton.getStyleClass().add("default-look");
        scanButton.setOnAction(e -> {
            if (running != null) running.cancel();
            else scan();
        });
        status.getStyleClass().add("hint-small");
        export.setOnAction(e -> exportFindings());
        export.setDisable(true);

        HBox top = new HBox(8, new Label("Connection"), connection, new Label("Match"), match, ignoreCase,
                new Label("Timeout per table"), timeout, new Label("s"), new Label("Big table over"), bigRows, new Label("rows"));
        top.setAlignment(Pos.CENTER_LEFT);
        schemas.listView().setPrefHeight(90);
        VBox schemaBox = box("Schemas", schemas, new HBox());
        VBox tableBox = box("Tables", tables, new HBox(6, allTables, noTables, tableCount));
        VBox columnBox = box("Columns", columns, new HBox(6, allColumns, noColumns, columnCount));
        VBox valueBox = new VBox(4, new Label("Find"), values);
        VBox.setVgrow(values, Priority.ALWAYS);
        HBox pickers = new HBox(8, valueBox, schemaBox, tableBox, columnBox);
        HBox.setHgrow(valueBox, Priority.ALWAYS);
        HBox.setHgrow(tableBox, Priority.ALWAYS);
        HBox.setHgrow(columnBox, Priority.ALWAYS);
        valueBox.setPrefWidth(320);
        schemaBox.setPrefWidth(160);
        tableBox.setPrefWidth(260);
        columnBox.setPrefWidth(300);
        VBox.setVgrow(pickers, Priority.ALWAYS);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox actions = new HBox(8, scanButton, status, gap, export);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox form = new VBox(8, top, pickers, actions);
        form.setPadding(new Insets(8));

        findings.setPlaceholder(new Label("Findings appear here as each table is scanned."));
        coverage.setPlaceholder(new Label("After a scan: what was not searched, and why."));
        Tab findingsTab = new Tab("Findings", findings);
        coverageTab.setContent(coverage);
        TabPane bottom = new TabPane(findingsTab, coverageTab);
        bottom.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        bottom.getStyleClass().add("sub-tabs");
        SplitPane split = new SplitPane(form, bottom);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.5);
        setContent(new BorderPane(split));
        setOnClosed(e -> {
            if (running != null) running.cancel();
        });
        if (initial != null) connection.setValue(initial);
    }

    private static VBox box(String title, CheckList<?> list, HBox buttons) {
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(4, new Label(title), list, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);
        return box;
    }

    // for tests: the parts a user works with
    TextArea valuesArea() {
        return values;
    }

    CheckList<Pick<ScanTable>> tableList() {
        return tables;
    }

    CheckList<ColumnPick> columnList() {
        return columns;
    }

    DataGrid findingsGrid() {
        return findings;
    }

    DataGrid coverageGrid() {
        return coverage;
    }

    Button scanButton() {
        return scanButton;
    }

    // ---- what to search ----

    private void loadSchemas() {
        ConnectionConfig c = connection.getValue();
        schemas.setItems(List.of());
        tables.setItems(List.of());
        columns.setItems(List.of());
        updateCounts();
        if (c == null) return;
        setText("Scan · " + c.getName());
        status.setText("Reading schemas…");
        sessions.withSession(c, s -> Async.run(s, s::listNamespaces, all -> {
            List<Pick<Namespace>> picks = all.stream().filter(ns -> !ns.isSystem(c.getType()))
                    .map(ns -> new Pick<>(ns, ns.label(), true)).toList();
            picks.forEach(p -> p.on().addListener((o, a, b) -> loadTables()));
            schemas.setItems(picks);
            loadTables();
        }, ex -> status.setText("Could not read the schemas: " + ex.getMessage())), ex -> status.setText("Could not connect: " + ex.getMessage()));
    }

    /** The tables and columns of the ticked schemas; ticks of tables and columns listed before are kept. */
    private void loadTables() {
        ConnectionConfig c = connection.getValue();
        List<Namespace> picked = schemas.items().stream().filter(p -> p.on().get()).map(Pick::item).toList();
        if (c == null) return;
        Map<String, Boolean> tableTicks = new HashMap<>();
        tables.items().forEach(t -> tableTicks.put(t.item().table().displayName(), t.on().get()));
        Map<String, Boolean> columnTicks = new HashMap<>();
        columns.items().forEach(col -> columnTicks.put(col.table().item().table().displayName() + "|" + col.column().name(), col.on().get()));
        status.setText("Reading tables…");
        sessions.withSession(c, s -> Async.run(s, () -> DataScan.candidates(s, picked), found -> {
            List<Pick<ScanTable>> tablePicks = new ArrayList<>();
            List<ColumnPick> columnPicks = new ArrayList<>();
            for (ScanTable t : found) {
                String key = t.table().displayName();
                Pick<ScanTable> tp = new Pick<>(t, tableLabel(t), tableTicks.getOrDefault(key, !isBig(t)));
                tp.on().addListener((o, a, b) -> {
                    columns.refilter();
                    updateCounts();
                });
                tablePicks.add(tp);
                for (ScanColumn col : t.searchableColumns()) {
                    ColumnPick cp = new ColumnPick(tp, col, new SimpleBooleanProperty(columnTicks.getOrDefault(key + "|" + col.name(), true)));
                    cp.on().addListener((o, a, b) -> updateCounts());
                    columnPicks.add(cp);
                }
            }
            tables.setItems(tablePicks);
            columns.setItems(columnPicks);
            columns.refilter();
            updateCounts();
            status.setText("");
        }, ex -> status.setText("Could not read the tables: " + ex.getMessage())), ex -> status.setText("Could not connect: " + ex.getMessage()));
    }

    private long bigLimit() {
        try {
            return Long.parseLong(bigRows.getText().trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private boolean isBig(ScanTable t) {
        return t.isBig(bigLimit());
    }

    /** "orders" or, with several schemas, "app.orders"; a big table with ⚠ and its estimated size. */
    private String tableLabel(ScanTable t) {
        String name = schemas.items().stream().filter(p -> p.on().get()).count() > 1 ? t.table().displayName() : t.table().name();
        return isBig(t) ? name + "   ⚠ " + String.format("%,d", t.estimatedRows()) + " rows" : name;
    }

    private void relabelTables() {
        tables.listView().refresh();
    }

    private void updateCounts() {
        long t = tables.items().stream().filter(p -> p.on().get()).count();
        tableCount.setText(t + " of " + tables.items().size() + " tables");
        long c = columns.items().stream().filter(p -> p.table().on().get() && p.on().get()).count();
        long shown = columns.items().stream().filter(p -> p.table().on().get()).count();
        columnCount.setText(c + " of " + shown + " columns");
    }

    // ---- scanning ----

    /** The request the form describes now. */
    ScanRequest request() {
        List<ScanRequest.Target> targets = new ArrayList<>();
        for (Pick<ScanTable> t : tables.items()) {
            if (!t.on().get()) continue;
            List<ScanColumn> picked = columns.items().stream().filter(c -> c.table() == t && c.on().get()).map(ColumnPick::column).toList();
            if (!picked.isEmpty()) targets.add(new ScanRequest.Target(t.item().table(), picked));
        }
        int seconds;
        try {
            seconds = Math.max(0, Integer.parseInt(timeout.getText().trim()));
        } catch (NumberFormatException e) {
            seconds = 60;
        }
        return new ScanRequest(ScanRequest.lines(values.getText()), "whole value".equals(match.getValue()), ignoreCase.isSelected(), targets, seconds);
    }

    private void scan() {
        ConnectionConfig c = connection.getValue();
        ScanRequest request = request();
        if (c == null || request.values().isEmpty() || request.targets().isEmpty()) {
            status.setText(c == null ? "Pick a connection." : request.values().isEmpty() ? "Type a value to find." : "Tick a table and a column.");
            return;
        }
        lastRequest = request;
        results.clear();
        showFindings();
        coverage.getItems().clear();
        coverageTab.setText("Coverage");
        scanButton.setText("Cancel");
        export.setDisable(true);
        long started = System.nanoTime();
        running = Job.start(List.of(c), DatabaseSession::open, Async.background(), Platform::runLater,
                (ss, job) -> DataScan.run(ss.get(0), request, job, r -> Platform.runLater(() -> {
                    results.add(r);
                    showFindings();
                })), new Job.Listener<>() {
                    @Override
                    public void progress(String text) {
                        status.setText(text);
                    }

                    @Override
                    public void done(List<TableResult> all) {
                        ended("Done in " + seconds(started) + " · " + summary());
                    }

                    @Override
                    public void failed(Throwable error) {
                        ended("Stopped: " + error.getMessage());
                    }

                    @Override
                    public void cancelled() {
                        ended("Cancelled after " + results.size() + " of " + request.targets().size() + " tables · " + summary());
                    }
                });
    }

    private void ended(String text) {
        running = null;
        scanButton.setText("Scan");
        status.setText(text);
        export.setDisable(results.isEmpty());
        showCoverage();
    }

    private static String seconds(long startedNanos) {
        return String.format(Locale.ROOT, "%.1f s", (System.nanoTime() - startedNanos) / 1e9);
    }

    private String summary() {
        long found = results.stream().mapToLong(r -> r.findings().size()).sum();
        long notScanned = results.stream().filter(r -> !r.scanned()).count();
        return found + " column(s) hold a value" + (notScanned > 0 ? " · " + notScanned + " table(s) not scanned (Coverage)" : "");
    }

    /** One row per finding: table, column, type, rows, per value; and ignoring case, the rows in another case only. */
    TableData findingsData() {
        List<String> headers = new ArrayList<>(List.of("Table", "Column", "Type", "Rows"));
        List<String> vals = lastRequest == null ? List.of() : lastRequest.values();
        boolean ic = lastRequest != null && lastRequest.ignoreCase();
        for (int i = 0; i < vals.size(); i++) headers.add(valueHeader(i, vals.get(i)));
        if (ic) headers.add("Other case only");
        List<List<Object>> rows = new ArrayList<>();
        for (TableResult r : results) {
            for (ScanFinding f : r.findings()) {
                List<Object> row = new ArrayList<>(List.of(f.table().displayName(), f.column().name(), f.column().type(), f.rows()));
                row.addAll(f.valueRows());
                if (ic) {
                    long other = 0;
                    for (int i = 0; i < vals.size(); i++) other += f.valueRows().get(i) - f.exactRows().get(i);
                    row.add(other);
                }
                rows.add(row);
            }
        }
        return new TableData(headers, rows);
    }

    private static String valueHeader(int i, String v) {
        String shortV = v.length() > 28 ? "…" + v.substring(v.length() - 27) : v;
        return (i + 1) + ": " + shortV;
    }

    private void showFindings() {
        findings.show(findingsData());
    }

    /**
     * Everything not searched, and why: tables that failed or timed out, tables and columns left out by the user
     * (big tables included), and columns whose type is not searched.
     */
    TableData coverageData() {
        List<List<Object>> rows = new ArrayList<>();
        for (TableResult r : results) {
            if (!r.scanned()) rows.add(List.of(r.table().displayName(), "(table)", "not scanned: " + r.notScanned()));
        }
        int done = results.size();
        int planned = lastRequest == null ? 0 : lastRequest.targets().size();
        if (lastRequest != null) {
            for (ScanRequest.Target t : lastRequest.targets().subList(Math.min(done, planned), planned)) {
                rows.add(List.of(t.table().displayName(), "(table)", "not scanned: the scan was stopped first"));
            }
        }
        for (Pick<ScanTable> t : tables.items()) {
            String name = t.item().table().displayName();
            boolean target = lastRequest != null && lastRequest.targets().stream().anyMatch(x -> x.table().equals(t.item().table()));
            if (!target) {
                rows.add(List.of(name, "(table)", isBig(t.item()) && !t.on().get() ? "left out: big table, not ticked" : "left out by you"));
                continue;
            }
            for (ColumnPick c : columns.items()) {
                if (c.table() == t && !c.on().get()) rows.add(List.of(name, c.column().name(), "left out by you"));
            }
            for (ScanColumn c : t.item().columns()) {
                if (!c.searchable()) rows.add(List.of(name, c.name(), "not searched: " + c.type() + " is not text"));
            }
        }
        for (Pick<Namespace> s : schemas.items()) {
            if (!s.on().get()) rows.add(List.of(s.label(), "(schema)", "left out by you"));
        }
        return new TableData(List.of("Table", "Column", "Why"), rows);
    }

    private void showCoverage() {
        TableData data = coverageData();
        coverage.show(data);
        long problems = data.rows().stream().filter(r -> String.valueOf(r.get(2)).startsWith("not scanned")).count();
        coverageTab.setText(problems > 0 ? "Coverage ⚠ " + problems : "Coverage");
    }

    private void exportFindings() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Export findings");
        chooser.setInitialFileName("scan-findings.csv");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File file = chooser.showSaveDialog(getTabPane() == null ? null : getTabPane().getScene().getWindow());
        if (file == null) return;
        TableData data = findingsData();
        try (var out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            var sink = com.dbadmin.service.transfer.Csv.writer(out, ',', true);
            sink.begin(data.columns(), List.of());
            for (List<Object> row : data.rows()) sink.row(row);
            sink.end();
            status.setText("Saved " + file.getName());
        } catch (IOException ex) {
            status.setText("Could not save: " + ex.getMessage());
        }
    }
}
