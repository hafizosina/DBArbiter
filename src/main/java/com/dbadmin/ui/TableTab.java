package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.StatementResult;
import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.SqlSplitter;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Shows one table or view, laid out like DBeaver: a Properties tab with a vertical list of
 * sections (Columns, Constraints, Foreign Keys, Indexes, Triggers) and a Data tab, opened by default.
 */
class TableTab extends Tab implements UnsavedWork {

    private static final int PAGE_SIZE = 200;

    private final DatabaseSession session;
    private final TableRef table;

    private final DataView dataView = new DataView();
    private final DataGrid dataGrid = dataView.grid();
    /** Properties sections by name, in display order; each is loaded the first time it is shown. */
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final Set<String> loadedSections = new HashSet<>();
    private final BorderPane sectionContent = new BorderPane();

    private final Label pageInfo = new Label();
    private final Button prev = new Button("◀ Prev");
    private final Button next = new Button("Next ▶");
    /**
     * Temporary, never-saved query box above the data, defaulting to "select * from <table>".
     * Same editor and runner as a script tab, minus saving.
     */
    private final SqlEditor queryEditor;
    private final ScriptRunner runner;
    private final Label queryMessage = new Label();
    private String defaultQuery;
    /** True while the grid shows a custom query's result instead of the paged table. */
    private boolean customMode;
    private long offset;
    private long totalRows = -1;
    private boolean dataLoaded;
    /** The query whose rows the grid shows (custom mode), when it can be exported: one plain SELECT without parameters. */
    private String exportableQuery;
    private final TableTransfer.Host transferHost;
    private final SqlCompleter completer;
    private final StructureEditing structure;
    /** The Properties section shown now (or DDL). */
    private String currentSection;
    private final Button addItem = new Button("Add…");
    private final Button editItem = new Button("Edit…");
    private final Button dropItem = new Button("Drop");

    private final TableActions.Host tableActions;

    TableTab(DatabaseSession session, TableRef table, SessionProvider sessions, SqlCompleter completer, TableTransfer.Host transferHost,
             TableActions.Host tableActions) {
        super(table.name());
        setGraphic(TabBadge.of(table.isView() ? TabBadge.Kind.VIEW : TabBadge.Kind.TABLE));
        this.tableActions = tableActions;
        this.session = session;
        this.table = table;
        this.transferHost = transferHost;
        this.completer = completer;
        this.structure = new StructureEditing(session, table, this::structureChanged);
        this.queryEditor = new SqlEditor(completer, sessions, session::getConfig, this::runStatement);
        this.runner = new ScriptRunner(sessions, completer);
        setOnSelectionChanged(e -> queryEditor.hidePopup());
        dataView.offerRecordMode();
        setTooltip(new javafx.scene.control.Tooltip(session.getConfig().getName() + " · " + table.displayName()));
        setOnCloseRequest(e -> {
            if (!confirmClose()) e.consume();
        });

        addSection("Columns", () -> session.listColumns(table));
        addSection("Constraints", () -> session.listConstraints(table));
        addSection("Foreign Keys", () -> session.listForeignKeys(table));
        addSection("Indexes", () -> session.listIndexes(table));
        addSection("Triggers", () -> session.listTriggers(table));
        // DDL is not a grid: see showDdl

        Tab properties = new Tab("Properties", buildPropertiesPane());
        Tab data = new Tab("Data", buildDataPane());
        TabPane inner = new TabPane(properties, data);
        inner.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        inner.getStyleClass().add("sub-tabs");
        BorderPane content = new BorderPane(inner);
        content.setTop(buildHeader());
        setContent(content);

        // fetch data only when the Data tab is first shown
        data.setOnSelectionChanged(e -> {
            if (data.isSelected() && !dataLoaded) {
                dataLoaded = true;
                loadCount();
                loadPage();
            }
        });
        inner.getSelectionModel().select(data);
    }

