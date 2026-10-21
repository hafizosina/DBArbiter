package com.dbadmin.ui;

import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Shows one table or view: its columns, indexes and a page of its data. */
class TableTab extends Tab {

    private static final int PAGE_SIZE = 200;

    private final DatabaseSession session;
    private final TableRef table;

    private final DataGrid columnsGrid = new DataGrid();
    private final DataGrid indexesGrid = new DataGrid();
    private final DataGrid dataGrid = new DataGrid();

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

        Tab columns = new Tab("Columns", columnsGrid);
        Tab indexes = new Tab("Indexes", indexesGrid);
        Tab data = new Tab("Data", buildDataPane());
        TabPane inner = new TabPane(columns, indexes, data);
        inner.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setContent(inner);

        // fetch data only when the Data tab is first opened
        data.setOnSelectionChanged(e -> {
            if (data.isSelected() && !dataLoaded) {
                dataLoaded = true;
                loadCount();
                loadPage();
            }
        });

        loadStructure();
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
        HBox bar = new HBox(8, refresh, spacer, pageInfo, prev, next);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));

        BorderPane pane = new BorderPane(dataGrid);
        pane.setTop(bar);
        return pane;
    }

    private void loadStructure() {
        Async.run(session, () -> session.listColumns(table), columnsGrid::show,
                ex -> columnsGrid.setPlaceholder(errorLabel(ex)));
        Async.run(session, () -> session.listIndexes(table), indexesGrid::show,
                ex -> indexesGrid.setPlaceholder(errorLabel(ex)));
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
