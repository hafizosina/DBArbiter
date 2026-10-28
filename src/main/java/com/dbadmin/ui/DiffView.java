package com.dbadmin.ui;

import com.dbadmin.service.compare.LineDiff;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.List;

/**
 * A git-style diff of an old and a new value: "-" lines (red) only in the old one, "+" lines (green) only
 * in the new one, unchanged lines between them with long runs folded, the changed words marked inside a
 * line, and old / new line numbers in the gutter. A missing old value shows the new one all green, a
 * missing new value the old one all red. Read-only.
 */
class DiffView extends StackPane {

    private final CodeArea area = new CodeArea();
    private List<LineDiff.Line> lines = List.of();

    DiffView() {
        area.getStyleClass().addAll("sql-editor", "sql-diff");
        area.setEditable(false);
        area.setWrapText(true);
        area.setParagraphGraphicFactory(this::gutter);
        getChildren().add(FindBar.over(area, new VirtualizedScrollPane<>(area)));
    }

    /** Shows the diff of two texts; null means there is no such value (all lines added or all removed). */
    void show(String old, String now, boolean ignoreSpace) {
        lines = LineDiff.diff(old, now, ignoreSpace);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            LineDiff.Line l = lines.get(i);
            sb.append(switch (l.kind()) {
                case REMOVED -> "- ";
                case ADDED -> "+ ";
                case SAME -> "  ";
                case FOLDED -> "";
            }).append(l.text());
            if (i < lines.size() - 1) sb.append('\n');
        }
        area.replaceText(sb.toString());
        for (int i = 0; i < lines.size(); i++) {
            LineDiff.Line l = lines.get(i);
            switch (l.kind()) {
                case REMOVED -> area.setParagraphStyle(i, List.of("diff-removed"));
                case ADDED -> area.setParagraphStyle(i, List.of("diff-added"));
                case FOLDED -> area.setStyle(i, 0, l.text().length(), List.of("diff-folded"));
                case SAME -> { }
            }
            String word = l.kind() == LineDiff.Kind.REMOVED ? "diff-word-removed" : "diff-word-added";
            for (int[] c : l.changes()) area.setStyle(i, c[0] + 2, c[1] + 2, List.of(word)); // after the "- " / "+ "
        }
        area.moveTo(0);
        area.requestFollowCaret();
    }

    /** Lines only in the old value (removed), lines only in the new one (added). */
    long[] counts() {
        return new long[]{lines.stream().filter(l -> l.kind() == LineDiff.Kind.REMOVED).count(),
                lines.stream().filter(l -> l.kind() == LineDiff.Kind.ADDED).count()};
    }

    private Label gutter(int paragraph) {
        Label label = new Label();
        label.getStyleClass().add("diff-gutter");
        if (paragraph < lines.size()) {
            LineDiff.Line l = lines.get(paragraph);
            label.setText(String.format("%4s %4s", l.oldNo() > 0 && l.kind() != LineDiff.Kind.FOLDED ? l.oldNo() : "",
                    l.newNo() > 0 ? l.newNo() : ""));
        }
        return label;
    }
}
