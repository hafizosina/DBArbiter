package com.dbadmin.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks which tables a Compare Table run covers. The result is the picked names (lower case);
 * an empty set means all tables.
 */
class TablePickerDialog extends Dialog<Set<String>> {

    /** A table name with where it exists, and whether it is picked. */
    private record Choice(String name, String where, BooleanProperty picked) {
        @Override
        public String toString() {
            return where.isEmpty() ? name : name + "   (" + where + ")";
        }
    }

    TablePickerDialog(Window owner, List<String> sourceTables, List<String> targetTables,
                      String sourceName, String targetName, Set<String> picked) {
        initOwner(owner);
        setTitle("Tables to compare");
        setHeaderText(null);

        Set<String> source = lower(sourceTables);
        Set<String> target = lower(targetTables);
        Set<String> all = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        all.addAll(sourceTables);
        all.addAll(targetTables);
        ObservableList<Choice> choices = FXCollections.observableArrayList();
        for (String name : all) {
            String key = name.toLowerCase(Locale.ROOT);
            String where = !target.contains(key) ? "only in " + sourceName : !source.contains(key) ? "only in " + targetName : "";
            choices.add(new Choice(name, where, new SimpleBooleanProperty(picked.contains(key))));
        }
        CheckList<Choice> list = new CheckList<>(Choice::picked, Choice::toString);
        list.setItems(choices);
        List<Choice> shown = list.shown();
        TextField search = list.searchField();
        list.listView().setPrefSize(420, 360);

        Label count = new Label();
        count.getStyleClass().add("hint-small");
        Runnable updateCount = () -> {
            long n = choices.stream().filter(c -> c.picked().get()).count();
            count.setText(n == 0 ? "None picked: all " + choices.size() + " tables are compared" : n + " of " + choices.size() + " picked");
        };
        choices.forEach(c -> c.picked().addListener((o, a, b) -> updateCount.run()));
        updateCount.run();

        Button selectShown = new Button("Select shown");
        selectShown.setOnAction(e -> shown.forEach(c -> c.picked().set(true)));
        Button clear = new Button("Clear");
        clear.setOnAction(e -> choices.forEach(c -> c.picked().set(false)));
        HBox buttons = new HBox(6, selectShown, clear, count);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox content = new VBox(8, list, buttons);
        content.setPadding(new Insets(10));
        VBox.setVgrow(list, Priority.ALWAYS);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            Set<String> result = new LinkedHashSet<>();
            choices.stream().filter(c -> c.picked().get()).forEach(c -> result.add(c.name().toLowerCase(Locale.ROOT)));
            return result;
        });
        setOnShown(e -> search.requestFocus());
    }

    private static Set<String> lower(List<String> names) {
        Set<String> set = new java.util.HashSet<>();
        names.forEach(n -> set.add(n.toLowerCase(Locale.ROOT)));
        return set;
    }
}
