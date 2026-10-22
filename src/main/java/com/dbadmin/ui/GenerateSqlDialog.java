package com.dbadmin.ui;

import com.dbadmin.model.DbType;
import com.dbadmin.service.compare.DataCompareReport;
import com.dbadmin.service.compare.MigrationSql;
import com.dbadmin.service.compare.Verdict;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.List;
import java.util.Map;

/**
 * Generate SQL: the script of a data compare's verdicts (see {@link MigrationSql}), with its options, to copy or
 * open in a script tab on the target. The result is the script when Open in script tab is chosen.
 */
class GenerateSqlDialog extends Dialog<String> {

    private final CodeArea area = new CodeArea();
    private final Label summary = new Label();

    GenerateSqlDialog(Window owner, DataCompareReport report, Map<String, List<MigrationSql.Column>> tables, String file) {
        initOwner(owner);
        setTitle("Generate SQL");
        setHeaderText("SQL for " + report.target.label() + ": the verdicts of this compare, in one transaction");
        setResizable(true);

        CheckBox audit = new CheckBox("Fill audit columns as");
        audit.setSelected(true);
        audit.setTooltip(new Tooltip("Inserted rows: created_date = now(), created_by = this name, modified_* = NULL."
                + "\nUpdated rows: modified_date = now(), modified_by = this name. Only for the columns the table has."));
        TextField user = new TextField("SYSTEM");
        user.setPrefColumnCount(10);
        user.disableProperty().bind(audit.selectedProperty().not());
        CheckBox soft = new CheckBox("DELETE sets is_deleted = true");
        soft.setSelected(true);
        soft.setTooltip(new Tooltip("Keep the deleted rows, marked is_deleted = true; off: DELETE them"));
        boolean canSoft = report.count(Verdict.DELETE) > 0
                && MigrationSql.hasSoftDelete(tables.getOrDefault(report.target.table().toLowerCase(java.util.Locale.ROOT), List.of()));
        soft.setVisible(canSoft);
        soft.setManaged(canSoft);
        HBox options = new HBox(8, audit, user, soft);
        options.setAlignment(Pos.CENTER_LEFT);

        area.setEditable(false);
        area.getStyleClass().add("sql-editor");
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);
        scroll.setPrefHeight(480);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        summary.getStyleClass().add("hint-small");
        summary.setWrapText(true);
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
            c.putString(area.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
            copy.setText("Copied");
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(8, summary, gap, copy);
        head.setAlignment(Pos.CENTER_LEFT);

        Runnable refresh = () -> {
            String name = user.getText() == null || user.getText().isBlank() ? "SYSTEM" : user.getText().strip();
            String sql = MigrationSql.generate(report, tables, new MigrationSql.Options(audit.isSelected(), name, soft.isSelected()), file);
            area.replaceText(sql);
            area.setStyleSpans(0, SqlHighlighter.highlight(sql, DbType.POSTGRESQL));
            area.moveTo(0);
            area.requestFollowCaret();
            long reviews = sql.lines().takeWhile(l -> !l.equals("BEGIN;")).filter(l -> l.startsWith("--   review:")).count();
            summary.setText(report.count(Verdict.INSERT) + " INSERT · " + report.count(Verdict.UPDATE) + " UPDATE · "
                    + report.count(Verdict.DELETE) + " DELETE · rows without a verdict or with SKIP are left out"
                    + (reviews > 0 ? " · " + reviews + " review note(s) in the header" : ""));
            copy.setText("Copy");
        };
        audit.selectedProperty().addListener((o, a, b) -> refresh.run());
        user.textProperty().addListener((o, a, b) -> refresh.run());
        soft.selectedProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();

        VBox content = new VBox(8, options, head, scroll);
        content.setPadding(new Insets(12, 16, 8, 16));
        content.setPrefWidth(980);
        getDialogPane().setContent(content);
        ButtonType open = new ButtonType("Open in script tab", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(open, ButtonType.CLOSE);
        setResultConverter(b -> b == open ? area.getText() : null);
    }
}
