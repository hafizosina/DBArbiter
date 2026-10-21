package com.dbadmin.ui;

import com.dbadmin.model.DbType;
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
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Shows one table or view, laid out like DBeaver: a Properties tab with a vertical list of
 * sections (Columns, Constraints, Foreign Keys, Indexes, Triggers) and a Data tab, opened by default.
 */
class TableTab extends Tab {

    private static final int PAGE_SIZE = 200;
    /** Rows fetched for a custom query in the quick-query box. */
    private static final int QUERY_MAX_ROWS = 1000;

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
    /** Temporary, never-saved query box above the data; defaults to "select * from <table>". */
    private final CodeArea queryEditor = new CodeArea();
    private final Label queryMessage = new Label();
    private String defaultQuery;
    /** True while the grid shows a custom query's result instead of the paged table. */
    private boolean customMode;
    private long offset;
    private long totalRows = -1;
    private boolean dataLoaded;

    TableTab(DatabaseSession session, TableRef table) {
        super(table.name());
        this.session = session;
        this.table = table;
        setTooltip(new javafx.scene.control.Tooltip(session.getConfig().getName() + " · " + table.displayName()));

        addSection("Columns", () -> session.listColumns(table));
        addSection("Constraints", () -> session.listConstraints(table));
        addSection("Foreign Keys", () -> session.listForeignKeys(table));
        addSection("Indexes", () -> session.listIndexes(table));
        addSection("Triggers", () -> session.listTriggers(table));

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

    /** "▦ schema.table  TABLE · connection" above the Properties / Data tabs. */
    private HBox buildHeader() {
        Label icon = new Label(table.isMaterializedView() ? "◩" : table.isView() ? "◫" : "▦");
        icon.getStyleClass().addAll("table-header-icon",
                table.isMaterializedView() ? "matview" : table.isView() ? "view" : "table");
        Label name = new Label(table.displayName());
        name.getStyleClass().add("table-header-name");
        Label kind = new Label(table.type() == null ? "" : table.type().toUpperCase());
        kind.getStyleClass().add("table-header-kind");
        Label connection = new Label(session.getConfig().getName());
        connection.getStyleClass().add("hint-small");
        connection.setGraphic(DbIcons.connection(session.getConfig().getType(), false));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, icon, name, kind, spacer, connection);
        header.setAlignment(Pos.CENTER_LEFT);
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
        nav.getStyleClass().add("section-nav");
        nav.setPrefWidth(140);
        nav.setMinWidth(120);
        nav.getSelectionModel().selectedItemProperty().addListener((o, a, name) -> {
            if (name != null) showSection(name, false);
        });

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> {
            String name = nav.getSelectionModel().getSelectedItem();
            if (name != null) showSection(name, true);
        });
        HBox bar = new HBox(8, refresh);
        bar.setPadding(new Insets(6));
        sectionContent.setTop(bar);

