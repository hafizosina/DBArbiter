package com.dbadmin.ui;

import com.dbadmin.model.BlobValue;
import com.dbadmin.model.StatementResult;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.SqlSplitter;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        moreButton.setTooltip(new Tooltip("Or scroll to the last row"));
        editBar.setAlignment(Pos.CENTER_LEFT);
        editBar.setPadding(new Insets(3, 8, 3, 8));
        editBar.getStyleClass().add("edit-bar");
        editInfo.getStyleClass().add("hint-small");
        saveEdits.getStyleClass().add("default-look");
        saveEdits.setOnAction(e -> saveEdits());
        revertEdits.setOnAction(e -> revertAll());
        editBar.setVisible(false);
        editBar.setManaged(false);
        setBottom(new VBox(editBar, moreBar));
        // anything else showing data in the grid (e.g. a table page) ends the chunked result
        grid.itemsProperty().addListener((o, a, b) -> {
            // new rows: edits of the old ones are gone (callers ask first, see confirmDiscard)
            edits.clear();
            editTarget = null;
            notEditable = false;
            editInfo.setText("");
            updateEditBar();
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
            inspector.show(cell, cell == null ? null : cellEdits());
        }
    }

    /** Edits of the focused cell through the value panel, or null when it cannot be edited there. */
    private CellInspector.Edits cellEdits() {
        DataGrid.EditableCell cell = grid.focusedEditableCell();
        if (cell == null) return null;
        List<Object> row = cell.row();
        int column = cell.column();
        // binary values are not edited as text; booleans have their checkbox in the grid
        if (row.get(column) instanceof BlobValue || row.get(column) instanceof Boolean) return null;
        return new CellInspector.Edits() {
            @Override
            public Object value() {
                return row.get(column);
            }

            @Override
            public void set(Object value) {
                editCell(row, column, value);
                grid.refresh();
            }

            @Override
            public void revert() {
                revertCell(row, column);
                grid.refresh();
            }

            @Override
            public boolean isEdited() {
                return isCellEdited(row, column);
            }
        };
    }

    // ---- editing -----------------------------------------------------------

    private TableEditing.Target editTarget;
    /** Set while editInfo says why the rows shown cannot be edited. */
    private boolean notEditable;
    private final PendingEdits edits = new PendingEdits();
    private final Label editInfo = new Label();
    private final Button saveEdits = new Button("Save…");
    private final Button revertEdits = new Button("Revert all");
    private final HBox editBar = new HBox(8, editInfo, saveEdits, revertEdits);
    private Runnable onSaved = () -> { };

    /**
     * Makes the shown rows editable for {@code target}'s table: the columns that are table columns
     * (not the primary key, which finds the row). {@code onSaved} runs after a successful save.
     */
    void enableEditing(TableEditing.Target target, Runnable onSaved) {
        editTarget = target;
        notEditable = false;
        this.onSaved = onSaved;
        edits.clear();
        Set<Integer> columns = new HashSet<>();
        List<String> names = grid.columnNames();
        Set<String> pk = new HashSet<>();
        target.primaryKey().forEach(k -> pk.add(k.toLowerCase()));
        boolean keyShown = pk.stream().allMatch(k -> names.stream().anyMatch(n -> n.equalsIgnoreCase(k)));
        if (!target.readOnlyConnection() && keyShown) {
            for (int i = 0; i < names.size(); i++) {
                String n = names.get(i).toLowerCase();
                if (target.columns().contains(n) && !pk.contains(n)) columns.add(i);
            }
        }
        grid.setEditing(columns, new DataGrid.Editor() {
            @Override
            public void edited(List<Object> row, int column, Object value) {
                editCell(row, column, value);
            }

            @Override
            public boolean isEdited(List<Object> row, int column) {
                return isCellEdited(row, column);
            }

            @Override
            public void revert(List<Object> row, int column) {
                revertCell(row, column);
            }

            @Override
            public void editMultiLine() {
                SHOW_VALUE_PANEL.set(true);
                // after the double-click is done, or the grid takes the focus back
                Platform.runLater(inspector::focusText);
            }
        });
        // a cell selected before editing was on becomes editable in the value panel too
        showCell(grid.selectedCellProperty().get());
        if (target.readOnlyConnection()) {
            editInfo.setText("🔒 Read-only connection: untick \"Read-only\" in Edit Connection to edit these rows.");
        } else if (!keyShown) {
            editInfo.setText("Not editable: the primary key (" + String.join(", ", target.primaryKey()) + ") is not in the result.");
        }
        updateEditBar();
    }

    /** Says why the rows shown cannot be edited, until other rows are shown. */
    void showNotEditable(String reason) {
        notEditable = true;
        editInfo.setText("Not editable: " + reason + ".");
        updateEditBar();
    }

    boolean hasEdits() {
        inspector.applyPending(); // text typed in the value panel counts too
        return !edits.isEmpty();
    }

    /** Asks before dropping unsaved edits (paging, refresh, closing). True if there are none left. */
    boolean confirmDiscard() {
        if (!hasEdits()) return true;
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                edits.cellCount() + " edited cell(s) in " + edits.rowCount() + " row(s) are not saved. Discard them?",
                ButtonType.YES, ButtonType.NO);
        ask.setHeaderText("Unsaved changes");
        if (InAppDialogs.showAndWait(ask).orElse(ButtonType.NO) != ButtonType.YES) return false;
        revertAll();
        return true;
    }

    private void editCell(List<Object> row, int column, Object value) {
        edits.set(row, column, value);
        updateEditBar();
    }

    private boolean isCellEdited(List<Object> row, int column) {
        return edits.isEdited(row, column);
    }

    private void revertCell(List<Object> row, int column) {
        edits.revert(row, column);
        updateEditBar();
    }

    private void revertAll() {
        edits.revertAll();
        grid.refresh();
        updateEditBar();
    }

    private void updateEditBar() {
        boolean editable = editTarget != null && grid.isEditable();
        boolean show = editable || (editTarget != null || notEditable) && !editInfo.getText().isEmpty();
        editBar.setVisible(show);
        editBar.setManaged(show);
        saveEdits.setVisible(!edits.isEmpty());
        revertEdits.setVisible(!edits.isEmpty());
        if (!editable) return;
        if (edits.isEmpty()) {
            editInfo.setText("✎ Editable: double-click a cell or edit it in the value panel (right-click: Set NULL / Revert). "
                    + "Changes are saved only with Save.");
        } else {
            editInfo.setText(edits.cellCount() + " change(s) in " + edits.rowCount() + " row(s), not saved yet");
        }
    }

    /** Shows the UPDATE statements, asks, then applies them in one transaction. */
    private void saveEdits() {
        if (!hasEdits() || editTarget == null) return;
        TableEditing.Target target = editTarget;
        List<DatabaseSession.RowUpdate> updates = edits.updates(grid.columnNames(), target.primaryKey());
        DatabaseSession session = target.session();
        Async.run(session, () -> {
            List<String> sql = new ArrayList<>();
            for (var u : updates) sql.add(session.describeUpdate(target.table(), u) + ";");
            return sql;
        }, sql -> {
            ButtonType save = new ButtonType("Save " + updates.size() + " row(s)",
                    ButtonBar.ButtonData.OK_DONE);
            Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                    "Write to \"" + session.getConfig().getName() + "\" · " + target.table().displayName()
                            + " in one transaction. If any row was changed meanwhile, nothing is saved.",
                    save, ButtonType.CANCEL);
            ask.setHeaderText("Save " + updates.size() + " changed row(s)?");
            TextArea statements = new TextArea(String.join("\n", sql));
            statements.setEditable(false);
            statements.setPrefRowCount(Math.min(12, sql.size() + 1));
            statements.getStyleClass().add("sql-messages");
            ask.getDialogPane().setExpandableContent(statements);
            ask.getDialogPane().setExpanded(true);
            if (InAppDialogs.showAndWait(ask).orElse(ButtonType.CANCEL) != save) return;
            saveEdits.setDisable(true);
            editInfo.setText("Saving…");
            Async.run(session, () -> session.applyUpdates(target.table(), updates), n -> {
                saveEdits.setDisable(false);
                edits.clear();
                grid.refresh();
                updateEditBar();
                editInfo.setText("✔ Saved " + n + " row(s).");
                onSaved.run();
            }, ex -> {
                saveEdits.setDisable(false);
                updateEditBar();
                Alert error = new Alert(Alert.AlertType.ERROR,
                        ex.getMessage(), ButtonType.OK);
                error.setHeaderText("Nothing was saved");
                InAppDialogs.show(error);
            });
        }, ex -> editInfo.setText("Could not prepare the changes: " + ex.getMessage()));
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
        grid.skinProperty().addListener((o, a, skin) -> Platform.runLater(() -> {
            for (Node n : grid.lookupAll(".scroll-bar")) {
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
