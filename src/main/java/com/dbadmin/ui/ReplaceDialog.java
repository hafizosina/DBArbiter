package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.ColumnSpec;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.scan.DataScan;
import com.dbadmin.service.scan.ReplaceSql;
import com.dbadmin.service.scan.ScanColumn;
import com.dbadmin.service.scan.ScanFinding;
import com.dbadmin.service.scan.ScanRequest;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replace… after a scan: each found value with the value it becomes, the columns to change (columns of a key and
 * generated ones never), what else to set in a changed row ("Also set", saved per connection), the checks, and a
 * before / after preview of a few rows. The result is the script ({@link ReplaceSql}), opened in a script tab by the
 * caller: nothing is changed here.
 */
class ReplaceDialog extends Dialog<String> {

    /** Rows previewed per column. */
    private static final int PREVIEW_ROWS = 2;

    /** A column that holds a value, and whether to change it. */
    record ColumnChoice(ScanFinding finding, BooleanProperty on, String excluded) {
        String label() {
            long exact = finding.exactRows().isEmpty() ? finding.rows() : finding.exactRows().stream().mapToLong(Long::longValue).sum();
            return finding.table().displayName() + "." + finding.column().name() + "   (" + exact + " match" + (exact == 1 ? "" : "es") + ")"
                    + (excluded != null ? "   — " + excluded : "");
        }
    }

    /** What the dialog read about a table: its columns as defined, key and foreign key columns. */
    private record TableFacts(Map<String, ColumnSpec> columns, Set<String> keyColumns) {
    }

    private final ConnectionConfig config;
    private final DatabaseSession session;
    private final ScanRequest request;
    private final List<TextField> names = new ArrayList<>();
    private final List<TextField> news = new ArrayList<>();
    private final CheckList<ColumnChoice> columns = new CheckList<>(ColumnChoice::on, ColumnChoice::label);
    private final VBox alsoSetRows = new VBox(4);
    private final Label checks = new Label();
    private final CodeArea preview = new CodeArea();
    private final ButtonType create = new ButtonType("Create script", ButtonBar.ButtonData.OK_DONE);
    /** Kept: while the dialog is shown inside the main window, getDialogPane() is a placeholder (see InAppDialogs). */
    private Button createButton;
    private final Map<TableRef, TableFacts> facts = new HashMap<>();
    /** Sample rows per finding (exact case, as REPLACE changes them). */
    private final Map<ScanFinding, List<String>> samples = new HashMap<>();
    /** The longest value per varchar(n) column after the replace, once read. */
    private final Map<ScanFinding, Long> longest = new HashMap<>();
    private final List<String> suggestions = new ArrayList<>();
    private final com.dbadmin.service.scan.AlsoSetStore store;
    private boolean loaded;

