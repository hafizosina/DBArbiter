package com.dbadmin.ui;

import com.dbadmin.model.BlobValue;
import com.dbadmin.model.StatementResult;
import com.dbadmin.model.TableData;
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

    private static final javafx.scene.input.KeyCombination SAVE_KEY = javafx.scene.input.KeyCombination.keyCombination("Shortcut+S");

    /** Show/hide the value panel everywhere at once. */
    private static final BooleanProperty SHOW_VALUE_PANEL = new SimpleBooleanProperty(true);
    /** One row as a list of column / value pairs, in every view that offers it. */
    private static final BooleanProperty RECORD_MODE = new SimpleBooleanProperty(false);
    /** A changed compare value as a git-style line diff (else source and target side by side), everywhere at once. */
    private static final BooleanProperty LINE_DIFF = new SimpleBooleanProperty(true);

    private final DataGrid grid = new DataGrid();
    private final CellInspector inspector = new CellInspector();
    /** For a compare result: the source value above, the target value below. */
    private final CellInspector targetInspector = new CellInspector();
    private final SplitPane pairPanel = new SplitPane();
    private final javafx.scene.layout.StackPane inspectorHost = new javafx.scene.layout.StackPane(inspector);
    /** For a compare result: a bar (legend, Line diff toggle) above the diff or the two values. */
    private final DiffView diffView = new DiffView();
    private final Label compareLegend = new Label();
    private final ToggleButton diffToggle = new ToggleButton("Line diff");
    private final javafx.scene.layout.StackPane compareBody = new javafx.scene.layout.StackPane();
    private final VBox comparePanel = new VBox();
    private final SplitPane split = new SplitPane(grid);
    private double divider = 0.7;
    private final RecordView record = new RecordView(grid, () -> this.canFetchMore, this::fetchMore);

    DataView() {
        grid.selectedCellProperty().addListener((o, a, cell) -> showCell(cell));
        // Ctrl+S here saves the rows' unsaved changes; without any it is left to the window (e.g. Save Script)
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (SAVE_KEY.match(e) && hasEdits() && editTarget != null) {
                e.consume();
                saveEdits();
            }
        });
        pairPanel.setOrientation(Orientation.VERTICAL);
        compareLegend.getStyleClass().add("hint-small");
        compareLegend.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(compareLegend, javafx.scene.layout.Priority.ALWAYS);
        diffToggle.selectedProperty().bindBidirectional(LINE_DIFF);
        diffToggle.setTooltip(new Tooltip("Show a changed value as a line-by-line diff (like git), or both values side by side"));
        diffToggle.setFocusTraversable(false);
        LINE_DIFF.addListener((o, a, b) -> showCell(grid.selectedCellProperty().get()));
        diffToggle.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE); // the legend shortens, never the toggle
        HBox compareBar = new HBox(8, diffToggle, compareLegend);
        compareBar.setAlignment(Pos.CENTER_LEFT);
        compareBar.setPadding(new Insets(4, 6, 4, 6));
        VBox.setVgrow(compareBody, javafx.scene.layout.Priority.ALWAYS);
        comparePanel.getChildren().setAll(compareBar, compareBody);
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
        // one line: the edit hint and its buttons on the left, the row count on the right
        javafx.scene.layout.Region gap = new javafx.scene.layout.Region();
        HBox.setHgrow(gap, javafx.scene.layout.Priority.ALWAYS);
        HBox bottom = new HBox(editBar, gap, moreBar);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.visibleProperty().bind(editBar.visibleProperty().or(moreBar.visibleProperty()));
        bottom.managedProperty().bind(bottom.visibleProperty());
        setBottom(bottom);
        // anything else showing data in the grid (e.g. a table page) ends the chunked result
        grid.itemsProperty().addListener((o, a, b) -> {
            // new rows: edits of the old ones are gone (callers ask first, see confirmDiscard)
            edits.clear();
            inserted.clear();
            editTarget = null;
            notEditable = false;
            editInfo.setText("");
            updateEditBar();
            if (!showingChunk) {
                chunked = null;
                closePaging();
                updateMoreBar();
            }
        });
        // off the screen (its tab closed): the paging connection is not needed any more
        sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) closePaging();
        });
        updateMoreBar();
        watchScrolling();
    }

    DataGrid grid() {
        return grid;
    }

    /** Follows the Record toggle (see {@link #recordToggle}); views without one stay a grid. */
    void offerRecordMode() {
        RECORD_MODE.addListener((o, a, on) -> showRecord(on));
        showRecord(RECORD_MODE.get());
    }

    private void showRecord(boolean on) {
        if (on == split.getItems().contains(record)) return;
        double[] dividers = split.getDividerPositions();
        if (on) {
            split.getItems().set(0, record);
            record.showFocusedRow();
        } else {
            split.getItems().set(0, grid);
            int row = grid.getFocusModel().getFocusedIndex();
            if (row >= 0) grid.scrollTo(row);
        }
        split.setDividerPositions(dividers);
    }

    /**
     * One view for a value; for a value from a compare, a line diff of what the target gets from the source
     * (red: the target's value now, green: the source's), when the sides differ or the row is on one side
     * only; else, or with Line diff off, both values, source above target.
     */
    private void showCell(DataGrid.CellValue cell) {
        // text typed for the cell shown before goes to that cell first, as a grid cell editor does when it loses focus
        inspector.applyPending();
        if (cell != null && cell.value() instanceof ComparedValue cv) {
            inspector.show(new DataGrid.CellValue(cell.column() + "  ·  " + cv.sourceName(), cell.type(),
                    cv.inSource() ? cv.source() : "(no row in " + cv.sourceName() + ")"));
            targetInspector.show(new DataGrid.CellValue(cell.column() + "  ·  " + cv.targetName(), cell.type(),
                    cv.inTarget() ? cv.target() : "(no row in " + cv.targetName() + ")"));
            if (pairPanel.getItems().isEmpty()) {
                pairPanel.getItems().setAll(inspector, targetInspector);
                inspectorHost.getChildren().setAll(comparePanel);
            }
            boolean differs = cv.valuesDiffer() || cv.inSource() != cv.inTarget();
            diffToggle.setDisable(!differs);
            if (differs && LINE_DIFF.get()) {
                // Source → Target: the target's value is the old one, the source's the new one
                diffView.show(cv.inTarget() ? text(cv.target()) : null, cv.inSource() ? text(cv.source()) : null, false);
                long[] n = diffView.counts();
                boolean onlyLineEnds = cv.inSource() && cv.inTarget() && cv.source() != null && cv.target() != null
                        && text(cv.source()).replace("\r\n", "\n").equals(text(cv.target()).replace("\r\n", "\n"));
                compareLegend.setText(cell.column() + (!cv.inTarget() ? "   + new in " + cv.targetName()
                        : !cv.inSource() ? "   − only in " + cv.targetName()
                        : "   − " + cv.targetName() + " (" + n[0] + ")   + " + cv.sourceName() + " (" + n[1] + ")")
                        + (onlyLineEnds ? "   · only the line endings differ (Windows CRLF ␍ vs LF)" : ""));
                compareLegend.setTooltip(new Tooltip(compareLegend.getText().strip())); // the panel may be too narrow for it
                compareBody.getChildren().setAll(diffView);
            } else {
                compareLegend.setText(cell.column() + (differs ? "" : cv.inSource() && cv.inTarget() ? "   · same on both sides" : ""));
                compareLegend.setTooltip(null);
                compareBody.getChildren().setAll(pairPanel);
            }
        } else {
            if (!pairPanel.getItems().isEmpty()) {
                pairPanel.getItems().clear();
                inspectorHost.getChildren().setAll(inspector);
            }
            inspector.show(cell, cell == null ? null : cellEdits());
        }
    }

    private static String text(Object v) {
        return v == null ? "NULL" : v.toString();
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
    /** The caller shows the rows again after a save. */
    private boolean savedReloads;
    /** New rows inserted by the last save while the rows were not read again: shown, no longer editable. */
    private final List<List<Object>> inserted = new ArrayList<>();

    /** By identity: two rows can hold equal values. */
    private static int indexOfRow(List<List<Object>> rows, List<Object> row) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == row) return i;
        }
        return -1;
    }

    private static boolean containsRow(List<List<Object>> rows, List<Object> row) {
        return indexOfRow(rows, row) >= 0;
    }

    private void removeRow(List<Object> row) {
        int i = indexOfRow(grid.getItems(), row);
        if (i >= 0) grid.getItems().remove(i);
    }

    /**
     * Makes the shown rows editable for {@code target}'s table: the columns that are table columns
     * (not the primary key, which finds the row); new rows get every table column. Rows can be added,
     * duplicated and deleted. {@code onSaved} runs after a successful save; {@code reloads}: it shows the rows
     * again (with the values the database gave new rows), else new rows stay as inserted, no longer editable.
     */
    void enableEditing(TableEditing.Target target, Runnable onSaved, boolean reloads) {
        editTarget = target;
        notEditable = false;
        this.onSaved = onSaved;
        this.savedReloads = reloads;
        edits.clear();
        inserted.clear();
        Set<Integer> columns = new HashSet<>();
        Set<Integer> newRowColumns = new HashSet<>();
        List<String> names = grid.columnNames();
        Set<String> pk = new HashSet<>();
        target.primaryKey().forEach(k -> pk.add(k.toLowerCase()));
        boolean keyShown = pk.stream().allMatch(k -> names.stream().anyMatch(n -> n.equalsIgnoreCase(k)));
        if (!target.readOnlyConnection() && keyShown) {
            for (int i = 0; i < names.size(); i++) {
                String n = names.get(i).toLowerCase();
                if (!target.columns().contains(n)) continue;
                newRowColumns.add(i);
                if (!pk.contains(n)) columns.add(i);
            }
        }
        // a duplicate copies what the user would retype; the key and generated values are left to the database
        Set<Integer> copied = new HashSet<>();
        for (int i : newRowColumns) {
            String n = names.get(i).toLowerCase();
            if (!pk.contains(n) && !target.generated().contains(n)) copied.add(i);
        }
        grid.setEditing(columns, newRowColumns, new DataGrid.Editor() {
            @Override
            public DataGrid.RowState state(List<Object> row) {
                if (edits.isAdded(row)) return DataGrid.RowState.NEW;
                if (edits.isDeleted(row)) return DataGrid.RowState.DELETED;
                return containsRow(inserted, row) ? DataGrid.RowState.SAVED_NEW : DataGrid.RowState.SHOWN;
            }

            @Override
            public void addRow(List<Object> after, List<Object> copyOf) {
                List<Object> row = new ArrayList<>();
                for (int i = 0; i < names.size(); i++) {
                    Object v = copyOf == null || !copied.contains(i) ? PendingEdits.Default.VALUE : copyOf.get(i);
                    // a binary value read only in part cannot be copied
                    row.add(v instanceof BlobValue b && b.isTruncated() ? PendingEdits.Default.VALUE : v);
                }
                edits.add(row);
                int at = after == null ? -1 : indexOfRow(grid.getItems(), after);
                int index = at < 0 ? grid.getItems().size() : at + 1;
                grid.getItems().add(index, row);
                grid.getSelectionModel().clearAndSelect(index, grid.getVisibleLeafColumns().isEmpty() ? null : grid.getVisibleLeafColumns().get(0));
                grid.scrollTo(Math.max(0, index - 3));
                grid.refresh();
                updateEditBar();
            }

            @Override
            public void deleteRows(List<List<Object>> rows) {
                for (List<Object> row : rows) {
                    if (containsRow(inserted, row)) continue;
                    if (edits.delete(row)) removeRow(row);
                }
                grid.refresh();
                updateEditBar();
            }

            @Override
            public void restoreRows(List<List<Object>> rows) {
                rows.forEach(edits::restore);
                grid.refresh();
                updateEditBar();
            }

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

    /** Unsaved edits, counting text typed in the value panel and not applied yet. Changes nothing. */
    boolean hasEdits() {
        return !edits.isEmpty() || inspector.hasPendingText();
    }

    /** Asks before dropping unsaved edits (paging, refresh, closing). True if there are none left. */
    boolean confirmDiscard() {
        if (!hasEdits()) return true;
        inspector.applyPending(); // typed text counts as an edit in the question
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                "Changes to " + edits.summary() + " are not saved. Discard them?",
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
        edits.revertAll().forEach(this::removeRow);
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
        record.refresh();
        if (!editable) return;
        if (edits.isEmpty()) {
            editInfo.setText("✎ Editable · double-click a cell · right-click to add or delete rows · saved only with Save");
        } else {
            editInfo.setText(edits.summary() + ", not saved yet");
        }
    }

    /** Shows the DELETE, UPDATE and INSERT statements, asks, then applies them in one transaction. */
    private void saveEdits() {
        inspector.applyPending(); // text typed in the value panel is saved too
        if (edits.isEmpty() || editTarget == null) return;
        TableEditing.Target target = editTarget;
        List<String> names = grid.columnNames();
        DatabaseSession.RowChanges changes;
        try {
            changes = new DatabaseSession.RowChanges(edits.deletes(names, target.primaryKey()),
                    edits.updates(names, target.primaryKey()), edits.inserts(names));
        } catch (IllegalStateException ex) {
            editInfo.setText("Could not prepare the changes: " + ex.getMessage());
            return;
        }
        String summary = edits.summary();
        DatabaseSession session = target.session();
        Async.run(session, () -> {
            List<String> sql = new ArrayList<>();
            for (String statement : session.describeChanges(target.table(), changes)) sql.add(statement + ";");
            return sql;
        }, sql -> {
            ButtonType save = new ButtonType("Save " + changes.size() + " row(s)",
                    ButtonBar.ButtonData.OK_DONE);
            Alert ask = new Alert(changes.deletes().isEmpty() ? Alert.AlertType.CONFIRMATION : Alert.AlertType.WARNING,
                    "Write to \"" + session.getConfig().getName() + "\" · " + target.table().displayName()
                            + " in one transaction. If any row was changed meanwhile, nothing is saved.",
                    save, ButtonType.CANCEL);
            ask.setHeaderText("Save " + summary + "?");
            TextArea statements = new TextArea(String.join("\n", sql));
            statements.setEditable(false);
            statements.setWrapText(true); // a long UPDATE wraps instead of needing a sideways scroll
            statements.setPrefRowCount(Math.max(8, Math.min(20, sql.size() + 2)));
            statements.getStyleClass().add("sql-messages");
            // wide enough for the statements (up to a limit), so the text and buttons are not cut off
            int longest = sql.stream().mapToInt(String::length).max().orElse(0);
            ask.getDialogPane().setPrefWidth(Math.max(560, Math.min(1000, longest * 7.5 + 80)));
            // to run them by hand instead (e.g. in a script, or by a DBA)
            Button copy = new Button("Copy SQL");
            copy.setOnAction(e -> {
                javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
                c.putString(statements.getText());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
                copy.setText("Copied");
            });
            VBox details = new VBox(6, statements, copy);
            VBox.setVgrow(statements, javafx.scene.layout.Priority.ALWAYS);
            ask.getDialogPane().setExpandableContent(details);
            ask.getDialogPane().setExpanded(true);
            if (InAppDialogs.showAndWait(ask).orElse(ButtonType.CANCEL) != save) return;
            saveEdits.setDisable(true);
            editInfo.setText("Saving…");
            List<List<Object>> deletedRows = grid.getItems().stream().filter(edits::isDeleted).toList();
            List<List<Object>> newRows = grid.getItems().stream().filter(edits::isAdded).toList();
            Async.run(session, () -> session.applyChanges(target.table(), changes), n -> {
                saveEdits.setDisable(false);
                edits.clear();
                deletedRows.forEach(this::removeRow);
                if (!savedReloads) inserted.addAll(newRows);
                grid.refresh();
                updateEditBar();
                editInfo.setText("✔ Saved " + n + " row(s)." + (!savedReloads && !newRows.isEmpty()
                        ? " Run the query again to edit the new rows." : ""));
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
    /** No ORDER BY: the rest, read by a second run of the query, may not line up with the first chunk. */
    private boolean unordered;
    /**
     * The rest of the result after the first chunk: the query, run once more on a connection of its own, kept open
     * and read chunk by chunk (instead of re-running it for every chunk). Opened at the first "more"; closed with it.
     */
    private DatabaseSession pagingSession;
    private DatabaseSession.RowCursor cursor;
    private boolean fetching;
    private boolean showingChunk;

    /**
     * Shows a query result whose first chunk was fetched. If more rows exist and the query only reads,
     * the next chunk is fetched by scrolling to the end or with "Fetch next"; other statements are
     * never re-run, so they stay at their first chunk.
     */
    void showResult(StatementResult result, DatabaseSession session) {
        closePaging();
        showingChunk = true;
        grid.show(result.data());
        showingChunk = false;
        chunked = result;
        chunkSession = session;
        fetched = result.data().rows().size();
        canFetchMore = result.truncated() && SqlSplitter.isReadOnlyQuery(result.sql());
        unordered = !SqlSplitter.hasOrderBy(result.sql(), session.getConfig().getType());
        fetching = false;
        updateMoreBar();
    }

    private void updateMoreBar() {
        boolean show = chunked != null;
        moreBar.setVisible(show);
        moreBar.setManaged(show);
        moreButton.setVisible(canFetchMore);
        moreButton.setDisable(fetching);
        if (fetching) {
            moreInfo.setText(rows(fetched) + " · fetching more…");
        } else if (canFetchMore) {
            moreInfo.setText(rows(fetched) + " · more available" + (unordered ? " · no ORDER BY: the rest may not line up" : ""));
        } else if (chunked != null && chunked.truncated() && fetched == chunked.data().rows().size()) {
            moreInfo.setText("First " + rows(fetched) + " (this statement is not re-run for more)");
        } else {
            moreInfo.setText(rows(fetched));
        }
        moreInfo.setTooltip(unordered && canFetchMore ? new Tooltip("The rest is read by running the query once more; without "
                + "ORDER BY its rows may come in another order,\nso a row of the first chunk may show again or one may be missed") : null);
    }

    private static String rows(int n) {
        return String.format("%,d", n) + (n == 1 ? " row" : " rows");
    }

    /**
     * The next chunk. The first time, the query runs once more on a connection of its own, kept open: it passes over
     * the rows shown and reads on; after that each chunk is read from it, with no re-run.
     */
    private void fetchMore() {
        if (!canFetchMore || fetching || chunked == null) return;
        StatementResult query = chunked;
        fetching = true;
        updateMoreBar();
        java.util.function.Consumer<Throwable> failed = ex -> {
            if (chunked != query) return;
            fetching = false;
            closePaging();
            updateMoreBar();
            moreInfo.setText("Could not fetch more rows: " + ex.getMessage());
        };
        if (cursor != null) {
            DatabaseSession s = pagingSession;
            DatabaseSession.RowCursor c = cursor;
            Async.run(s, () -> c.next(ScriptRunner.FETCH_SIZE), more -> showMore(query, more, c.hasMore()), failed);
            return;
        }
        com.dbadmin.model.ConnectionConfig config = chunkSession.getConfig();
        int skip = fetched;
        Async.run(() -> {
            DatabaseSession s = DatabaseSession.open(config);
            try {
                DatabaseSession.RowCursor c = s.submit(() -> s.openCursor(query.sql(), query.params(), skip)).get();
                TableData more = s.submit(() -> c.next(ScriptRunner.FETCH_SIZE)).get();
                return new Object[]{s, c, more};
            } catch (Exception e) {
                s.close();
                throw e instanceof java.util.concurrent.ExecutionException && e.getCause() instanceof Exception cause ? cause : e;
            }
        }, opened -> {
            DatabaseSession s = (DatabaseSession) opened[0];
            DatabaseSession.RowCursor c = (DatabaseSession.RowCursor) opened[1];
            if (chunked != query) {
                s.close(); // another result is shown now
                return;
            }
            pagingSession = s;
            cursor = c;
            showMore(query, (TableData) opened[2], c.hasMore());
        }, failed);
    }

    private void showMore(StatementResult query, TableData more, boolean hasMore) {
        if (chunked != query) return; // another result is shown now
        fetching = false;
        grid.getItems().addAll(more.rows());
        fetched += more.rows().size();
        canFetchMore = hasMore;
        if (!hasMore) closePaging(); // all read
        updateMoreBar();
    }

    /** Closes the paging connection, if one is open (its cursor and transaction end with it). */
    private void closePaging() {
        if (pagingSession != null) pagingSession.close();
        pagingSession = null;
        cursor = null;
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

    /** A toolbar toggle for record mode: one row at a time, its columns listed down the page. */
    static ToggleButton recordToggle() {
        ToggleButton toggle = new ToggleButton("Record");
        toggle.setTooltip(new Tooltip("Show one row at a time as a list of columns and values; ◀ ▶ step through the rows"));
        toggle.selectedProperty().bindBidirectional(RECORD_MODE);
        return toggle;
    }

    /** A toolbar toggle for the value panel. */
    static ToggleButton valuePanelToggle() {
        ToggleButton toggle = new ToggleButton("Value Panel");
        toggle.setTooltip(new Tooltip("Show the selected cell's full value, formatted"));
        toggle.selectedProperty().bindBidirectional(SHOW_VALUE_PANEL);
        return toggle;
    }
}
