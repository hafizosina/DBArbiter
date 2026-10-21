package com.dbadmin.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.List;

/** A line-by-line diff of two SQL texts: "-" lines only in the source, "+" lines only in the target. */
class SqlDiffDialog extends Dialog<Void> {

    SqlDiffDialog(Window owner, String title, String source, String target, String sourceName, String targetName) {
        initOwner(owner);
        setTitle("SQL diff");
        setHeaderText(null);

        List<String> diff = diff(lines(source), lines(target));
        CodeArea area = new CodeArea();
        area.getStyleClass().addAll("sql-editor", "sql-diff");
        area.setEditable(false);
        StringBuilder text = new StringBuilder();
        for (String line : diff) text.append(line).append('\n');
        area.replaceText(text.toString());
        for (int i = 0; i < diff.size(); i++) {
            String line = diff.get(i);
            if (line.startsWith("- ")) area.setParagraphStyle(i, List.of("diff-removed"));
            else if (line.startsWith("+ ")) area.setParagraphStyle(i, List.of("diff-added"));
        }
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);
        scroll.setPrefSize(860, 480);

        long removed = diff.stream().filter(l -> l.startsWith("- ")).count();
        long added = diff.stream().filter(l -> l.startsWith("+ ")).count();
        Label legend = new Label(title + "   ·   - only in " + sourceName + " (" + removed + ")   ·   + only in "
                + targetName + " (" + added + ")");
        legend.setStyle("-fx-font-weight: bold;");
        VBox content = new VBox(8, legend, scroll);
        content.setPadding(new Insets(10));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }

    private static List<String> lines(String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s.strip().split("\\R"));
    }

    /** Longest-common-subsequence line diff; lines are compared ignoring surrounding whitespace. */
    static List<String> diff(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = same(a.get(i), b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (same(a.get(i), b.get(j))) {
                out.add("  " + a.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add("- " + a.get(i++));
            } else {
                out.add("+ " + b.get(j++));
            }
        }
        while (i < n) out.add("- " + a.get(i++));
        while (j < m) out.add("+ " + b.get(j++));
        return out;
    }

    private static boolean same(String x, String y) {
        return x.strip().replaceAll("\\s+", " ").equals(y.strip().replaceAll("\\s+", " "));
    }
}
