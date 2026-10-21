package com.dbadmin.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import com.dbadmin.model.StatementResult;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.SqlSplitter;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/** A data grid with the value panel beside it. The panel's visibility is shared by all views. */
class DataView extends BorderPane {

    /** Show/hide the value panel everywhere at once. */
    private static final BooleanProperty SHOW_VALUE_PANEL = new SimpleBooleanProperty(true);

    private final DataGrid grid = new DataGrid();
    private final CellInspector inspector = new CellInspector();
    /** For a compare result: the source value above, the target value below. */
    private final CellInspector targetInspector = new CellInspector();
    private final SplitPane pairPanel = new SplitPane();
    private final javafx.scene.layout.StackPane inspectorHost = new javafx.scene.layout.StackPane(inspector);
    private final SplitPane split = new SplitPane(grid);
    private double divider = 0.7;

    DataView() {
        grid.selectedCellProperty().addListener((o, a, cell) -> showCell(cell));
        pairPanel.setOrientation(Orientation.VERTICAL);
        SplitPane.setResizableWithParent(inspectorHost, false);
        setCenter(split);
        SHOW_VALUE_PANEL.addListener((o, a, show) -> applyVisibility(show));
        applyVisibility(SHOW_VALUE_PANEL.get());

        moreBar.setAlignment(Pos.CENTER_LEFT);
        moreBar.setPadding(new Insets(3, 8, 3, 8));
        moreBar.getStyleClass().add("more-bar");
        moreInfo.getStyleClass().add("hint-small");
        moreButton.setOnAction(e -> fetchMore());
        moreButton.setTooltip(new javafx.scene.control.Tooltip("Or scroll to the last row"));
        setBottom(moreBar);
        // anything else showing data in the grid (e.g. a table page) ends the chunked result
        grid.itemsProperty().addListener((o, a, b) -> {
            if (!showingChunk) {
                chunked = null;
                updateMoreBar();
            }
        });
        updateMoreBar();
        watchScrolling();
    }

    DataGrid grid() {
        return grid;
    }

    /** One view for a value; two (source, target) for a value from a data compare. */
    private void showCell(DataGrid.CellValue cell) {
        if (cell != null && cell.value() instanceof ComparedValue cv) {
            inspector.show(new DataGrid.CellValue(cell.column() + "  ·  " + cv.sourceName(), cell.type(),
                    cv.inSource() ? cv.source() : "(no row in " + cv.sourceName() + ")"));
            targetInspector.show(new DataGrid.CellValue(cell.column() + "  ·  " + cv.targetName(), cell.type(),
                    cv.inTarget() ? cv.target() : "(no row in " + cv.targetName() + ")"));
            if (pairPanel.getItems().isEmpty()) {
                pairPanel.getItems().setAll(inspector, targetInspector);
                inspectorHost.getChildren().setAll(pairPanel);
            }
        } else {
            if (!pairPanel.getItems().isEmpty()) {
                pairPanel.getItems().clear();
                inspectorHost.getChildren().setAll(inspector);
            }
            inspector.show(cell);
        }
    }

    // ---- results fetched chunk by chunk -----------------------------------

    private final Label moreInfo = new Label();
    private final Button moreButton = new Button("Fetch next " + ScriptRunner.FETCH_SIZE);
    private final HBox moreBar = new HBox(8, moreInfo, moreButton);
    private StatementResult chunked;
    private DatabaseSession chunkSession;
    private int fetched;
    private boolean canFetchMore;
    private boolean fetching;
    private boolean showingChunk;

    /**
     * Shows a query result whose first chunk was fetched. If more rows exist and the query only reads,
     * the next chunk is fetched by scrolling to the end or with "Fetch next"; other statements are
     * never re-run, so they stay at their first chunk.
     */
    void showResult(StatementResult result, DatabaseSession session) {
        showingChunk = true;
        grid.show(result.data());
        showingChunk = false;
        chunked = result;
        chunkSession = session;
        fetched = result.data().rows().size();
        canFetchMore = result.truncated() && SqlSplitter.isReadOnlyQuery(result.sql());
        fetching = false;
        updateMoreBar();
    }

    private void updateMoreBar() {
        boolean show = chunked != null && (chunked.truncated() || fetched > chunked.data().rows().size());
        moreBar.setVisible(show);
        moreBar.setManaged(show);
        moreButton.setVisible(canFetchMore);
        moreButton.setDisable(fetching);
        if (fetching) {
            moreInfo.setText(fetched + " rows fetched · fetching more…");
        } else if (canFetchMore) {
            moreInfo.setText(fetched + " rows fetched · more available");
        } else if (chunked != null && chunked.truncated() && fetched == chunked.data().rows().size()) {
            moreInfo.setText("First " + fetched + " rows. More exist, but this statement is not re-run to fetch them "
                    + "(only plain read-only queries are).");
        } else {
            moreInfo.setText("All " + fetched + " rows fetched");
        }
    }

    private void fetchMore() {
        if (!canFetchMore || fetching || chunked == null) return;
        StatementResult query = chunked;
        DatabaseSession session = chunkSession;
        int skip = fetched;
        fetching = true;
        updateMoreBar();
        Async.run(session, () -> session.execute(query.sql(), query.params(), ScriptRunner.FETCH_SIZE, skip), more -> {
            if (chunked != query) return; // another result is shown now
            fetching = false;
            if (more.data() != null) {
                grid.getItems().addAll(more.data().rows());
                fetched += more.data().rows().size();
            }
            canFetchMore = more.truncated();
            updateMoreBar();
        }, ex -> {
            if (chunked != query) return;
            fetching = false;
            updateMoreBar();
            moreInfo.setText("Could not fetch more rows: " + ex.getMessage());
        });
    }

    /** Fetches the next chunk when the grid is scrolled to its last row. */
    private void watchScrolling() {
        grid.skinProperty().addListener((o, a, skin) -> javafx.application.Platform.runLater(() -> {
            for (javafx.scene.Node n : grid.lookupAll(".scroll-bar")) {
                if (n instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                    bar.valueProperty().addListener((ob, before, value) -> {
                        if (value.doubleValue() >= bar.getMax() && bar.getMax() > 0) fetchMore();
                    });
                }
            }
        }));
    }

    private void applyVisibility(boolean show) {
        if (show && !split.getItems().contains(inspectorHost)) {
            split.getItems().add(inspectorHost);
            split.setDividerPositions(divider);
        } else if (!show && split.getItems().contains(inspectorHost)) {
            divider = split.getDividerPositions()[0];
            split.getItems().remove(inspectorHost);
        }
    }

    /** A toolbar toggle for the value panel. */
    static ToggleButton valuePanelToggle() {
        ToggleButton toggle = new ToggleButton("Value Panel");
        toggle.setTooltip(new Tooltip("Show the selected cell's full value, formatted"));
        toggle.selectedProperty().bindBidirectional(SHOW_VALUE_PANEL);
        return toggle;
    }
}