    /** The connection (with read-only / read-write) over "▦ schema.table  TABLE", above the Properties / Data tabs. */
    private VBox buildHeader() {
        Label icon = new Label(table.isMaterializedView() ? "◩" : table.isView() ? "◫" : "▦");
        icon.getStyleClass().addAll("table-header-icon",
                table.isMaterializedView() ? "matview" : table.isView() ? "view" : "table");
        Label name = new Label(table.displayName());
        name.getStyleClass().add("table-header-name");
        Label kind = new Label(table.type() == null ? "" : table.type().toUpperCase());
        kind.getStyleClass().add("table-header-kind");
        ConnectionConfig config = session.getConfig();
        Label connection = new Label(config.getName());
        connection.getStyleClass().add("table-header-connection");
        connection.setGraphic(DbIcons.connection(config.getType(), false));
        Label mode = new Label(config.isReadOnly() ? "read-only" : "read-write");
        mode.getStyleClass().add(config.isReadOnly() ? "hint-small" : "mode-write");
        // Backup / Rename, and Truncate / Reset / Drop where the connection's warning level is 0 (see TableActions)
        javafx.scene.control.MenuButton actions = new javafx.scene.control.MenuButton("Table");
        TableActions.Items items = TableActions.items(() -> getTabPane() == null ? null : getTabPane().getScene().getWindow(),
                () -> new TableActions.Target(session, table), tableActions);
        actions.getItems().setAll(items.all());
        actions.setOnShowing(e -> items.showFor(session.getConfig(), table)); // the connection may have been edited
        items.showFor(session.getConfig(), table);
        actions.setVisible(items.anyShown());
        actions.setManaged(items.anyShown());
        actions.getStyleClass().add("small-button");
        HBox connectionRow = new HBox(8, connection, mode);
        connectionRow.setAlignment(Pos.CENTER_LEFT);
        HBox tableRow = new HBox(8, icon, name, kind, actions);
        tableRow.setAlignment(Pos.CENTER_LEFT);
        VBox header = new VBox(4, connectionRow, tableRow);
        header.getStyleClass().add("table-header");
        return header;
    }

    private record Section(DataGrid grid, Callable<TableData> loader) {
    }

    private void addSection(String name, Callable<TableData> loader) {
        sections.put(name, new Section(new DataGrid(), loader));
    }

