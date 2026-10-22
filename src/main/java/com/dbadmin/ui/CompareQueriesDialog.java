package com.dbadmin.ui;

import com.dbadmin.model.DbType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

/**
 * The two queries a data compare runs, one per side (the sides are separate connections, so the rows are
 * compared in the app), to read or copy and run by hand.
 */
class CompareQueriesDialog extends Dialog<Void> {

    CompareQueriesDialog(Window owner, String source, String sourceSql, DbType sourceType,
                         String target, String targetSql, DbType targetType, int rowsPerSide) {
        initOwner(owner);
        setTitle("Compare queries");
        setHeaderText("The queries Compare data runs: one on each connection");
        setResizable(true);
        Label note = new Label("Each runs on its own connection; the app reads at most " + rowsPerSide
                + " rows of each (Rows per side) and compares them by the key.");
        note.getStyleClass().add("hint-small");
        note.setWrapText(true);
        VBox content = new VBox(10, note, side(source + " (source)", sourceSql, sourceType), side(target + " (target)", targetSql, targetType));
        content.setPadding(new Insets(12, 16, 8, 16));
        content.setPrefWidth(860);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }

    private static Node side(String title, String sql, DbType type) {
        CodeArea area = new CodeArea(sql + ";");
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("sql-editor");
        area.setStyleSpans(0, SqlHighlighter.highlight(area.getText(), type));
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);
        scroll.setPrefHeight(150);
        Label label = new Label(title);
        label.setStyle("-fx-font-weight: bold;");
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
            c.putString(area.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
            copy.setText("Copied");
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(8, label, gap, copy);
        head.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, head, scroll);
    }
}
