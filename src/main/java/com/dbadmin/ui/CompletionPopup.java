package com.dbadmin.ui;

import com.dbadmin.ui.SqlCompleter.Suggestion;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Popup;

import java.util.List;
import java.util.function.Consumer;

/** The autocomplete list shown under the caret. It never takes focus; the editor forwards keys. */
final class CompletionPopup {

    private static final int VISIBLE_ROWS = 10;
    private static final double ROW_HEIGHT = 24;

    private final Popup popup = new Popup();
    private final ListView<Suggestion> list = new ListView<>();
    private Consumer<Suggestion> onAccept;

    CompletionPopup() {
        list.getStylesheets().add(CompletionPopup.class.getResource("/com/dbadmin/app.css").toExternalForm());
        list.getStyleClass().add("completion-list");
        list.setFocusTraversable(false);
        list.setPrefWidth(420);
        list.setFixedCellSize(ROW_HEIGHT);
        list.setCellFactory(lv -> new SuggestionCell());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) acceptSelected();
        });
        popup.getContent().add(list);
        popup.setAutoHide(true);
        popup.setAutoFix(true);
    }

    void show(Node owner, double screenX, double screenY, List<Suggestion> items, Consumer<Suggestion> onAccept) {
        this.onAccept = onAccept;
        list.setItems(FXCollections.observableArrayList(items));
        list.setPrefHeight(Math.min(items.size(), VISIBLE_ROWS) * ROW_HEIGHT + 2);
        list.getSelectionModel().selectFirst();
        list.scrollTo(0);
        if (popup.isShowing()) {
            popup.setX(screenX);
            popup.setY(screenY);
        } else {
            popup.show(owner, screenX, screenY);
        }
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    void hide() {
        popup.hide();
    }

    void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) return;
        int i = Math.max(0, Math.min(size - 1, list.getSelectionModel().getSelectedIndex() + delta));
        list.getSelectionModel().select(i);
        list.scrollTo(Math.max(0, i - VISIBLE_ROWS / 2));
    }

    void acceptSelected() {
        Suggestion s = list.getSelectionModel().getSelectedItem();
        hide();
        if (s != null && onAccept != null) onAccept.accept(s);
    }

    private static class SuggestionCell extends ListCell<Suggestion> {
        private final Label badge = new Label();
        private final Label name = new Label();
        private final Label detail = new Label();
        private final HBox box;

        SuggestionCell() {
            badge.setMinWidth(18);
            badge.setAlignment(Pos.CENTER);
            badge.getStyleClass().add("completion-badge");
            detail.getStyleClass().add("completion-detail");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(6, badge, name, spacer, detail);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Suggestion s, boolean empty) {
            super.updateItem(s, empty);
            setText(null);
            if (empty || s == null) {
                setGraphic(null);
                return;
            }
            badge.setText(switch (s.kind()) {
                case COLUMN -> "c";
                case TABLE -> "T";
                case VIEW -> "V";
                case SCHEMA -> "S";
                case KEYWORD -> "k";
                case TYPE -> "t";
                case FUNCTION -> "f";
            });
            badge.getStyleClass().setAll("label", "completion-badge", "badge-" + s.kind().name().toLowerCase());
            name.setText(s.text());
            detail.setText(s.detail() == null ? "" : s.detail());
            setGraphic(box);
        }
    }
}
