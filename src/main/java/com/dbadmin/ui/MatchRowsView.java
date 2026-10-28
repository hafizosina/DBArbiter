package com.dbadmin.ui;

import com.dbadmin.service.scan.DataScan;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The rows behind one finding: their key, and the value around its first match with the match highlighted; the
 * selected row's whole value below, every match highlighted. Values are shown exactly as stored.
 */
class MatchRowsView extends VBox {

    /** Characters shown before and after the first match in the list. */
    private static final int AROUND = 40;

    private record Row(List<Object> key, String value) {
    }

    private final Label header = new Label("Double-click a finding to see its rows.");
    private final TableView<Row> rows = new TableView<>();
    private final CodeArea full = new CodeArea();
    private List<String> values = List.of();
    private boolean whole;
    private boolean ignoreCase;

    MatchRowsView() {
        super(6);
        setPadding(new Insets(6));
        header.getStyleClass().add("hint-small");
        rows.setPlaceholder(new Label("No rows"));
        rows.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        rows.setFixedCellSize(24); // one line per row, like the grids
        full.setEditable(false);
        full.setWrapText(true);
        full.getStyleClass().add("sql-editor");
        rows.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> showFull(r));
        SplitPane split = new SplitPane(rows, FindBar.over(full, new VirtualizedScrollPane<>(full)));
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.6);
        VBox.setVgrow(split, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(header, split);
    }

    /** The values listed now (for tests). */
    List<String> shownValues() {
        return rows.getItems().stream().map(Row::value).toList();
    }

    void loading(String what) {
        header.setText("Reading " + what + "…");
        rows.getItems().clear();
        full.clear();
    }

    void failed(String message) {
        header.setText(message);
    }

    /** The rows of {@code what} ("schema.table.column"), matched as the scan matched them. */
    void show(String what, DataScan.MatchRows found, List<String> values, boolean whole, boolean ignoreCase) {
        this.values = values;
        this.whole = whole;
        this.ignoreCase = ignoreCase;
        header.setText(what + " · " + found.values().size() + (found.more() ? "+" : "") + " row(s)"
                + (found.more() ? " (the first " + found.values().size() + ")" : "")
                + (found.holdsJson() ? " · values are JSON" : "")
                + (found.keyColumns().isEmpty() ? " · no primary key" : ""));
        rows.getColumns().clear();
        for (int i = 0; i < found.keyColumns().size(); i++) {
            int k = i;
            TableColumn<Row, Object> key = new TableColumn<>(found.keyColumns().get(i));
            key.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().key().get(k)));
            key.setPrefWidth(90);
            key.setMaxWidth(200);
            rows.getColumns().add(key);
        }
        TableColumn<Row, String> match = new TableColumn<>("Value around the match");
        match.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().value()));
        match.setCellFactory(c -> new SnippetCell());
        rows.getColumns().add(match);
        List<Row> list = new ArrayList<>();
        for (int i = 0; i < found.values().size(); i++) list.add(new Row(found.keys().get(i), found.values().get(i)));
        rows.getItems().setAll(list);
        if (!list.isEmpty()) rows.getSelectionModel().selectFirst();
    }

    private void showFull(Row r) {
        if (r == null || r.value() == null) {
            full.replaceText(r == null ? "" : "NULL");
            return;
        }
        String text = r.value();
        full.replaceText(text);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int at = 0;
        for (int[] m : DataScan.matches(text, values, whole, ignoreCase)) {
            spans.add(List.of(), m[0] - at);
            spans.add(List.of("scan-match"), m[1] - m[0]);
            at = m[1];
        }
        spans.add(List.of(), text.length() - at);
        full.setStyleSpans(0, spans.create());
        full.moveTo(0);
        full.requestFollowCaret();
    }

    /** One line around the first match, the match highlighted; line breaks shown as ↵. */
    private class SnippetCell extends TableCell<Row, String> {
        @Override
        protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            setText(null);
            if (empty || value == null) {
                setGraphic(empty ? null : new Label("NULL"));
                return;
            }
            List<int[]> ms = DataScan.matches(value, values, whole, ignoreCase);
            if (ms.isEmpty()) {
                setGraphic(new Label(DataGrid.oneLine(value)));
                return;
            }
            int[] m = ms.get(0);
            int from = Math.max(0, m[0] - AROUND);
            int to = Math.min(value.length(), m[1] + AROUND);
            Label before = new Label((from > 0 ? "…" : "") + oneLine(value.substring(from, m[0])));
            Label hit = new Label(oneLine(value.substring(m[0], m[1])));
            hit.getStyleClass().add("scan-match-label");
            Label after = new Label(oneLine(value.substring(m[1], to)) + (to < value.length() ? "…" : "")
                    + (ms.size() > 1 ? "   (" + ms.size() + " matches)" : ""));
            // one line: the text before and the match keep their width, what follows is cut at the cell's edge
            before.setMinWidth(Region.USE_PREF_SIZE);
            hit.setMinWidth(Region.USE_PREF_SIZE);
            HBox line = new HBox(before, hit, after);
            line.setMinWidth(0);
            setGraphic(line);
        }

        private String oneLine(String s) {
            return s.replace("\r\n", "↵").replace('\n', '↵').replace('\r', '↵');
        }
    }
}
