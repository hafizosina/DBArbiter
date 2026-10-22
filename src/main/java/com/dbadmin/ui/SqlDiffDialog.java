package com.dbadmin.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * A line-by-line diff of two SQL texts (see {@link DiffView}), as the target would change to match the
 * source: "-" lines only in the target, "+" lines only in the source; whitespace alone does not count.
 */
class SqlDiffDialog extends Dialog<Void> {

    SqlDiffDialog(Window owner, String title, String source, String target, String sourceName, String targetName) {
        initOwner(owner);
        setTitle("SQL diff");
        setHeaderText(null);

        DiffView diff = new DiffView();
        diff.show(target == null || target.isBlank() ? null : target.strip(), source == null || source.isBlank() ? null : source.strip(), true);
        diff.setPrefSize(860, 480);
        long[] n = diff.counts();
        Label legend = new Label(title + "   ·   − only in " + targetName + " (" + n[0] + ")   ·   + only in "
                + sourceName + " (" + n[1] + ")");
        legend.setStyle("-fx-font-weight: bold;");
        VBox content = new VBox(8, legend, diff);
        content.setPadding(new Insets(10));
        VBox.setVgrow(diff, Priority.ALWAYS);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }
}
