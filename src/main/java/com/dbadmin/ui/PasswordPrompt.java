package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Asks for the password of a connection whose password is not saved. The result is the password typed. */
class PasswordPrompt extends Dialog<String> {

    PasswordPrompt(Window owner, ConnectionConfig c) {
        initOwner(owner);
        setTitle("Password");
        setHeaderText("Password for " + c.getName());
        PasswordField password = new PasswordField();
        password.setPromptText("password");
        Label who = new Label(c.getUsername() + " @ " + c.getHost() + ":" + c.getPort() + " / " + c.getDatabase());
        who.getStyleClass().add("hint-small");
        VBox content = new VBox(8, who, password);
        content.setPadding(new Insets(12, 16, 8, 16));
        content.setPrefWidth(380);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(b -> b == ButtonType.OK ? password.getText() : null);
        setOnShown(e -> Platform.runLater(password::requestFocus));
    }
}
