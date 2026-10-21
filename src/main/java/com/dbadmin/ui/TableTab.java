package com.dbadmin.ui;

import com.dbadmin.model.TableData;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Shows one table or view, laid out like DBeaver: a Properties tab with a vertical list of
 * sections (Columns, Constraints, Foreign Keys, Indexes, Triggers) and a Data tab, opened by default.
 */
class TableTab extends Tab {

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
            loadCount();
            loadPage();
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

        BorderPane pane = new BorderPane(dataView);
        pane.setTop(bar);
        return pane;
    }

    private void loadCount() {
        Async.run(session, () -> session.countRows(table), count -> {
            totalRows = count;
            updatePager(dataGrid.getItems().size());
        }, ex -> totalRows = -1);
    }

    private void loadPage() {
        prev.setDisable(true);
        next.setDisable(true);
        pageInfo.setText("Loading…");
        long requestedOffset = offset;
        Async.run(session, () -> session.fetchRows(table, PAGE_SIZE, requestedOffset), data -> {
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
