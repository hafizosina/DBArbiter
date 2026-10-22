package com.dbadmin.ui;

import com.dbadmin.model.DbType;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.ColumnSpec;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.SchemaChange;
import com.dbadmin.service.SchemaChange.ConstraintKind;
import com.dbadmin.service.SchemaChange.ConstraintSpec;
import com.dbadmin.service.SchemaChange.ForeignKeySpec;
import com.dbadmin.service.SchemaChange.IndexSpec;
import javafx.collections.FXCollections;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Add, Edit and Drop in the table tab's Columns, Constraints, Foreign Keys and Indexes sections. Each opens a form
 * with the SQL it makes (see {@link StructureDialog}); Apply runs it as the connection's warning level says (see
 * {@link RunWarning}). Drop always asks, showing the SQL. Constraints, indexes and foreign keys are edited by dropping
 * and creating them again, as the databases have no ALTER for them. {@code changed} runs after a change was made.
 */
final class StructureEditing {

    static final String COLUMNS = "Columns";
    static final String CONSTRAINTS = "Constraints";
    static final String FOREIGN_KEYS = "Foreign Keys";
    static final String INDEXES = "Indexes";
    static final Set<String> SECTIONS = Set.of(COLUMNS, CONSTRAINTS, FOREIGN_KEYS, INDEXES);

    private final DatabaseSession session;
    /** The session's connection; given on its own so the forms can be tried without a database. */
    private final com.dbadmin.model.ConnectionConfig config;
    private final TableRef table;
    private final Runnable changed;

    StructureEditing(DatabaseSession session, TableRef table, Runnable changed) {
        this(session, session.getConfig(), table, changed);
    }

    StructureEditing(DatabaseSession session, com.dbadmin.model.ConnectionConfig config, TableRef table, Runnable changed) {
        this.session = session;
        this.config = config;
        this.table = table;
        this.changed = changed;
    }

    /** Why the structure cannot be changed here, or null if it can. */
    String notEditable() {
        if (table.isView()) return "A view's structure comes from its query";
        if (config.isReadOnly()) return "Read-only connection: untick \"Read-only\" in Edit Connection to change the structure";
        return null;
    }

    private DbType type() {
        return config.getType();
    }

    // ---- actions ----

    void add(String section, Window owner) {
        withColumns(columns -> {
            switch (section) {
                case COLUMNS -> show(owner, columnForm(owner, null));
                case CONSTRAINTS -> show(owner, constraintForm(owner, columns, null));
                case INDEXES -> show(owner, indexForm(owner, columns, null));
                case FOREIGN_KEYS -> withTables(tables -> show(owner, foreignKeyForm(owner, columns, tables, null)));
                default -> { }
            }
        });
    }

    /** {@code row}: the section's row as listed. */
    void edit(String section, List<Object> row, Window owner) {
        withColumns(columns -> {
            switch (section) {
                case COLUMNS -> {
                    String name = text(row, 1);
                    Async.run(session, () -> session.columnSpecs(table), specs -> specs.stream().filter(c -> c.name().equals(name))
                            .findFirst().ifPresentOrElse(c -> show(owner, columnForm(owner, c)),
                                    () -> error(owner, "Column not found", name + " is not in " + table.displayName() + " any more. Refresh.")),
                            ex -> error(owner, "Could not read the column", ex.getMessage()));
                }
                case CONSTRAINTS -> {
                    ConstraintSpec c = constraintOf(row);
                    if (c == null) error(owner, "Not editable here", text(row, 1) + " constraints are changed with SQL in a script.");
                    else show(owner, constraintForm(owner, columns, c));
                }
                case INDEXES -> show(owner, indexForm(owner, columns, indexOf(row)));
                case FOREIGN_KEYS -> withTables(tables -> show(owner, foreignKeyForm(owner, columns, tables, row)));
                default -> { }
            }
        });
    }

