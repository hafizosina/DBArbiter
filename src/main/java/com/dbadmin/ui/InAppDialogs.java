package com.dbadmin.ui;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import javafx.util.Duration;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Shows JavaFX dialogs (Alert, Dialog subclasses) as a card over the dimmed main window instead
 * of a separate OS window, so they never appear as their own window in the task bar.
 * {@link #showAndWait} blocks like {@link Dialog#showAndWait()} (a nested event loop).
 */
final class InAppDialogs {

    private static StackPane host;

    private InAppDialogs() {
    }

    /** The main window's top-level stack; dialogs are layered on top of its content. */
    static void install(StackPane mainWindowStack) {
        host = mainWindowStack;
    }

    /** Like {@code dialog.showAndWait()}, but inside the main window. */
    static <R> Optional<R> showAndWait(Dialog<R> dialog) {
        // JavaFX cannot block inside an animation/layout pass; fall back to a normal dialog window there
        if (host == null || host.getScene() == null || !Platform.canStartNestedEventLoop()) return dialog.showAndWait();
        Object loop = new Object();
        @SuppressWarnings("unchecked")
        Optional<R>[] result = new Optional[]{Optional.empty()};
        open(dialog, r -> {
            result[0] = r;
            Platform.exitNestedEventLoop(loop, null);
        });
        Platform.enterNestedEventLoop(loop);
        return result[0];
    }

    /** Like {@code dialog.show()} (does not wait), but inside the main window. */
    static <R> void show(Dialog<R> dialog) {
        if (host == null || host.getScene() == null) {
            dialog.show();
            return;
        }
        open(dialog, r -> { });
    }

    private static <R> void open(Dialog<R> dialog, Consumer<Optional<R>> done) {
        DialogPane pane = dialog.getDialogPane();
        Label title = new Label(dialog.getTitle());
        title.getStyleClass().add("overlay-title");
        title.setMaxWidth(Double.MAX_VALUE);
        VBox card = new VBox(title, pane);
        card.getStyleClass().add("overlay-card");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        StackPane layer = new StackPane(card);
        layer.getStyleClass().add("overlay-layer");
        layer.setAlignment(Pos.CENTER);
        layer.setOnMouseClicked(javafx.event.Event::consume); // the window below is blocked

        Node focusBefore = host.getScene().getFocusOwner();
        boolean[] finished = {false};
        Consumer<ButtonType> finish = button -> {
            if (finished[0]) return;
            finished[0] = true;
            host.getChildren().remove(layer);
            card.getChildren().remove(pane); // the pane can be shown again later
            if (focusBefore != null) focusBefore.requestFocus();
            done.accept(Optional.ofNullable(button == null ? null : convert(dialog, button)));
        };

        // a button closes the card unless one of its filters consumed the click (e.g. validation)
        for (ButtonType type : pane.getButtonTypes()) {
            Node button = pane.lookupButton(type);
            if (button != null) button.addEventHandler(ActionEvent.ACTION, e -> finish.accept(type));
        }
        layer.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.ESCAPE) return;
            e.consume();
            ButtonType cancel = pane.getButtonTypes().stream()
                    .filter(t -> t.getButtonData().isCancelButton() || t.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE)
                    .findFirst().orElse(pane.getButtonTypes().size() == 1 ? pane.getButtonTypes().get(0) : null);
            if (cancel != null && pane.lookupButton(cancel) instanceof Button b) b.fire();
            else finish.accept(null);
        });

        host.getChildren().add(layer);
        // size and place the card now, so the first frame does not show it unstyled at 0x0 (flicker)
        host.applyCss();
        host.layout();
        layer.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(120), layer);
        fade.setToValue(1);
        fade.play();
        if (dialog.getOnShown() != null) {
            dialog.getOnShown().handle(new DialogEvent(dialog, DialogEvent.DIALOG_SHOWN));
        } else {
            pane.getButtonTypes().stream().map(pane::lookupButton)
                    .filter(n -> n instanceof Button b && b.isDefaultButton()).findFirst()
                    .ifPresentOrElse(Node::requestFocus, card::requestFocus);
        }
    }

    /** The dialog's result for a button, the way Dialog computes it. */
    @SuppressWarnings("unchecked")
    private static <R> R convert(Dialog<R> dialog, ButtonType button) {
        Callback<ButtonType, R> converter = dialog.getResultConverter();
        return converter != null ? converter.call(button) : (R) button;
    }
}
