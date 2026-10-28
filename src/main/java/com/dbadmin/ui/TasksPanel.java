package com.dbadmin.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The background tasks panel above the footer: each running task with its time, progress and Stop. Shown and
 * hidden with the footer's "N running" toggle ({@link #toggle}).
 */
final class TasksPanel extends VBox {

    private final ListView<BackgroundTasks.Task> list = new ListView<>(BackgroundTasks.running());
    private final ToggleButton toggle = new ToggleButton();

    TasksPanel() {
        getStyleClass().add("tasks-panel");
        Label title = new Label("Background tasks");
        title.getStyleClass().add("form-title");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button hide = new Button("✕");
        hide.getStyleClass().add("small-button");
        hide.setOnAction(e -> toggle.setSelected(false));
        HBox head = new HBox(8, title, gap, hide);
        head.setAlignment(Pos.CENTER_LEFT);
        list.setPlaceholder(new Label("Nothing is running."));
        list.setPrefHeight(150);
        list.setCellFactory(v -> new TaskCell());
        setPadding(new Insets(4, 8, 4, 8));
        setSpacing(4);
        getChildren().addAll(head, list);
        visibleProperty().bind(toggle.selectedProperty());
        managedProperty().bind(toggle.selectedProperty());

        toggle.getStyleClass().add("small-button");
        toggle.setTooltip(new javafx.scene.control.Tooltip("Show or hide what runs in the background"));
        BackgroundTasks.running().addListener((ListChangeListener<BackgroundTasks.Task>) c -> updateToggle());
        updateToggle();
        // the elapsed times count up
        Timeline tick = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (isVisible() && !list.getItems().isEmpty()) list.refresh();
        }));
        tick.setCycleCount(Timeline.INDEFINITE);
        tick.play();
    }

    /** For the footer: "2 running" (or "No tasks"), and it shows the panel. */
    ToggleButton toggle() {
        return toggle;
    }

    private void updateToggle() {
        int n = BackgroundTasks.running().size();
        toggle.setText(n == 0 ? "No tasks" : "⟳ " + n + " running");
    }

    private static String elapsed(long startedMillis) {
        long s = (System.currentTimeMillis() - startedMillis) / 1000;
        return s < 60 ? s + " s" : s / 60 + " min " + s % 60 + " s";
    }

    private static final class TaskCell extends ListCell<BackgroundTasks.Task> {
        private final Label title = new Label();
        private final Label progress = new Label();
        private final Label time = new Label();
        private final Button stop = new Button("Stop");
        private final HBox box;

        TaskCell() {
            title.getStyleClass().add("form-title");
            progress.getStyleClass().add("hint-small");
            time.getStyleClass().add("hint-small");
            stop.getStyleClass().add("small-button");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            box = new HBox(10, title, progress, gap, time, stop);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(BackgroundTasks.Task t, boolean empty) {
            super.updateItem(t, empty);
            progress.textProperty().unbind();
            if (empty || t == null) {
                setGraphic(null);
                return;
            }
            title.setText(t.title);
            progress.textProperty().bind(t.progress);
            time.setText(elapsed(t.startedMillis));
            stop.setOnAction(e -> {
                stop.setDisable(true);
                stop.setText("Stopping…");
                t.stop.run();
            });
            stop.setDisable(false);
            stop.setText("Stop");
            setGraphic(box);
        }
    }
}
