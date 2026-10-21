package com.dbadmin.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Popup;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * A text field that picks one item from a list by typing part of its name: the dropdown shows the
 * matches; Enter, a click or an exact name picks one; Esc or leaving without a pick keeps the
 * previous choice. (A filtered editable ComboBox loses its selection when its list changes.)
 */
class SearchBox<T> extends HBox {

    private final ObjectProperty<T> value = new SimpleObjectProperty<>();
    private final ObservableList<T> items = FXCollections.observableArrayList();
    private final TextField field = new TextField();
    private final ListView<T> matches = new ListView<>();
    private final Popup popup = new Popup();
    private final Function<T, String> name;
    private boolean settingText;

    SearchBox(Function<T, String> name, String prompt) {
        this.name = name;
        field.setPromptText(prompt);
        field.setPrefColumnCount(18);
        HBox.setHgrow(field, Priority.ALWAYS);
        Button all = new Button("▾");
        all.setFocusTraversable(false);
        all.setOnAction(e -> {
            field.requestFocus();
            showMatches("");
        });
        getChildren().addAll(field, all);
        getStyleClass().add("search-box");

        matches.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : name.apply(item));
            }
        });
        matches.setPrefHeight(240);
        matches.setOnMouseClicked(e -> pick(matches.getSelectionModel().getSelectedItem()));
        matches.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) pick(matches.getSelectionModel().getSelectedItem());
            else if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        popup.getContent().add(matches);
        popup.setAutoHide(true);
        Themes.install(matches);

        field.textProperty().addListener((o, a, text) -> {
            if (!settingText) showMatches(text);
        });
        field.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    if (!popup.isShowing()) showMatches(field.getText());
                    matches.requestFocus();
                    matches.getSelectionModel().selectFirst();
                }
                case ENTER -> {
                    T exact = items.stream().filter(i -> name.apply(i).equalsIgnoreCase(field.getText().trim()))
                            .findFirst().orElse(null);
                    pick(exact != null ? exact : matches.getItems().size() == 1 ? matches.getItems().get(0) : null);
                }
                case ESCAPE -> cancel();
                default -> {
                    return;
                }
            }
            e.consume();
        });
        field.focusedProperty().addListener((o, was, focused) -> {
            if (!focused && !matches.isFocused()) {
                popup.hide();
                showValue(); // left without picking: the previous choice stays
            }
        });
        value.addListener((o, a, v) -> showValue());
    }

    ObjectProperty<T> valueProperty() {
        return value;
    }

    T getValue() {
        return value.get();
    }

    void setValue(T v) {
        value.set(v);
    }

    /** The items to choose from; the current value is cleared if it is no longer among them. */
    void setItems(List<T> list) {
        items.setAll(list);
        if (value.get() != null && !items.contains(value.get())) value.set(null);
    }

    List<T> getItems() {
        return items;
    }

    private void showMatches(String text) {
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        matches.getItems().setAll(items.stream().filter(i -> name.apply(i).toLowerCase(Locale.ROOT).contains(q)).toList());
        if (matches.getItems().isEmpty() || getScene() == null) {
            popup.hide();
            return;
        }
        matches.setPrefWidth(Math.max(getWidth(), 220));
        if (!popup.isShowing()) {
            Bounds b = localToScreen(getBoundsInLocal());
            popup.show(this, b.getMinX(), b.getMaxY());
        }
    }

    private void pick(T item) {
        popup.hide();
        if (item != null) value.set(item);
        showValue();
        field.positionCaret(field.getLength());
    }

    private void cancel() {
        popup.hide();
        showValue();
    }

    private void showValue() {
        settingText = true;
        field.setText(value.get() == null ? "" : name.apply(value.get()));
        settingText = false;
    }
}