    void drop(String section, List<Object> row, Window owner) {
        String name = section.equals(COLUMNS) ? text(row, 1) : text(row, 0);
        List<String> sql;
        try {
            sql = switch (section) {
                case COLUMNS -> SchemaChange.dropColumn(type(), table, name);
                case CONSTRAINTS -> {
                    ConstraintSpec c = constraintOf(row);
                    // PostgreSQL drops any constraint by name; MySQL needs to know what it is
                    if (c == null && type() != DbType.POSTGRESQL) throw new SchemaChange.Unsupported(text(row, 1) + " constraints are dropped with SQL in a script.");
                    yield SchemaChange.dropConstraint(type(), table, c != null ? c : new ConstraintSpec(name, ConstraintKind.CHECK, List.of(), ""));
                }
                case INDEXES -> {
                    if (type() == DbType.MYSQL && name.equals("PRIMARY")) throw new SchemaChange.Unsupported("This index is the primary key: drop it under Constraints.");
                    yield SchemaChange.dropIndex(type(), table, name);
                }
                case FOREIGN_KEYS -> SchemaChange.dropForeignKey(type(), table, name);
                default -> List.of();
            };
        } catch (SchemaChange.Unsupported e) {
            error(owner, "Cannot drop it here", e.getMessage());
            return;
        }
        String what = switch (section) {
            case COLUMNS -> "column";
            case CONSTRAINTS -> "constraint";
            case INDEXES -> "index";
            default -> "foreign key";
        };
        ButtonType drop = new ButtonType("Drop", ButtonBar.ButtonData.OK_DONE);
        Alert ask = new Alert(Alert.AlertType.WARNING, "", drop, ButtonType.CANCEL);
        if (owner != null) ask.initOwner(owner);
        ask.setHeaderText("Drop " + what + " \"" + name + "\" of " + table.displayName() + " on \"" + config.getName() + "\"?");
        TextArea text = new TextArea(String.join(";\n", sql) + ";");
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefRowCount(Math.min(6, sql.size() + 1));
        text.getStyleClass().add("sql-messages");
        ask.getDialogPane().setContent(text);
        ask.getDialogPane().setPrefWidth(600);
        ((Button) ask.getDialogPane().lookupButton(drop)).setDefaultButton(false);
        ((Button) ask.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        if (InAppDialogs.showAndWait(ask).orElse(ButtonType.CANCEL) != drop) return;
        run(owner, sql);
    }

    // ---- forms ----

    /** The form for a new column ({@code before} null) or for changing one. */
    StructureDialog columnForm(Window owner, ColumnSpec before) {
        boolean editing = before != null;
        StructureDialog d = new StructureDialog(owner, editing ? "Edit Column" : "Add Column",
                editing ? "Column \"" + before.name() + "\" of " + table.displayName() : "New column in " + table.displayName());
        ColumnSpec base = editing ? before : ColumnSpec.of("", "", true, null);
        TextField name = new TextField(base.name());
        ComboBox<String> typeBox = new ComboBox<>(FXCollections.observableArrayList(suggestedTypes(type())));
        typeBox.setEditable(true);
        typeBox.getEditor().setText(base.type());
        typeBox.setMaxWidth(Double.MAX_VALUE);
        CheckBox notNull = new CheckBox("NOT NULL");
        notNull.setSelected(!base.nullable());
        TextField def = new TextField(base.defaultValue() == null ? "" : base.defaultValue());
        def.setPromptText("SQL expression, e.g. 0, 'text', now(); empty: none");
        TextField comment = new TextField(base.comment());
        d.row("Name", name);
        d.row("Type", typeBox);
        d.row("", notNull);
        d.row("Default", def);
        if (type() != DbType.SQLITE) d.row("Comment", comment);
        if (!base.extra().isEmpty()) d.row("Kept", new Label(base.extra()));
        d.statements(() -> {
            ColumnSpec after = base.withName(name.getText().strip()).withType(typeBox.getEditor().getText().strip())
                    .withNullable(!notNull.isSelected()).withDefault(def.getText().isBlank() ? null : def.getText().strip())
                    .withComment(comment.getText());
            return editing ? SchemaChange.alterColumn(type(), table, before, after) : SchemaChange.addColumn(type(), table, after);
        }, name.textProperty(), typeBox.getEditor().textProperty(), notNull.selectedProperty(), def.textProperty(), comment.textProperty());
        d.setOnShown(e -> name.requestFocus());
        return d;
    }

    StructureDialog constraintForm(Window owner, List<String> columns, ConstraintSpec before) {
        boolean editing = before != null;
        StructureDialog d = new StructureDialog(owner, editing ? "Edit Constraint" : "Add Constraint",
                editing ? "Constraint \"" + before.name() + "\" of " + table.displayName() + " (dropped and created again)"
                        : "New constraint on " + table.displayName());
        ComboBox<ConstraintKind> kind = new ComboBox<>(FXCollections.observableArrayList(ConstraintKind.values()));
        kind.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ConstraintKind k) {
                return k == null ? "" : k.sql;
            }

            @Override
            public ConstraintKind fromString(String s) {
                return ConstraintKind.of(s);
            }
        });
        kind.setValue(editing ? before.kind() : ConstraintKind.UNIQUE);
        TextField name = new TextField(editing ? before.name() : "");
        TextField cols = new TextField(editing ? String.join(", ", before.columns()) : "");
        TextField check = new TextField(editing && before.check() != null ? before.check() : "");
        check.setPromptText("e.g. total >= 0");
        AutoName auto = new AutoName(name, () -> switch (kind.getValue()) {
            case PRIMARY_KEY -> table.name() + "_pkey";
            case UNIQUE -> "uq_" + table.name() + suffix(cols.getText());
            case CHECK -> "ck_" + table.name() + suffix(check.getText().replaceAll("[^A-Za-z0-9_]+", " ").strip().split(" ")[0]);
        }, editing);
        javafx.beans.binding.BooleanBinding isCheck = kind.valueProperty().isEqualTo(ConstraintKind.CHECK);
        d.row("Type", kind);
        d.row("Name", name);
        d.row("Columns", StructureDialog.columnsField(cols, columns, "a, b"), isCheck.not());
        d.row("Condition", check, isCheck);
        d.statements(() -> {
            auto.update();
            ConstraintSpec after = new ConstraintSpec(name.getText().strip(), kind.getValue(),
                    kind.getValue() == ConstraintKind.CHECK ? List.of() : SchemaChange.parseColumns(cols.getText()), check.getText().strip());
            if (!editing) return SchemaChange.addConstraint(type(), table, after);
            if (sameConstraint(before, after)) return List.of();
            return concat(SchemaChange.dropConstraint(type(), table, before), SchemaChange.addConstraint(type(), table, after));
        }, kind.valueProperty(), name.textProperty(), cols.textProperty(), check.textProperty());
        return d;
    }

    StructureDialog indexForm(Window owner, List<String> columns, IndexSpec before) {
        boolean editing = before != null;
        StructureDialog d = new StructureDialog(owner, editing ? "Edit Index" : "Add Index",
                editing ? "Index \"" + before.name() + "\" of " + table.displayName() + " (dropped and created again)"
                        : "New index on " + table.displayName());
        TextField name = new TextField(editing ? before.name() : "");
        TextField cols = new TextField(editing ? String.join(", ", before.columns()) : "");
        CheckBox unique = new CheckBox("UNIQUE");
        unique.setSelected(editing && before.unique());
        AutoName auto = new AutoName(name, () -> (unique.isSelected() ? "ux_" : "ix_") + table.name() + suffix(cols.getText()), editing);
        d.row("Columns", StructureDialog.columnsField(cols, columns, "a, b DESC"));
        d.row("", unique);
        d.row("Name", name);
        d.statements(() -> {
            auto.update();
            IndexSpec after = new IndexSpec(name.getText().strip(), SchemaChange.parseColumns(cols.getText()), unique.isSelected());
            if (!editing) return SchemaChange.addIndex(type(), table, after);
            if (after.equals(before)) return List.of();
            return concat(SchemaChange.dropIndex(type(), table, before.name()), SchemaChange.addIndex(type(), table, after));
        }, name.textProperty(), cols.textProperty(), unique.selectedProperty());
        return d;
    }

    /** {@code row}: the foreign key as listed (name, columns, references, referenced columns, on update, on delete), or null. */
    StructureDialog foreignKeyForm(Window owner, List<String> columns, List<TableRef> tables, List<Object> row) {
        boolean editing = row != null;
        StructureDialog d = new StructureDialog(owner, editing ? "Edit Foreign Key" : "Add Foreign Key",
                editing ? "Foreign key \"" + text(row, 0) + "\" of " + table.displayName() + " (dropped and created again)"
                        : "New foreign key on " + table.displayName());
        SearchBox<TableRef> references = new SearchBox<>(TableRef::displayName, "referenced table…");
        references.setItems(tables);
        TextField name = new TextField(editing ? text(row, 0) : "");
        TextField cols = new TextField(editing ? text(row, 1) : "");
        TextField refCols = new TextField(editing ? text(row, 3) : "");
        ComboBox<String> onUpdate = new ComboBox<>(FXCollections.observableArrayList(SchemaChange.RULES));
        ComboBox<String> onDelete = new ComboBox<>(FXCollections.observableArrayList(SchemaChange.RULES));
        onUpdate.setValue(editing ? text(row, 4) : "NO ACTION");
        onDelete.setValue(editing ? text(row, 5) : "NO ACTION");
        TableRef before = editing ? tableNamed(tables, text(row, 2)) : null;
        if (before != null) references.setValue(before);
        AutoName auto = new AutoName(name, () -> "fk_" + table.name()
                + (references.getValue() == null ? "" : "_" + references.getValue().name()), editing);
        // a picked table offers its primary key as the referenced columns
        references.valueProperty().addListener((o, a, t) -> {
            if (t == null || !refCols.getText().isBlank()) return;
            Async.run(session, () -> session.primaryKey(t), pk -> {
                if (refCols.getText().isBlank()) refCols.setText(String.join(", ", pk));
            }, ex -> { });
        });
        d.row("Columns", StructureDialog.columnsField(cols, columns, "a, b"));
        d.row("References", references);
        d.row("Referenced columns", refCols);
        d.row("On update", onUpdate);
        d.row("On delete", onDelete);
        d.row("Name", name);
        ForeignKeySpec original = editing ? new ForeignKeySpec(text(row, 0), SchemaChange.parseColumns(text(row, 1)), before,
                SchemaChange.parseColumns(text(row, 3)), text(row, 4), text(row, 5)) : null;
        d.statements(() -> {
            auto.update();
            ForeignKeySpec after = new ForeignKeySpec(name.getText().strip(), SchemaChange.parseColumns(cols.getText()),
                    references.getValue(), SchemaChange.parseColumns(refCols.getText()), onUpdate.getValue(), onDelete.getValue());
            if (!editing) return SchemaChange.addForeignKey(type(), table, after);
            if (after.equals(original)) return List.of();
            return concat(SchemaChange.dropForeignKey(type(), table, original.name()), SchemaChange.addForeignKey(type(), table, after));
        }, name.textProperty(), cols.textProperty(), references.valueProperty(), refCols.textProperty(),
                onUpdate.valueProperty(), onDelete.valueProperty());
        return d;
    }

    // ---- running ----

    private void show(Window owner, StructureDialog form) {
        InAppDialogs.showAndWait(form).ifPresent(sql -> confirmAndRun(owner, sql));
    }

    private void confirmAndRun(Window owner, List<String> sql) {
        if (RunWarning.confirm(owner, config, sql)) run(owner, sql);
    }

    private void run(Window owner, List<String> sql) {
        Async.run(session, () -> {
            session.applySchemaChange(sql);
            return null;
        }, x -> changed.run(), ex -> {
            error(owner, "The change was not made", ex.getMessage());
            changed.run(); // on MySQL part of it may have been made: show what is there now
        });
    }

    // ---- helpers ----

    private void withColumns(java.util.function.Consumer<List<String>> then) {
        Async.run(session, () -> session.columnSpecs(table).stream().map(ColumnSpec::name).toList(), then,
                ex -> error(null, "Could not read the columns", ex.getMessage()));
    }

    private void withTables(java.util.function.Consumer<List<TableRef>> then) {
        String label = type().qualifiesByCatalog() ? table.catalog() : table.schema();
        Namespace ns = new Namespace(table.catalog(), table.schema(), label == null ? "" : label);
        Async.run(session, () -> session.listTables(ns).stream().filter(t -> !t.isView()).toList(), then,
                ex -> error(null, "Could not list the tables", ex.getMessage()));
    }

    /** The listed reference ("schema.table", or "table") among {@code tables}. */
    private static TableRef tableNamed(List<TableRef> tables, String listed) {
        String name = listed.substring(listed.lastIndexOf('.') + 1);
        return tables.stream().filter(t -> t.displayName().equalsIgnoreCase(listed)).findFirst()
                .orElse(tables.stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst().orElse(null));
    }

    /** A listed constraint (name, type, columns, definition); null for a kind not edited here (e.g. EXCLUDE). */
    static ConstraintSpec constraintOf(List<Object> row) {
        ConstraintKind kind = ConstraintKind.of(text(row, 1));
        if (kind == null) return null;
        return new ConstraintSpec(text(row, 0), kind, kind == ConstraintKind.CHECK ? List.of() : SchemaChange.parseColumns(text(row, 2)),
                kind == ConstraintKind.CHECK ? checkCondition(text(row, 3)) : "");
    }

    /** "CHECK ((total >= 0))" or "(`total` >= 0)" as listed -> "(total >= 0)": without CHECK and the outer parentheses. */
    static String checkCondition(String definition) {
        String s = definition.strip();
        if (s.toUpperCase(Locale.ROOT).startsWith("CHECK")) s = s.substring(5).strip();
        if (s.toUpperCase(Locale.ROOT).endsWith("NOT VALID")) s = s.substring(0, s.length() - 9).strip();
        if (s.startsWith("(") && s.endsWith(")") && balanced(s.substring(1, s.length() - 1))) s = s.substring(1, s.length() - 1).strip();
        return s;
    }

    private static boolean balanced(String s) {
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')' && --depth < 0) return false;
        }
        return depth == 0;
    }

    private static IndexSpec indexOf(List<Object> row) {
        return new IndexSpec(text(row, 0), SchemaChange.parseColumns(text(row, 1)), "YES".equals(text(row, 2)));
    }

    private static boolean sameConstraint(ConstraintSpec a, ConstraintSpec b) {
        return a.name().equals(b.name()) && a.kind() == b.kind() && (a.kind() == ConstraintKind.CHECK
                ? a.check().equals(b.check()) : a.columns().equals(b.columns()));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** "_a_b" from "a, b DESC": for a suggested name. */
    private static String suffix(String columns) {
        List<String> names = SchemaChange.parseColumns(columns).stream()
                .map(c -> c.replaceAll("(?i)\\s+(asc|desc)$", "").replaceAll("[^A-Za-z0-9_]+", "_")).toList();
        return names.isEmpty() ? "" : "_" + String.join("_", names);
    }

    private static String text(List<Object> row, int i) {
        return i < row.size() && row.get(i) != null ? row.get(i).toString() : "";
    }

    static List<String> suggestedTypes(DbType type) {
        return switch (type) {
            case POSTGRESQL -> List.of("integer", "bigint", "smallint", "numeric(12,2)", "varchar(255)", "text", "boolean",
                    "date", "timestamp", "timestamptz", "uuid", "jsonb", "bytea");
            case MYSQL -> List.of("int", "bigint", "smallint", "decimal(12,2)", "varchar(255)", "text", "tinyint(1)",
                    "date", "datetime", "timestamp", "json", "blob");
            case SQLITE -> List.of("INTEGER", "REAL", "NUMERIC", "TEXT", "BLOB");
        };
    }

    private static void error(Window owner, String header, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        if (owner != null) a.initOwner(owner);
        a.setHeaderText(header);
        InAppDialogs.show(a);
    }

    /** Fills the name from the form while the user has not typed one of their own. */
    private static final class AutoName {
        private final TextField field;
        private final Supplier<String> suggestion;
        private String last;

        AutoName(TextField field, Supplier<String> suggestion, boolean editing) {
            this.field = field;
            this.suggestion = suggestion;
            // an existing name is the user's; a new one follows the form until typed over
            this.last = editing ? null : "";
        }

        void update() {
            if (last == null || !field.getText().equals(last)) {
                last = null;
                return;
            }
            String next = suggestion.get().toLowerCase(Locale.ROOT);
            last = next;
            if (!next.equals(field.getText())) field.setText(next); // the change refreshes the form once more
        }
    }
}