    ReplaceDialog(Window owner, ConnectionConfig config, DatabaseSession session, ScanRequest request, List<ScanFinding> findings,
                  com.dbadmin.service.scan.AlsoSetStore store) {
        this.config = config;
        this.session = session;
        this.request = request;
        this.store = store;
        if (owner != null) initOwner(owner);
        setTitle("Replace");
        setHeaderText("Replace on \"" + config.getName() + "\": a script to review and run; nothing is changed here");
        setResizable(true);

        // the values
        GridPane pairs = new GridPane();
        pairs.setHgap(8);
        pairs.setVgap(4);
        ColumnConstraints fill = new ColumnConstraints();
        fill.setHgrow(Priority.ALWAYS);
        fill.setPercentWidth(42);
        ColumnConstraints fill2 = new ColumnConstraints();
        fill2.setHgrow(Priority.ALWAYS);
        fill2.setPercentWidth(42);
        pairs.getColumnConstraints().addAll(new ColumnConstraints(110), fill, new ColumnConstraints(), fill2);
        pairs.addRow(0, small("Name"), small("Found (replaced exactly as typed)"), new Label(""), small("Becomes (empty: not replaced)"));
        for (int i = 0; i < request.values().size(); i++) {
            String v = request.values().get(i);
            TextField name = new TextField();
            name.setPromptText("e.g. facebook");
            TextField old = new TextField(v);
            old.setEditable(false);
            old.setTooltip(new Tooltip(v));
            TextField neu = new TextField();
            neu.setPromptText("new value");
            names.add(name);
            news.add(neu);
            pairs.addRow(i + 1, name, old, new Label("→"), neu);
            neu.textProperty().addListener((o, a, b) -> refresh());
        }

        // the columns
        for (ScanFinding f : findings) {
            boolean exactSome = f.exactRows().isEmpty() || f.exactRows().stream().anyMatch(n -> n > 0);
            ColumnChoice c = new ColumnChoice(f, new SimpleBooleanProperty(exactSome), exactSome ? null : "only in another case");
            c.on().addListener((o, a, b) -> refresh());
            columns.items().add(c);
        }
        columns.listView().setPrefHeight(130);

        // also set
        Button addAlso = new Button("+ Add");
        addAlso.setTooltip(new Tooltip("A column to set in every changed row of the tables that have it, e.g. modified_date = NOW()"));
        addAlso.setOnAction(e -> addAlsoSet(new ReplaceSql.AlsoSet("", "")));
        store.load(config.getId()).forEach(this::addAlsoSet);

        checks.setWrapText(true);
        checks.getStyleClass().add("hint-small");
        preview.setEditable(false);
        preview.setWrapText(true);
        preview.getStyleClass().add("sql-editor");
        VirtualizedScrollPane<CodeArea> previewPane = new VirtualizedScrollPane<>(preview);
        previewPane.setPrefHeight(200);

        VBox content = new VBox(8,
                section("Values"), pairs,
                section("Columns to change"), columns,
                section("Also set in each changed row"), alsoSetRows, addAlso,
                section("Checks"), checks,
                section("Preview (exact case, as the script changes them)"), previewPane);
        content.setPadding(new Insets(10, 14, 10, 14));
        VBox.setVgrow(previewPane, Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(980, 560); // fits a laptop screen; the rest scrolls
        getDialogPane().setContent(scroll);
        getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
        createButton = (Button) getDialogPane().lookupButton(create);
        setResultConverter(b -> {
            if (b != create) return null;
            store.save(config.getId(), alsoSet());
            return ReplaceSql.script(config.getType(), plan());
        });
        refresh();
        load(findings);
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-title");
        l.setStyle("-fx-font-size: 13px;");
        return l;
    }

    private static Label small(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint-small");
        return l;
    }

    private void addAlsoSet(ReplaceSql.AlsoSet a) {
        ComboBox<String> column = new ComboBox<>(FXCollections.observableArrayList(suggestions));
        column.setEditable(true);
        column.setPromptText("column");
        column.getEditor().setText(a.column());
        column.setPrefWidth(220);
        TextField value = new TextField(a.expression());
        value.setPromptText("SQL value, e.g. NOW() or 'SYSTEM'");
        HBox.setHgrow(value, Priority.ALWAYS);
        Button remove = new Button("✕");
        HBox row = new HBox(6, column, new Label("="), value, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        remove.setOnAction(e -> {
            alsoSetRows.getChildren().remove(row);
            refresh();
        });
        column.getEditor().textProperty().addListener((o, x, y) -> refresh());
        value.textProperty().addListener((o, x, y) -> refresh());
        alsoSetRows.getChildren().add(row);
        refresh();
    }

    @SuppressWarnings("unchecked")
    List<ReplaceSql.AlsoSet> alsoSet() {
        List<ReplaceSql.AlsoSet> list = new ArrayList<>();
        for (var n : alsoSetRows.getChildren()) {
            HBox row = (HBox) n;
            String column = ((ComboBox<String>) row.getChildren().get(0)).getEditor().getText().strip();
            String value = ((TextField) row.getChildren().get(2)).getText().strip();
            if (!column.isEmpty() && !value.isEmpty()) list.add(new ReplaceSql.AlsoSet(column, value));
        }
        return list;
    }

    /** The values with a new value; the others are not replaced. */
    List<ReplaceSql.Pair> pairs() {
        List<ReplaceSql.Pair> list = new ArrayList<>();
        for (int i = 0; i < request.values().size(); i++) {
            if (!news.get(i).getText().isEmpty()) list.add(new ReplaceSql.Pair(names.get(i).getText(), request.values().get(i), news.get(i).getText()));
        }
        return list;
    }

    /** The ticked columns, by table (in the order found), with every column of each table for Also set. */
    ReplaceSql.Plan plan() {
        Map<TableRef, List<ScanColumn>> byTable = new LinkedHashMap<>();
        for (ColumnChoice c : columns.items()) {
            if (c.on().get() && c.excluded() == null) byTable.computeIfAbsent(c.finding().table(), k -> new ArrayList<>()).add(c.finding().column());
        }
        List<ReplaceSql.Target> targets = new ArrayList<>();
        byTable.forEach((t, cols) -> targets.add(new ReplaceSql.Target(t, cols,
                facts.containsKey(t) ? facts.get(t).columns().keySet() : Set.of())));
        return new ReplaceSql.Plan(pairs(), targets, alsoSet());
    }

    // ---- reading what the checks need ----

    /** Per table: columns as defined, key columns; per column: a few sample rows. Then the checks can run. */
    private void load(List<ScanFinding> findings) {
        checks.setText("Reading the tables…");
        ScanRequest exact = new ScanRequest(request.values(), request.wholeValue(), false, List.of(), request.timeoutSeconds());
        Async.run(session, () -> {
            Map<TableRef, TableFacts> read = new HashMap<>();
            Map<ScanFinding, List<String>> rows = new HashMap<>();
            for (ScanFinding f : findings) {
                if (!read.containsKey(f.table())) {
                    Map<String, ColumnSpec> cols = new LinkedHashMap<>();
                    session.columnSpecs(f.table()).forEach(c -> cols.put(c.name(), c));
                    Set<String> keys = new HashSet<>(session.primaryKey(f.table()));
                    for (List<Object> fk : session.listForeignKeys(f.table()).rows()) {
                        for (String c : String.valueOf(fk.get(1)).split(",\\s*")) keys.add(c);
                    }
                    read.put(f.table(), new TableFacts(cols, keys));
                }
                rows.put(f, DataScan.matchingRows(session, exact, f.table(), f.column(), PREVIEW_ROWS).values());
            }
            return Map.entry(read, rows);
        }, done -> {
            facts.putAll(done.getKey());
            samples.putAll(done.getValue());
            facts.values().forEach(t -> t.columns().keySet().forEach(c -> {
                if (!suggestions.contains(c)) suggestions.add(c);
            }));
            suggestions.sort(String.CASE_INSENSITIVE_ORDER);
            alsoSetRows.getChildren().forEach(n -> {
                @SuppressWarnings("unchecked") ComboBox<String> box = (ComboBox<String>) ((HBox) n).getChildren().get(0);
                box.getItems().setAll(suggestions);
            });
            excludeUnchangeable();
            loaded = true;
            refresh();
        }, ex -> checks.setText("Could not read the tables: " + ex.getMessage()));
    }

    /** Columns of a key and generated columns are never changed: unticked, with the reason. */
    private void excludeUnchangeable() {
        List<ColumnChoice> updated = new ArrayList<>();
        for (ColumnChoice c : columns.items()) {
            TableFacts t = facts.get(c.finding().table());
            String name = c.finding().column().name();
            ColumnSpec spec = t == null ? null : t.columns().get(name);
            String why = c.excluded();
            if (t != null && t.keyColumns().contains(name)) why = "part of a key: not changed";
            else if (spec != null && spec.generated()) why = "generated: not changed";
            ColumnChoice next = why == null ? c : new ColumnChoice(c.finding(), new SimpleBooleanProperty(false), why);
            if (next != c) next.on().addListener((o, a, b) -> refresh());
            updated.add(next);
        }
        columns.setItems(updated);
    }

    // ---- checks and preview ----

    private void refresh() {
        List<String> notes = new ArrayList<>();
        List<ReplaceSql.Pair> pairs = pairs();
        ReplaceSql.Plan plan = plan();
        int skipped = request.values().size() - pairs.size();
        if (pairs.isEmpty()) notes.add("Type what each value becomes.");
        else if (skipped > 0) notes.add(skipped + " value(s) without a new value: not replaced.");
        if (plan.targets().isEmpty()) notes.add("Tick a column to change.");
        notes.addAll(ReplaceSql.checks(pairs));
        notes.addAll(ReplaceSql.alsoSetMissing(plan));
        for (ColumnChoice c : columns.items()) {
            ScanFinding f = c.finding();
            if (!f.exactRows().isEmpty()) {
                long other = 0;
                for (int i = 0; i < f.valueRows().size(); i++) other += f.valueRows().get(i) - f.exactRows().get(i);
                if (other > 0) notes.add(f.table().name() + "." + f.column().name() + ": " + other
                        + " row(s) hold a value only in another case: not replaced (add that spelling as a value to replace it too).");
            }
            if (c.on().get() && c.excluded() == null) jsonCheck(c, pairs, notes);
        }
        longestCheck(plan, notes);
        if (!loaded) notes.add("Reading the tables…");
        checks.setText(notes.isEmpty() ? "Nothing to note." : "• " + String.join("\n• ", notes));
        if (createButton != null) createButton.setDisable(!loaded || pairs.isEmpty() || plan.targets().isEmpty()); // null while the dialog is built
        showPreview(pairs);
    }

    /** A text column whose samples are JSON must still be JSON after the replace (a json / jsonb cast checks itself). */
    private void jsonCheck(ColumnChoice c, List<ReplaceSql.Pair> pairs, List<String> notes) {
        if (c.finding().column().kind() == ScanColumn.Kind.JSON) return;
        for (String before : samples.getOrDefault(c.finding(), List.of())) {
            if (before != null && DataScan.isJson(before) && !DataScan.isJson(ReplaceSql.apply(before, pairs))) {
                notes.add(c.finding().table().name() + "." + c.finding().column().name()
                        + " holds JSON, and a value would no longer be valid JSON after the replace (a quote or backslash in a new value?).");
                return;
            }
        }
    }

    /** The new values the longest lengths were last asked for, and those they were read for (they may differ while reading). */
    private String longestAsked = "";
    private String longestRead = "";

    /** varchar(n) / char(n) columns: the longest value after the replace must fit; read once per set of new values. */
    private void longestCheck(ReplaceSql.Plan plan, List<String> notes) {
        if (!loaded || plan.pairs().isEmpty()) return;
        String key = plan.pairs().toString();
        if (key.equals(longestRead)) {
            for (ColumnChoice c : columns.items()) {
                if (!c.on().get() || c.excluded() != null) continue;
                ColumnSpec spec = spec(c);
                int max = spec == null ? -1 : ReplaceSql.declaredLength(spec.type());
                Long after = longest.get(c.finding());
                if (max > 0 && after != null && after > max) {
                    notes.add(c.finding().table().name() + "." + c.finding().column().name() + " is " + spec.type()
                            + ", but its longest value becomes " + after + " characters: the UPDATE would fail.");
                }
            }
            return;
        }
        if (key.equals(longestAsked)) return; // being read
        longestAsked = key;
        List<ColumnChoice> limited = columns.items().stream().filter(c -> spec(c) != null && ReplaceSql.declaredLength(spec(c).type()) > 0).toList();
        List<ReplaceSql.Pair> pairs = plan.pairs();
        Async.run(session, () -> {
            Map<ScanFinding, Long> read = new HashMap<>();
            for (ColumnChoice c : limited) {
                List<String> params = new ArrayList<>();
                String sql = ReplaceSql.longestAfterQuery(config.getType(), c.finding().table(), c.finding().column(), pairs, params);
                read.put(c.finding(), session.countRow(sql, params, request.timeoutSeconds()).get(0));
            }
            return read;
        }, read -> {
            if (!key.equals(longestAsked)) return; // the new values changed meanwhile: that read is coming
            longest.clear();
            longest.putAll(read);
            longestRead = key;
            refresh();
        }, ex -> { });
    }

    private ColumnSpec spec(ColumnChoice c) {
        TableFacts t = facts.get(c.finding().table());
        return t == null ? null : t.columns().get(c.finding().column().name());
    }

    /** A few rows per ticked column: the value before (old values marked) and after (new values marked). */
    private void showPreview(List<ReplaceSql.Pair> pairs) {
        StringBuilder text = new StringBuilder();
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int[] at = {0};
        java.util.function.BiConsumer<String, String> add = (s, style) -> {
            if (s.isEmpty()) return;
            text.append(s);
            spans.add(style == null ? List.of() : List.of(style), s.length());
            at[0] += s.length();
        };
        List<String> olds = pairs.stream().map(ReplaceSql.Pair::oldValue).toList();
        List<String> newValues = pairs.stream().map(ReplaceSql.Pair::newValue).filter(v -> !v.isEmpty()).toList();
        for (ColumnChoice c : columns.items()) {
            if (!c.on().get() || c.excluded() != null) continue;
            List<String> rows = samples.get(c.finding());
            if (rows == null || rows.isEmpty()) continue;
            add.accept(c.finding().table().displayName() + "." + c.finding().column().name() + "\n", "preview-title");
            for (String before : rows) {
                if (before == null) continue;
                add.accept("  before  ", "preview-label");
                marked(before, olds, "preview-old", add);
                add.accept("\n  after   ", "preview-label");
                marked(ReplaceSql.apply(before, pairs), newValues, "preview-new", add);
                add.accept("\n", null);
            }
        }
        if (text.isEmpty()) {
            preview.replaceText(loaded ? "Nothing to preview: type a new value and tick a column." : "Reading rows…");
            return;
        }
        preview.replaceText(text.toString());
        preview.setStyleSpans(0, spans.create());
        preview.moveTo(0);
        preview.requestFollowCaret();
    }

    private static void marked(String value, List<String> marks, String style, java.util.function.BiConsumer<String, String> add) {
        String one = value.replace("\r\n", "↵").replace('\n', '↵').replace('\r', '↵');
        int at = 0;
        for (int[] m : DataScan.matches(one, marks, false, false)) {
            add.accept(one.substring(at, m[0]), null);
            add.accept(one.substring(m[0], m[1]), style);
            at = m[1];
        }
        add.accept(one.substring(at), null);
    }

    /** Whether the plan's values and columns are ready (for tests). */
    boolean ready() {
        return loaded;
    }

    /** The values' "becomes" fields (for tests). */
    List<TextField> newFields() {
        return news;
    }

    String checksText() {
        return checks.getText();
    }

    String previewText() {
        return preview.getText();
    }

    CheckList<ColumnChoice> columnList() {
        return columns;
    }

}
