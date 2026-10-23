package com.dbadmin.ui;

import com.dbadmin.service.compare.KeyJoins;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Data Compare's column picker: which columns identify a row (Key), are compared, or are only shown. The table's own
 * columns and joined ones ("mpc.category_code"); a column missing on one side can be ticked for nothing.
 * Shift-click ticks or unticks a range in one column, like the tables picker.
 */
class ColumnPicker extends VBox {

    /**
     * One column of the compared table, or of a joined table ("mpc.category_code": e.g. to compare category_id
     * by the code it points to, when the ids differ between the databases).
     */
    static final class Choice {
        final String name;
        final boolean inSource;
        final boolean inTarget;
        final boolean joined;
        final BooleanProperty key = new SimpleBooleanProperty();
        final BooleanProperty compare = new SimpleBooleanProperty();
        /** Shown in the report only: its differences do not change a row's status. */
        final BooleanProperty show = new SimpleBooleanProperty();

        Choice(String name, boolean inSource, boolean inTarget) {
            this.name = name;
            this.inSource = inSource;
            this.inTarget = inTarget;
            this.joined = KeyJoins.isJoinedColumn(name);
        }

        boolean onBothSides() {
            return inSource && inTarget;
        }
    }

    private final ObservableList<Choice> choices = FXCollections.observableArrayList();
    private final TableView<Choice> table = new TableView<>(choices);
    /** Shift-click ranges, within the checkbox column last clicked. */
    private final RangeTicks ticks = new RangeTicks();
    private TableColumn<Choice, ?> anchorColumn;