        BorderPane pane = new BorderPane(sectionContent);
        pane.setLeft(nav);
        nav.getSelectionModel().selectFirst();
        return pane;
    }

    private void showSection(String name, boolean reload) {
        Section section = sections.get(name);
        sectionContent.setCenter(section.grid());
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

    static String key(DatabaseSession session, TableRef t) {
        return session.getConfig().getId() + "|" + t.catalog() + "|" + t.schema() + "|" + t.name();
    }

    private BorderPane buildDataPane() {
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> {
            if (customMode) {
                runQuery();
            } else {
                loadCount();
                loadPage();
            }
        });
        prev.setOnAction(e -> {
            offset = Math.max(0, offset - PAGE_SIZE);
            loadPage();
        });
        next.setOnAction(e -> {
            offset += PAGE_SIZE;
            loadPage();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, refresh, DataView.valuePanelToggle(), spacer, pageInfo, prev, next);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));

        BorderPane grid = new BorderPane(dataView);
        grid.setTop(bar);

        SplitPane split = new SplitPane(buildQueryBox(), grid);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.2);
        SplitPane.setResizableWithParent(split.getItems().get(0), false); // keep its height when the window grows
        return new BorderPane(split);
    }

    private BorderPane buildQueryBox() {
        defaultQuery = "select * from " + qualified();
        queryEditor.getStyleClass().addAll("sql-editor", "quick-query");
        queryEditor.replaceText(defaultQuery);
        queryEditor.getUndoManager().forgetHistory();
        highlightQuery();
        queryEditor.multiPlainChanges().successionEnds(Duration.ofMillis(150)).subscribe(x -> highlightQuery());
        queryEditor.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                e.consume();
                runQuery();
            }
        });

        Button run = new Button("▶ Run");
        run.setTooltip(new Tooltip("Run this query (Ctrl+Enter). Not saved; up to " + QUERY_MAX_ROWS + " rows."));
        run.setMaxWidth(Double.MAX_VALUE);
        run.setOnAction(e -> runQuery());
        Button reset = new Button("Reset");
        reset.setTooltip(new Tooltip("Back to: " + defaultQuery));
        reset.setMaxWidth(Double.MAX_VALUE);
        reset.setOnAction(e -> {
            queryEditor.replaceText(defaultQuery);
            runQuery();
        });
        VBox buttons = new VBox(4, run, reset);
        buttons.setPadding(new Insets(0, 0, 0, 6));

        queryMessage.getStyleClass().add("hint-small");
        BorderPane box = new BorderPane(new VirtualizedScrollPane<>(queryEditor));
        box.setRight(buttons);
        box.setBottom(queryMessage);
        box.setPadding(new Insets(6, 6, 2, 6));
        box.setMinHeight(84);
        return box;
    }

    /** schema.table (database.table on MySQL), quoting names only where needed. */
    private String qualified() {
        DbType type = session.getConfig().getType();
        String prefix = type == DbType.MYSQL ? table.catalog() : table.schema();
        String name = SqlCompleter.quoteIfNeeded(table.name(), type);
        return prefix == null ? name : SqlCompleter.quoteIfNeeded(prefix, type) + "." + name;
    }

    private void highlightQuery() {
        queryEditor.setStyleSpans(0, SqlHighlighter.highlight(queryEditor.getText(), session.getConfig().getType()));
    }

    /**
     * Runs the query box. The unchanged default query shows the paged table again;
     * anything else replaces the grid with its result (one statement, up to QUERY_MAX_ROWS rows).
     */
    private void runQuery() {
        String sql = queryEditor.getText().strip();
        if (sql.isEmpty() || normalize(sql).equals(normalize(defaultQuery))) {
            customMode = false;
            queryMessage.setText("");
            offset = 0;
            loadCount();
            loadPage();
            return;
        }
        List<String> statements = SqlSplitter.split(sql, session.getConfig().getType());
        if (statements.size() != 1) {
            queryMessage.setText(statements.isEmpty() ? "Nothing to run." : "Only one statement here; use a script for more.");
            return;
        }
        customMode = true;
        prev.setDisable(true);
        next.setDisable(true);
        pageInfo.setText("Running…");
        queryMessage.setText("");
        Async.run(session, () -> session.execute(statements.get(0), QUERY_MAX_ROWS), r -> {
            if (r.data() != null) {
                dataGrid.show(r.data());
                dataGrid.setPlaceholder(new Label("No rows"));
                pageInfo.setText(r.data().rows().size() + " row(s)"
                        + (r.truncated() ? " (first " + QUERY_MAX_ROWS + ")" : "") + " · " + r.millis() + " ms");
            } else {
                dataGrid.getItems().clear();
                pageInfo.setText((r.updateCount() >= 0 ? r.updateCount() + " row(s) affected" : "OK") + " · " + r.millis() + " ms");
            }
        }, ex -> {
            dataGrid.getItems().clear();
            dataGrid.setPlaceholder(errorLabel(ex));
            pageInfo.setText("Error");
        });
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
}
