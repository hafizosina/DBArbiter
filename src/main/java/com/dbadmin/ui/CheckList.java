package com.dbadmin.ui;

import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A filterable list of checkbox rows: a search box above a list where a click anywhere on a row ticks or unticks it
 * and a shift-click does the same to every row from the last one clicked ({@link RangeTicks}). {@code ticked} gives
 * each item's checkbox value; {@code label} its text, also what the search box matches. Callers add their own
 * buttons and counts below ({@link #shown()}, {@link #items()}).
 */
class CheckList<T> extends VBox {

    private final ObservableList<T> items = FXCollections.observableArrayList();
    private final FilteredList<T> shown = new FilteredList<>(items);
    private final TextField search = new TextField();
    private final ListView<T> list = new ListView<>(shown);
    private final RangeTicks ticks = new RangeTicks();
    private final Function<T, BooleanProperty> ticked;
    private final Function<T, String> label;
    private Predicate<T> visible = t -> true;

    CheckList(Function<T, BooleanProperty> ticked, Function<T, String> label) {
        super(6);
        this.ticked = ticked;
        this.label = label;
        search.setPromptText("search…");
        search.textProperty().addListener((o, a, b) -> refilter());
        list.setCellFactory(lv -> {
            CheckBoxListCell<T> cell = new CheckBoxListCell<>(ticked::apply, new javafx.util.StringConverter<>() {
                @Override
                public String toString(T t) {
                    return t == null ? "" : label.apply(t);
                }

                @Override
                public T fromString(String s) {
                    return null;
                }
            });
            cell.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (cell.isEmpty() || e.getButton() != MouseButton.PRIMARY) return;
                e.consume(); // handled here, not by the box (it would toggle once more on release)
                ticks.click(cell.getIndex(), e.isShiftDown(), shown.size(), i -> ticked.apply(shown.get(i)), i -> true);
            });
            cell.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
                if (e.getButton() == MouseButton.PRIMARY) e.consume();
            });
            return cell;
        });
        list.setTooltip(new javafx.scene.control.Tooltip("Click a row to tick or untick it; shift-click for every row from the last clicked one"));
        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().addAll(search, list);
    }

    void setItems(List<T> all) {
        items.setAll(all);
        ticks.reset();
    }

    /** Every item, shown or not. */
    ObservableList<T> items() {
        return items;
    }

    /** The items the search box and {@link #setVisible} leave in the list, in order. */
    List<T> shown() {
        return shown;
    }

    /** Which items are listed at all, besides the search (e.g. the columns of ticked tables only); call again to refresh. */
    void setVisible(Predicate<T> visible) {
        this.visible = visible;
        refilter();
    }

    void refilter() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        Predicate<T> v = visible;
        shown.setPredicate(t -> v.test(t) && (q.isEmpty() || label.apply(t).toLowerCase(Locale.ROOT).contains(q)));
        ticks.reset(); // other rows shown: the last clicked row may be gone
    }

    TextField searchField() {
        return search;
    }

    ListView<T> listView() {
        return list;
    }

    /** The test hook for a click on the shown row {@code at}, as the mouse gives it. */
    void click(int at, boolean shift) {
        ticks.click(at, shift, shown.size(), i -> ticked.apply(shown.get(i)), i -> true);
    }
}
