package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.compare.SchemaCompare.Status;
import com.dbadmin.service.compare.SchemaCompare.TableDiff;
import com.dbadmin.service.compare.SchemaCompare;
import com.dbadmin.service.compare.CompareHtml;
import com.dbadmin.service.compare.CompareReportFiles;
import com.dbadmin.service.compare.SchemaCompareReport;
import com.dbadmin.service.Job;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tools → Compare Table: lists the structural differences of all or some tables of two schemas, e.g.
 * UAT (source) against Prod (target) before a release. Only reads. The result can be saved, reopened
 * later and exported as HTML.
 */
class SchemaCompareTab extends Tab implements UnsavedWork {

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
    /** Filters of the table list by status, with their counts; identical tables (and items) are hidden until ticked. */
    private final Map<Status, javafx.scene.control.ToggleButton> statusFilters = new java.util.EnumMap<>(Status.class);
    private final Button compareDataButton = new Button("Compare data");
    private final Button sqlDiffButton = new Button("SQL diff");
    /** Tables picked for a Table Compare (lower case); empty = all. */
    private Set<String> pickedTables = Set.of();
    private final Button tablesButton = new Button("All tables");
    private String sourceName = "Source";
    private String targetName = "Target";
    /** Save… (Ctrl+S): the in-app dialog asks where, and whether with the results or the settings only. */
    private final Button saveButton = new Button("Save");
    /** Compare, or Cancel while a compare runs. */
    private final Button compareButton = new Button("Compare");
    private final Button saveAsButton = new Button("Save As…");
    static final javafx.scene.input.KeyCombination SAVE_KEY = javafx.scene.input.KeyCombination.keyCombination("Shortcut+S");
    static final javafx.scene.input.KeyCombination SAVE_AS_KEY = javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+S");
    /** Compare again, from anywhere in a compare tab. */
    static final javafx.scene.input.KeyCombination COMPARE_KEY = javafx.scene.input.KeyCombination.keyCombination("Shortcut+Enter");
    /** The compare's file and whether the tab differs from it. */
    private final CompareFile compareFile;

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
        compareFile = new CompareFile(this, saveButton, "Table Compare");
        setOnCloseRequest(e -> {
            if (!confirmClose()) e.consume();
        });
        setOnClosed(e -> {
            if (running != null) running.cancel(); // its connections close with it
        });
        getContent().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (SAVE_AS_KEY.match(e)) {
                e.consume();
                saveAs();
            } else if (SAVE_KEY.match(e)) {
                e.consume();
                save();
            } else if (COMPARE_KEY.match(e)) {
                e.consume();
                runSchemaCompare();
            }
        });
        if (initial != null) schemaSource.connection.setValue(initial);
    }

    private Node schemaPage() {
        Button compare = compareButton;
        compare.getStyleClass().add("default-look");
        compare.setTooltip(new Tooltip("Compare the tables of both schemas (Ctrl+Enter); while it runs: Cancel"));
        compare.setOnAction(e -> {
            if (running != null) running.cancel();
            else runSchemaCompare();
        });
        Button swap = CompareSide.swapButton(schemaSource, schemaTarget);

        diffList.setCellFactory(lv -> new DiffCell());
        diffList.setPlaceholder(new Label("Pick a source and a target, then Compare."));
        diffList.getSelectionModel().selectedItemProperty().addListener((o, a, d) -> showDetails(d));
        detailGrid.setPlaceholder(new Label("Select a table to see its differences."));

        detailTitle.getStyleClass().add("form-title");
        compareDataButton.setTooltip(new Tooltip("Open a Data Compare of this table, with both sides chosen"));
        compareDataButton.setDisable(true);
        compareDataButton.setOnAction(e -> openDataCompare(diffList.getSelectionModel().getSelectedItem()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        sqlDiffButton.setVisible(false);
        sqlDiffButton.setManaged(false);
        HBox detailBar = new HBox(8, detailTitle, spacer, sqlDiffButton, compareDataButton);
        CompareSide.keepWidth(sqlDiffButton, compareDataButton);
        detailBar.setAlignment(Pos.CENTER_LEFT);
        detailBar.setPadding(new Insets(4, 6, 4, 6));
        BorderPane details = new BorderPane(detailView); // the value panel shows long definitions in full
        details.setTop(detailBar);

        // the list's filters: one toggle per status with its count (also the legend)
        HBox filters = new HBox(4);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.setPadding(new Insets(4, 6, 4, 6));
        for (Status status : List.of(Status.CHANGED, Status.ONLY_SOURCE, Status.ONLY_TARGET, Status.SAME)) {
            javafx.scene.control.ToggleButton t = new javafx.scene.control.ToggleButton();
            t.setSelected(status != Status.SAME);
            t.getStyleClass().add("cmp-filter");
            t.selectedProperty().addListener((o, a, b) -> {
                applyFilter();
                showDetails(diffList.getSelectionModel().getSelectedItem());
            });
            CompareSide.keepWidth(t);
            statusFilters.put(status, t);
            filters.getChildren().add(t);
        }
        refreshFilters();
        applyFilter();
        VBox list = new VBox(filters, diffList);
        VBox.setVgrow(diffList, Priority.ALWAYS);
        SplitPane split = new SplitPane(list, details);
        CompareSide.fixedFirstPane(split, 280);

        tablesButton.setOnAction(e -> pickTables());
        tablesButton.setTooltip(new Tooltip("Compare all tables, or only the ones you pick"));
        // a different schema means a different list of tables
        schemaSource.schema.valueProperty().addListener((o, a, b) -> resetPickedTables());
        schemaTarget.schema.valueProperty().addListener((o, a, b) -> resetPickedTables());
        saveButton.setTooltip(new Tooltip("Save this compare to its file (Ctrl+S); the first time, asks where and what"));
        saveButton.setOnAction(e -> save());
        javafx.scene.control.MenuItem saveAs = new javafx.scene.control.MenuItem("Save As…");
        saveAs.setAccelerator(SAVE_AS_KEY);
        saveAs.setOnAction(e -> saveAs());
        javafx.scene.control.MenuItem html = new javafx.scene.control.MenuItem("Export HTML…");
        html.setOnAction(e -> exportHtml());
        html.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(diffs));
        javafx.scene.control.MenuButton more = new javafx.scene.control.MenuButton("⋯", null, saveAs, html);
        more.setTooltip(new Tooltip("Save As, Export HTML"));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox form = new HBox(8, schemaSource, swap, schemaTarget, tablesButton, compare, gap, saveButton, more);
        CompareSide.keepWidth(tablesButton, compare, saveButton, more);
        form.setAlignment(Pos.CENTER_LEFT);
        schemaStatus.getStyleClass().add("hint-small");
        schemaStatus.setWrapText(true);
        schemaStatus.managedProperty().bind(schemaStatus.textProperty().isNotEmpty());
        schemaStatus.visibleProperty().bind(schemaStatus.managedProperty());
        VBox top = new VBox(6, form, schemaStatus);
        top.setPadding(new Insets(6, 8, 6, 8));
        top.getStyleClass().add("compare-settings");
        BorderPane page = new BorderPane(split);
        page.setTop(top);
        return page;
    }

    /** A reopened report's picked tables, until both schemas are chosen. */
    private Set<String> pendingPicked;

    /** A reopened settings file: compare once both schemas are chosen. */
    private boolean pendingAutoCompare;

    private void resetPickedTables() {
        boolean bothChosen = schemaSource.schema.getValue() != null && schemaTarget.schema.getValue() != null;
        if (pendingPicked != null && bothChosen) {
            applyPicked(pendingPicked);
            pendingPicked = null;
        } else {
            pickedTables = Set.of();
            tablesButton.setText("All tables");
        }
        if (pendingAutoCompare && bothChosen) {
            pendingAutoCompare = false;
            javafx.application.Platform.runLater(this::runSchemaCompare);
        }
    }

    /** Asks about unsaved results before the tab (or the app) closes. Returns false if the user cancelled. */
    @Override
    public boolean confirmClose() {
        return compareFile.confirmClose(() -> {
            save();
            return !compareFile.isDirty();
        });
    }

    /** Save (Ctrl+S): to the compare's file, the same kind as it is (results, or settings only); without a file yet, as Save As. */
    void save() {
        Path file = compareFile.file();
        if (file == null) {
            saveAs();
        } else if (!compareFile.isSettingsOnly() && !diffs.isEmpty()) {
            write(file, true);
        } else if (schemaSource.ready() && schemaTarget.ready()) {
            write(file, false);
        } else {
            schemaStatus.setText("Pick a connection and schema on both sides first.");
        }
    }

    /** Save As… (Ctrl+Shift+S): where, and whether with the results (the default when there are some) or the settings only. */
    void saveAs() {
        boolean hasResults = !diffs.isEmpty();
        boolean hasSettings = schemaSource.ready() && schemaTarget.ready();
        if (!hasResults && !hasSettings) {
            schemaStatus.setText("Pick a connection and schema on both sides first.");
            return;
        }
        CompareReportChooser.SaveChoice choice = CompareReportChooser.saveCompare(getTabPane().getScene().getWindow(), compareFile.file(),
                CompareReportChooser.defaultName("TableCompare", hasResults ? sourceName : schemaSource.label(),
                        hasResults ? targetName : schemaTarget.label()), hasResults, hasSettings);
        if (choice != null) write(choice.file(), choice.withResults());
    }

    private void write(Path file, boolean withResults) {
        SchemaCompareReport r;
        if (withResults) {
            r = report();
        } else {
            r = SchemaCompareReport.of(List.of(), schemaSource.label(), schemaTarget.label(), "v" + AppFooter.version());
            r.settingsOnly = true;
            r.sourceConnection = schemaSource.connection.getValue().getName();
            r.sourceSchema = schemaSource.schemaLabel();
            r.targetConnection = schemaTarget.connection.getValue().getName();
            r.targetSchema = schemaTarget.schemaLabel();
            r.pickedTables = List.copyOf(pickedTables);
        }
        try {
            CompareReportFiles.save(file, r);
        } catch (IOException e) {
            schemaStatus.setText("Could not save: " + e.getMessage());
            return;
        }
        compareFile.at(file, !withResults);
        schemaStatus.setText(withResults ? "Saved to " + file : "Settings saved to " + file + " · opening it compares again");
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

    /**
     * Compare (Ctrl+Enter): reads both schemas as a {@link Job}, each on a connection of its own (so the navigator and
     * scripts keep working), at the same time; while it runs the button is Cancel.
     */
    private void runSchemaCompare() {
        if (running != null) return;
        if (!schemaSource.ready() || !schemaTarget.ready()) {
            schemaStatus.setText("Pick a connection and schema on both sides.");
            return;
        }
        sourceName = schemaSource.label();
        targetName = schemaTarget.label();
        diffs.clear();
        refreshFilters();
        showDetails(null);
        Namespace srcNs = schemaSource.schema.getValue();
        Namespace dstNs = schemaTarget.schema.getValue();
        Set<String> only = pickedTables;
        String src = sourceName;
        String dst = targetName;
        compareButton.setText("Cancel");
        running = Job.start(List.of(schemaSource.connection.getValue(), schemaTarget.connection.getValue()), DatabaseSession::open,
                Async.background(), Platform::runLater, (sessions, job) -> {
                    DatabaseSession a = sessions.get(0);
                    DatabaseSession b = sessions.get(1);
                    // read both sides at the same time (they usually are different servers)
                    int[] progress = {0, 0, 0, 0};
                    Runnable show = () -> job.progress("Reading " + src + ": " + progress[0] + "/" + progress[1]
                            + " · " + dst + ": " + progress[2] + "/" + progress[3] + " tables…");
                    show.run();
                    var readA = a.submit(() -> SchemaCompare.snapshot(a, srcNs, only, (done, total) -> {
                        job.checkCancelled();
                        progress[0] = done;
                        progress[1] = total;
                        show.run();
                    }));
                    var readB = b.submit(() -> SchemaCompare.snapshot(b, dstNs, only, (done, total) -> {
                        job.checkCancelled();
                        progress[2] = done;
                        progress[3] = total;
                        show.run();
                    }));
                    return SchemaCompare.compare(readA.get(), readB.get());
                }, new Job.Listener<>() {
                    @Override
                    public void progress(String text) {
                        schemaStatus.setText(text);
                    }

                    @Override
                    public void done(List<TableDiff> result) {
                        jobEnded();
                        diffs.setAll(result);
                        compareFile.compared();
                        refreshFilters();
                        schemaStatus.setText("");
                        diffList.getSelectionModel().selectFirst();
                    }

                    @Override
                    public void failed(Throwable error) {
                        jobEnded();
                        Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null
                                ? error.getCause() : error;
                        schemaStatus.setText("Could not read the schema: " + cause.getMessage());
                    }

                    @Override
                    public void cancelled() {
                        jobEnded();
                        schemaStatus.setText("Compare cancelled");
                    }
                });
    }

    /** The compare running now (null when none): the Compare button cancels it. */
    private Job<List<TableDiff>> running;

    private void jobEnded() {
        running = null;
        compareButton.setText("Compare");
    }

    /** Shows a report opened from a file; a new compare can still be run from the form above. */
    void showSaved(SchemaCompareReport saved, Path file) {
        compareFile.at(file, saved.settingsOnly);
        if (saved.settingsOnly) {
            pendingAutoCompare = true;
            schemaStatus.setText("Settings from " + file.getFileName() + ": comparing as soon as both sides are ready…");
        } else {
            sourceName = saved.sourceLabel;
            targetName = saved.targetLabel;
            diffs.setAll(saved.tables);
            refreshFilters();
            schemaStatus.setText("");
            diffList.getSelectionModel().selectFirst();
        }
        // the form as it was, to compare again (connects to both sides, as picking them does)
        ConnectionConfig source = connectionNamed(saved.sourceConnection);
        ConnectionConfig target = connectionNamed(saved.targetConnection);
        if (source != null) schemaSource.select(source, saved.sourceSchema, null);
        if (target != null) schemaTarget.select(target, saved.targetSchema, null);
        // applied once both schemas are chosen (choosing a schema resets the picked tables)
        pendingPicked = saved.pickedTables == null || saved.pickedTables.isEmpty() ? null : Set.copyOf(saved.pickedTables);
    }

    private ConnectionConfig connectionNamed(String name) {
        return name == null ? null : connections.stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
    }

    private void applyPicked(Set<String> picked) {
        pickedTables = picked;
        tablesButton.setText(picked.size() == 1 ? "1 table: " + picked.iterator().next() : picked.size() + " tables");
    }

    private SchemaCompareReport report() {
        SchemaCompareReport r = SchemaCompareReport.of(diffs, sourceName, targetName, "v" + AppFooter.version());
        r.sourceConnection = schemaSource.connection.getValue() == null ? null : schemaSource.connection.getValue().getName();
        r.sourceSchema = schemaSource.schemaLabel();
        r.targetConnection = schemaTarget.connection.getValue() == null ? null : schemaTarget.connection.getValue().getName();
        r.targetSchema = schemaTarget.schemaLabel();
        r.pickedTables = List.copyOf(pickedTables);
        return r;
    }

    private void exportHtml() {
        Path file = CompareReportChooser.saveHtml(getTabPane().getScene().getWindow(), compareFile.file(),
                CompareReportChooser.defaultName("TableCompare", sourceName, targetName));
        if (file == null) return;
        try {
            Files.writeString(file, CompareHtml.table(report()), StandardCharsets.UTF_8);
            schemaStatus.setText("HTML saved as " + file);
        } catch (IOException e) {
            schemaStatus.setText("Could not save the HTML report: " + e.getMessage());
        }
    }

    /** Each filter's icon and count; the hover text says what it is (with the sides' names). */
    private void refreshFilters() {
        Map<Status, Long> counts = new java.util.EnumMap<>(Status.class);
        diffs.forEach(d -> counts.merge(d.status(), 1L, Long::sum));
        statusFilters.forEach((status, t) -> {
            t.setText(icon(status) + " " + counts.getOrDefault(status, 0L));
            t.getStyleClass().removeIf(c -> c.startsWith("cmp-") && !c.equals("cmp-filter"));
            t.getStyleClass().add(styleClass(status));
            t.setTooltip(new Tooltip(switch (status) {
                case SAME -> "Identical tables (and identical columns of a changed table)";
                case CHANGED -> "Tables that differ";
                case ONLY_SOURCE -> "Tables only in " + sourceName;
                case ONLY_TARGET -> "Tables only in " + targetName;
            } + ": shown while pressed"));
        });
    }

    private static String icon(Status s) {
        return switch (s) {
            case SAME -> "✔";
            case CHANGED -> "✎";
            case ONLY_SOURCE -> "＋";
            case ONLY_TARGET -> "−";
        };
    }

    private static String styleClass(Status s) {
        return switch (s) {
            case SAME -> "cmp-same";
            case CHANGED -> "cmp-changed";
            case ONLY_SOURCE -> "cmp-only-source";
            case ONLY_TARGET -> "cmp-only-target";
        };
    }

    private void applyFilter() {
        shownDiffs.setPredicate(d -> statusFilters.get(d.status()).isSelected());
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
            if (i.status() == Status.SAME && !statusFilters.get(Status.SAME).isSelected() && !i.name().contains(" = ")) continue;
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
            icon.setText(icon(d.status()));
            icon.getStyleClass().setAll("label", "cmp-icon", styleClass(d.status()));
            name.setText(d.name());
            String kind = d.kind().contains("VIEW") ? "view" : "";
            detail.setText(switch (d.status()) {
                case SAME -> kind;
                case CHANGED -> (kind.isEmpty() ? "" : kind + " · ") + d.differences() + " diff";
                case ONLY_SOURCE, ONLY_TARGET -> kind;
            });
            setGraphic(box);
        }
    }
}
