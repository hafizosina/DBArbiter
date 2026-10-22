package com.dbadmin.ui;

import com.dbadmin.license.License;
import com.dbadmin.license.LicenseKey;
import com.dbadmin.license.Licensing;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.format.DateTimeFormatter;

/** Shows the installed license and takes a new key (pasted). The result is the activated key text. */
class LicenseDialog extends Dialog<String> {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    LicenseDialog(Window owner, Licensing licensing) {
        initOwner(owner);
        setTitle("License");
        setHeaderText(licensing.current()
                .map(l -> "Current license: " + describe(l))
                .orElse("No license installed. Compare Table and Compare Data need one."));

        TextArea key = new TextArea();
        key.setPromptText("Paste the license key here (DBA1-…)");
        key.setWrapText(true);
        key.setPrefRowCount(4);
        key.setPrefColumnCount(48);
        Label check = new Label(" ");
        check.setWrapText(true);
        VBox content = new VBox(8, new Label("New license key:"), key, check);
        content.setPadding(new Insets(12, 16, 12, 16));
        getDialogPane().setContent(content);

        ButtonType activate = new ButtonType("Activate", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(activate, ButtonType.CANCEL);
        // looked up once: shown in the window (InAppDialogs) the pane is not the dialog's any more
        Node activateButton = getDialogPane().lookupButton(activate);
        activateButton.setDisable(true);
        key.textProperty().addListener((o, a, text) -> {
            boolean valid = false;
            if (text.isBlank()) {
                check.setText(" ");
            } else {
                try {
                    check.setText("✓ " + describe(licensing.check(text)));
                    valid = true;
                } catch (LicenseKey.InvalidKeyException e) {
                    check.setText("✗ " + e.getMessage());
                }
            }
            check.getStyleClass().setAll("label", valid ? "hint-small" : "error-text-inline");
            activateButton.setDisable(!valid);
        });
        setResultConverter(b -> b == activate ? key.getText() : null);
        setOnShown(e -> key.requestFocus());
    }

    static String describe(License l) {
        return l.type().label() + " for " + l.name()
                + (l.expires() == null ? ", never ends" : ", " + DATE.format(l.issued()) + " – " + DATE.format(l.expires()));
    }
}