    private BorderPane buildPropertiesPane() {
        ListView<String> nav = new ListView<>();
        nav.getItems().addAll(sections.keySet());
        nav.getItems().add(DDL);
        nav.getStyleClass().add("section-nav");
        nav.setPrefWidth(140);
        nav.setMinWidth(120);
        nav.getSelectionModel().selectedItemProperty().addListener((o, a, name) -> {
            if (DDL.equals(name)) showDdl(false);
            else if (name != null) showSection(name, false);
        });

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> {
            if (!dataView.confirmDiscard()) return;
            String name = nav.getSelectionModel().getSelectedItem();
            if (DDL.equals(name)) showDdl(true);
            else if (name != null) showSection(name, true);
        });
        ddlCopy.setTooltip(new Tooltip("Copy the DDL"));
        ddlCopy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
            c.putString(ddlArea.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
        });
        ddlCopy.setVisible(false);
        ddlCopy.managedProperty().bind(ddlCopy.visibleProperty()); // no gap where it is hidden
        String notEditable = structure.notEditable();
        addItem.setOnAction(e -> structureAction(StructureAction.ADD));
        editItem.setOnAction(e -> structureAction(StructureAction.EDIT));
        dropItem.setOnAction(e -> structureAction(StructureAction.DROP));
        for (Button b : List.of(addItem, editItem, dropItem)) {
            b.managedProperty().bind(b.visibleProperty());
            if (notEditable != null) b.setTooltip(new Tooltip(notEditable));
        }
        for (Map.Entry<String, Section> s : sections.entrySet()) {
            if (!StructureEditing.SECTIONS.contains(s.getKey()) || notEditable != null) continue;
            DataGrid grid = s.getValue().grid();
            grid.setContextMenu(structureMenu());
            grid.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY && selectedStructureRow() != null) {
                    structureAction(StructureAction.EDIT);
                }
            });
            grid.getFocusModel().focusedIndexProperty().addListener((o, a, b) -> updateStructureButtons());
        }
        HBox bar = new HBox(8, refresh, ddlCopy, addItem, editItem, dropItem);
        bar.setPadding(new Insets(6));
        sectionContent.setTop(bar);

        BorderPane pane = new BorderPane(sectionContent);
        pane.setLeft(nav);
        nav.getSelectionModel().selectFirst();
        return pane;
    }

    private static final String DDL = "DDL";
    private final org.fxmisc.richtext.CodeArea ddlArea = new org.fxmisc.richtext.CodeArea();
    /** Next to Refresh, only while the DDL is shown. */
    private final Button ddlCopy = new Button("Copy");
    private org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.CodeArea> ddlPane;
    private boolean ddlLoaded;

    /** The CREATE statement(s) of the table, view or materialized view, colored as SQL; read-only, copyable. */
    private void showDdl(boolean reload) {
        if (ddlPane == null) {
            ddlArea.setEditable(false);
            ddlArea.getStyleClass().add("sql-editor");
            ddlArea.setParagraphGraphicFactory(org.fxmisc.richtext.LineNumberFactory.get(ddlArea));
            ddlPane = new org.fxmisc.flowless.VirtualizedScrollPane<>(ddlArea);
        }
        sectionContent.setCenter(ddlPane);
        ddlCopy.setVisible(true);
        currentSection = DDL;
        updateStructureButtons();
        if (ddlLoaded && !reload) return;
        ddlLoaded = true;
        ddlArea.replaceText("-- loading…");
        Async.run(session, () -> session.ddl(table), sql -> {
            ddlArea.replaceText(sql.isEmpty() ? "-- no DDL found" : sql);
            ddlArea.setStyleSpans(0, SqlHighlighter.highlight(ddlArea.getText(), session.getConfig().getType()));
            ddlArea.moveTo(0);
            ddlArea.requestFollowCaret();
        }, ex -> ddlArea.replaceText("-- could not read the DDL: " + ex.getMessage()));
    }

    private void showSection(String name, boolean reload) {
        Section section = sections.get(name);
        sectionContent.setCenter(section.grid());
        ddlCopy.setVisible(false);
        currentSection = name;
        updateStructureButtons();
        if (!loadedSections.add(name) && !reload) return;
        section.grid().getItems().clear();
        section.grid().setPlaceholder(new Label("Loading…"));
        Async.run(session, section.loader(), data -> {
            section.grid().show(data);
            section.grid().setPlaceholder(new Label("No " + name.toLowerCase()));
        }, ex -> section.grid().setPlaceholder(errorLabel(ex)));
    }

    String key() {
        return key(session, table);
    }

    // ---- changing the structure ----

    private enum StructureAction { ADD, EDIT, DROP }

    /** Add / Edit / Drop show on the sections whose structure can change; Edit and Drop need a row. */
    private void updateStructureButtons() {
        boolean shown = currentSection != null && StructureEditing.SECTIONS.contains(currentSection);
        boolean editable = structure.notEditable() == null;
        boolean row = selectedStructureRow() != null;
        addItem.setVisible(shown);
        editItem.setVisible(shown);
        dropItem.setVisible(shown);
        addItem.setDisable(!editable);
        editItem.setDisable(!editable || !row);
        dropItem.setDisable(!editable || !row);
    }

    private List<Object> selectedStructureRow() {
        if (currentSection == null || !sections.containsKey(currentSection)) return null;
        DataGrid grid = sections.get(currentSection).grid();
        int i = grid.getFocusModel().getFocusedIndex();
        return i >= 0 && i < grid.getItems().size() ? grid.getItems().get(i) : null;
    }

    private javafx.scene.control.ContextMenu structureMenu() {
        javafx.scene.control.MenuItem add = new javafx.scene.control.MenuItem("Add…");
        add.setOnAction(e -> structureAction(StructureAction.ADD));
        javafx.scene.control.MenuItem edit = new javafx.scene.control.MenuItem("Edit…");
        edit.setOnAction(e -> structureAction(StructureAction.EDIT));
        javafx.scene.control.MenuItem drop = new javafx.scene.control.MenuItem("Drop");
        drop.setOnAction(e -> structureAction(StructureAction.DROP));
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(add, edit, drop);
        menu.setOnShowing(e -> {
            boolean row = selectedStructureRow() != null;
            edit.setDisable(!row);
            drop.setDisable(!row);
        });
        return menu;
    }

    private void structureAction(StructureAction action) {
        if (structure.notEditable() != null || currentSection == null || !StructureEditing.SECTIONS.contains(currentSection)) return;
        // the rows are read again after a change: unsaved edits would be lost
        if (!dataView.confirmDiscard()) return;
        javafx.stage.Window owner = getTabPane() == null ? null : getTabPane().getScene().getWindow();
        List<Object> row = selectedStructureRow();
        switch (action) {
            case ADD -> structure.add(currentSection, owner);
            case EDIT -> {
                if (row != null) structure.edit(currentSection, row, owner);
            }
            case DROP -> {
                if (row != null) structure.drop(currentSection, row, owner);
            }
        }
    }

    /** The structure changed: every section, the DDL, autocomplete and the rows are read again. */
    private void structureChanged() {
        completer.invalidate(session);
        loadedSections.clear();
        ddlLoaded = false;
        if (currentSection != null && sections.containsKey(currentSection)) showSection(currentSection, true);
        if (dataLoaded && !customMode) {
            loadCount();
            loadPage();
        }
    }

    /** Rows were imported elsewhere: shows the table's rows again (a custom query result stays as it is). */
    void reload() {
        if (!dataLoaded || customMode) return;
        loadCount();
        loadPage();
    }

    static String key(DatabaseSession session, TableRef t) {
        return session.getConfig().getId() + "|" + t.catalog() + "|" + t.schema() + "|" + t.name();
    }

    private BorderPane buildDataPane() {
        defaultQuery = "select * from " + qualified();
        queryEditor.getStyleClass().add("quick-query");
        queryEditor.setText(defaultQuery);


        Button run = new Button("▶ Run");
        run.setTooltip(new Tooltip("Run the statement at the cursor, or the selection (Ctrl+Enter). Not saved; up to "
                + ScriptRunner.FETCH_SIZE + " rows at a time."));
        run.setOnAction(e -> runStatement());
        run.disableProperty().bind(runner.runningProperty());
        Button runAll = new Button("▶▶ Run All");
        runAll.setTooltip(new Tooltip("Run all of it, or the selection"));
        runAll.setOnAction(e -> runQuery());
        runAll.disableProperty().bind(runner.runningProperty());
        Button stop = new Button("■ Stop");
        stop.setOnAction(e -> runner.stop());
        stop.disableProperty().bind(runner.runningProperty().not());
        Button reset = new Button("Reset");
        reset.setTooltip(new Tooltip("Back to: " + defaultQuery));
        reset.setOnAction(e -> {
            if (!dataView.confirmDiscard()) return;
            queryEditor.setText(defaultQuery);
            runQuery();
        });
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> {
            if (!dataView.confirmDiscard()) return;
            if (customMode) {
                runQuery();
            } else {
                loadCount();
                loadPage();
            }
        });
        prev.setOnAction(e -> {
            if (!dataView.confirmDiscard()) return;
            offset = Math.max(0, offset - PAGE_SIZE);
            loadPage();
        });
        next.setOnAction(e -> {
            if (!dataView.confirmDiscard()) return;
            offset += PAGE_SIZE;
            loadPage();
        });
        queryMessage.getStyleClass().add("hint-small");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button export = new Button("Export…");
        export.setTooltip(new Tooltip("Save the rows to a file: CSV, Excel, SQL INSERT or JSON"));
        export.setOnAction(e -> TableTransfer.export(getTabPane().getScene().getWindow(), session, table,
                customMode ? exportableQuery : null, transferHost));
        Button importButton = new Button("Import…");
        importButton.setTooltip(new Tooltip(session.getConfig().isReadOnly() ? "Read-only connection"
                : "Insert rows from a CSV file (with a preview first), or open an SQL file in a script tab"));
        importButton.setDisable(table.isView() || session.getConfig().isReadOnly());
        importButton.setOnAction(e -> TableTransfer.importData(getTabPane().getScene().getWindow(), session, table, transferHost));
        HBox bar = new HBox(8, run, runAll, stop, reset, new Separator(Orientation.VERTICAL), refresh,
                DataView.recordToggle(), DataView.valuePanelToggle(), new Separator(Orientation.VERTICAL), export, importButton,
                queryMessage, spacer, pageInfo, prev, next);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));

        VBox.setMargin(queryEditor, new Insets(6, 6, 0, 6));
        VBox.setVgrow(queryEditor, Priority.ALWAYS);
        // the query box starts 3 lines high; drag the divider below the buttons to make it taller or shorter
        VBox query = new VBox(queryEditor, bar);
        SplitPane split = new SplitPane(query, dataView);
        split.setOrientation(Orientation.VERTICAL);
        SplitPane.setResizableWithParent(query, false); // a taller window gives the rows the extra space
        query.setMinHeight(0);
        // no taller than its 3 lines until sized (else the first frame shows the split's default, half the tab)
        query.setMaxHeight(Region.USE_PREF_SIZE);
        Runnable fit = () -> {
            if (split.getHeight() <= 0) return;
            double height = query.prefHeight(query.getWidth());
            query.setMaxHeight(Double.MAX_VALUE); // from now on the divider can be dragged anywhere
            split.setDividerPositions(height / split.getHeight());
        };
        queryEditor.setInitialLines(3, () -> {
            if (split.getHeight() > 0) {
                fit.run();
            } else {
                split.heightProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                    @Override
                    public void changed(javafx.beans.value.ObservableValue<? extends Number> o, Number a, Number h) {
                        if (h.doubleValue() <= 0) return;
                        split.heightProperty().removeListener(this);
                        fit.run();
                    }
                });
            }
        });
        return new BorderPane(split);
    }

    /** schema.table (database.table on MySQL), quoting names only where needed. */
    private String qualified() {
        DbType type = session.getConfig().getType();
        String prefix = type.qualifiesByCatalog() ? table.catalog() : table.schema();
        String name = SqlCompleter.quoteIfNeeded(table.name(), type);
        return prefix == null ? name : SqlCompleter.quoteIfNeeded(prefix, type) + "." + name;
    }

    /**
     * Runs the query box (the selection, or all of it). The unchanged default query shows the paged
     * table again; anything else runs like a script and the grid shows the last result set.
     */
    /** ▶▶ Run All: the selection, or all of the box. */
    private void runQuery() {
        runQuery(queryEditor.sqlToRun());
    }

    /** ▶ Run / Ctrl+Enter: the selection, or the statement at the cursor. */
    private void runStatement() {
        runQuery(queryEditor.statementToRun());
    }

    private void runQuery(String text) {
        if (!dataView.confirmDiscard()) return;
        String sql = text.strip();
        if (sql.isEmpty() || normalize(sql).equals(normalize(defaultQuery))) {
            customMode = false;
            queryMessage.setText("");
            offset = 0;
            loadCount();
            loadPage();
            return;
        }
        if (runner.run(sql, session.getConfig(), getTabPane().getScene().getWindow(), new GridResults())
                == ScriptRunner.Outcome.NOTHING_TO_RUN) {
            queryMessage.setText("Nothing to run.");
        }
    }

    /** Shows a run of the query box in the data grid: the last result set, or the error. */
    private class GridResults implements ScriptRunner.Listener {
        private int statements;

        @Override
        public void started(ConnectionConfig config, int statements, Map<String, String> values) {
            this.statements = statements;
            customMode = true;
            prev.setDisable(true);
            next.setDisable(true);
            pageInfo.setText("Running…");
            queryMessage.setText("");
        }

        @Override
        public void result(int number, StatementResult r, DatabaseSession runSession) {
            if (r.data() != null) {
                exportableQuery = r.params().isEmpty() && SqlSplitter.isReadOnlyQuery(r.sql()) ? r.sql() : null;
                dataView.showResult(r, runSession);
                TableEditing.forQuery(runSession, r.sql(), t -> dataView.enableEditing(t, () -> { }, false), dataView::showNotEditable);
                dataGrid.setPlaceholder(new Label("No rows"));
                pageInfo.setText(r.data().rows().size() + " row(s)"
                        + (r.truncated() ? " (first " + ScriptRunner.FETCH_SIZE + ")" : "") + " · " + r.millis() + " ms");
            } else {
                pageInfo.setText((r.updateCount() >= 0 ? r.updateCount() + " row(s) affected" : "OK") + " · " + r.millis() + " ms");
            }
        }

        @Override
        public void failed(int number, String sql, Throwable error) {
            dataGrid.getItems().clear();
            dataGrid.setPlaceholder(errorLabel(statements > 1
                    ? new Exception("Statement " + number + ": " + error.getMessage()) : error));
            pageInfo.setText("Error");
        }

        @Override
        public void finished(String summary) {
            // one successful statement: the pager line says it all
            boolean plain = statements == 1 && summary.startsWith("Done");
            queryMessage.setText(plain ? "" : summary + (statements > 1 ? " The grid shows the last result." : ""));
        }
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll(";\\s*$", "").trim().toLowerCase(Locale.ROOT);
    }

    private void loadCount() {
        Async.run(session, () -> session.countRows(table), count -> {
            totalRows = count;
            if (!customMode) updatePager(dataGrid.getItems().size());
        }, ex -> totalRows = -1);
    }

    private void loadPage() {
        prev.setDisable(true);
        next.setDisable(true);
        pageInfo.setText("Loading…");
        long requestedOffset = offset;
        Async.run(session, () -> session.fetchRows(table, PAGE_SIZE, requestedOffset), data -> {
            if (customMode) return; // a custom query was run meanwhile
            dataGrid.show(data);
            updatePager(data.rows().size());
            TableEditing.forTable(session, table, t -> {
                if (!customMode) dataView.enableEditing(t, this::loadPage, true);
            }, reason -> {
                if (!customMode) dataView.showNotEditable(reason);
            });
        }, ex -> {
            dataGrid.getItems().clear();
            dataGrid.setPlaceholder(errorLabel(ex));
            pageInfo.setText("Error");
        });
    }

    private void updatePager(int rowsOnPage) {
        String total = totalRows >= 0 ? " of " + totalRows : "";
        pageInfo.setText(rowsOnPage == 0
                ? "No rows" + (offset > 0 ? " at offset " + offset : "")
                : "Rows " + (offset + 1) + "–" + (offset + rowsOnPage) + total);
        prev.setDisable(offset == 0);
        next.setDisable(rowsOnPage < PAGE_SIZE || (totalRows >= 0 && offset + rowsOnPage >= totalRows));
    }

    private static Label errorLabel(Throwable ex) {
        Label l = new Label("Error: " + ex.getMessage());
        l.setWrapText(true);
        l.getStyleClass().add("error-text");
        return l;
    }

    /** Unsaved cell edits: shows this tab, then asks to save or discard them. */
    @Override
    public boolean confirmClose() {
        if (!dataView.hasEdits()) return true;
        if (getTabPane() != null) getTabPane().getSelectionModel().select(this);
        return dataView.confirmDiscard();
    }
}