    ColumnPicker() {
        super(6);
        TableColumn<Choice, String> name = new TableColumn<>("Column");
        name.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name
                + (c.getValue().onBothSides() ? "" : c.getValue().inSource ? "  (only in source)" : "  (only in target)")));
        name.setPrefWidth(125);
        TableColumn<Choice, Boolean> key = new TableColumn<>("Key");
        key.setCellValueFactory(c -> c.getValue().key);
        key.setCellFactory(col -> new CheckCell());
        key.setPrefWidth(40);
        TableColumn<Choice, Boolean> compare = new TableColumn<>("Compare");
        compare.setCellValueFactory(c -> c.getValue().compare);
        compare.setCellFactory(col -> new CheckCell());
        compare.setPrefWidth(68);
        TableColumn<Choice, Boolean> show = new TableColumn<>("Show");
        show.setCellValueFactory(c -> c.getValue().show);
        show.setCellFactory(col -> new CheckCell());
        show.setPrefWidth(52);
        table.getColumns().addAll(List.of(name, key, compare, show));
        // the check box columns keep their width, the names take the rest (no sideways scrolling)
        for (TableColumn<Choice, ?> c : List.of(key, compare, show)) {
            c.setMinWidth(c.getPrefWidth());
            c.setMaxWidth(c.getPrefWidth());
            c.setResizable(false);
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setEditable(true);
        table.setPlaceholder(new Label("The table's columns appear here."));

        Button all = new Button("Compare all");
        all.setOnAction(e -> choices.forEach(c -> c.compare.set(c.onBothSides() && !c.key.get() && !c.joined)));
        Button showAll = new Button("Show all");
        showAll.setOnAction(e -> choices.forEach(c -> c.show.set(c.onBothSides())));
        Button none = new Button("Clear");
        none.setOnAction(e -> choices.forEach(c -> {
            c.key.set(false);
            c.compare.set(false);
            c.show.set(false);
        }));
        HBox buttons = new HBox(6, all, showAll, none);
        CompareSide.keepWidth(all, showAll, none);
        getChildren().addAll(table, buttons);
        setPadding(new Insets(6));
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    /** Every column listed now, by name (the table's own, then "alias.column"). */
    List<String> names() {
        return choices.stream().map(c -> c.name).toList();
    }

    /** The columns that identify a row. */
    List<String> keys() {
        return choices.stream().filter(c -> c.key.get()).map(c -> c.name).toList();
    }

    /** The compared columns (a key is never also compared). */
    List<String> compared() {
        return choices.stream().filter(c -> c.compare.get() && !c.key.get()).map(c -> c.name).toList();
    }

    /** The columns only shown in the report. */
    List<String> shownOnly() {
        return choices.stream().filter(c -> c.show.get() && !c.key.get() && !c.compare.get()).map(c -> c.name).toList();
    }

    /** True when every one of {@code names} is listed (case does not matter). */
    boolean lists(Collection<String> names) {
        Set<String> listed = new HashSet<>();
        choices.forEach(c -> listed.add(c.name.toLowerCase(Locale.ROOT)));
        return names.stream().allMatch(n -> listed.contains(n.toLowerCase(Locale.ROOT)));
    }

    /** A reopened compare's ticks, kept by name when the tables' columns arrive (see {@link #setColumns}). */
    void restore(List<String> shown, List<String> keys, List<String> compared) {
        List<Choice> ticks = new ArrayList<>();
        for (String c : shown) {
            Choice choice = new Choice(c, true, true);
            choice.key.set(keys.contains(c));
            choice.compare.set(!choice.key.get() && compared.contains(c));
            choice.show.set(!choice.key.get() && !choice.compare.get());
            ticks.add(choice);
        }
        choices.setAll(ticks);
    }

    /**
     * Lists the columns of both sides: the source's in order, then those only in the target. Nothing is ticked for a
     * new table; ticks made for the same column names are kept (another side picked, a join added, a compare
     * reopened). A column missing on one side is listed but never ticked.
     */
    void setColumns(List<String> srcCols, List<String> dstCols) {
        choices.setAll(merged(choices, srcCols, dstCols));
    }

    /** The choices for these columns, keeping the ticks of {@code previous} by name (see {@link #setColumns}). */
    static List<Choice> merged(List<Choice> previous, List<String> srcCols, List<String> dstCols) {
        Map<String, Boolean[]> before = new HashMap<>();
        previous.forEach(c -> before.put(c.name.toLowerCase(Locale.ROOT), new Boolean[]{c.key.get(), c.compare.get(), c.show.get()}));
        Set<String> tgtNames = new HashSet<>();
        dstCols.forEach(c -> tgtNames.add(c.toLowerCase(Locale.ROOT)));
        Set<String> srcNames = new HashSet<>();
        List<Choice> listed = new ArrayList<>();
        for (String c : srcCols) {
            srcNames.add(c.toLowerCase(Locale.ROOT));
            listed.add(new Choice(c, true, tgtNames.contains(c.toLowerCase(Locale.ROOT))));
        }
        for (String c : dstCols) {
            if (!srcNames.contains(c.toLowerCase(Locale.ROOT))) listed.add(new Choice(c, false, true));
        }
        boolean keep = !before.isEmpty() && listed.stream().anyMatch(c -> before.containsKey(c.name.toLowerCase(Locale.ROOT)));
        for (Choice c : listed) {
            Boolean[] ticks = before.get(c.name.toLowerCase(Locale.ROOT));
            if (!c.onBothSides() || !keep || ticks == null) continue;
            c.key.set(ticks[0]);
            c.compare.set(ticks[1]);
            c.show.set(ticks[2]);
        }
        return listed;
    }

    /**
     * A checkbox; the whole cell is the click target, and shift-click ticks (or unticks) the rows from the last
     * clicked one in the same column. Disabled for a column that is not on both sides.
     */
    private class CheckCell extends javafx.scene.control.cell.CheckBoxTableCell<Choice, Boolean> {

        CheckCell() {
            // a click anywhere in the cell, the box included, goes through RangeTicks (the box would toggle on its own)
            addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || isEmpty() || isDisabled()) return;
                e.consume();
                if (anchorColumn != getTableColumn()) ticks.reset(); // a range stays within one checkbox column
                anchorColumn = getTableColumn();
                List<Choice> rows = getTableView().getItems();
                ticks.click(getIndex(), e.isShiftDown(), rows.size(), this::value, i -> rows.get(i).onBothSides());
            });
            addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) e.consume();
            });
        }

        private BooleanProperty value(int row) {
            return (BooleanProperty) getTableColumn().getCellObservableValue(row);
        }

        @Override
        public void updateItem(Boolean value, boolean empty) {
            super.updateItem(value, empty);
            Choice c = empty || getTableRow() == null ? null : getTableRow().getItem();
            boolean usable = c != null && c.onBothSides();
            setDisable(!usable);
            setEditable(usable);
        }
    }
}
