package com.dbadmin.ui;

import com.dbadmin.license.Licensing;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The license in the window: the line in the footer, the License dialog, and the check before a licensed tool opens
 * (today the compares). A new licensed tool calls {@link #allows} with its name instead of a check of its own.
 */
final class LicenseGate {

    private final Licensing licensing = new Licensing();
    private final Window owner;
    private final AppFooter footer;
    private final Consumer<String> status;

    LicenseGate(Window owner, AppFooter footer, Consumer<String> status) {
        this.owner = owner;
        this.footer = footer;
        this.status = status;
        showInFooter();
    }

    /** The footer's license line, for today (the date may have changed since startup). */
    void showInFooter() {
        footer.showLicense(licensing.current(), licensing.today());
    }

    /** The License dialog: shows the current license; a key entered there is installed. */
    void openDialog() {
        InAppDialogs.showAndWait(new LicenseDialog(owner, licensing)).ifPresent(key -> {
            try {
                licensing.install(key);
                status.accept("License activated: " + LicenseDialog.describe(licensing.check(key)));
            } catch (Exception e) {
                Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(e.getMessage()), ButtonType.OK);
                alert.initOwner(owner);
                alert.setHeaderText("Could not activate the license");
                alert.getDialogPane().setMinWidth(480);
                InAppDialogs.show(alert);
            }
            showInFooter();
        });
    }

    /**
     * True when the license lets the tools named {@code tools} (e.g. "Compare Table and Compare Data") open; otherwise
     * says why and offers to enter a license, and is true if one that allows them was entered.
     */
    boolean allows(String tools) {
        showInFooter();
        if (licensing.allowsCompare()) return true;
        String why = licensing.current()
                .map(l -> "The " + l.type().label().toLowerCase(Locale.ROOT) + " license for " + l.name()
                        + " ended on " + DateTimeFormatter.ofPattern("d MMM yyyy").format(l.expires()) + ".")
                .orElse("No license is installed.");
        ButtonType enter = new ButtonType("Enter License…", ButtonBar.ButtonData.OK_DONE);
        Alert locked = new Alert(Alert.AlertType.INFORMATION, why + "\n\nTo continue, contact " + Licensing.CONTACT + ".",
                enter, ButtonType.CLOSE);
        locked.initOwner(owner);
        locked.setTitle("License");
        locked.setHeaderText(tools + " need a license");
        if (InAppDialogs.showAndWait(locked).orElse(ButtonType.CLOSE) == enter) {
            openDialog();
            return licensing.allowsCompare();
        }
        return false;
    }
}
