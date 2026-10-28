package com.dbadmin.ui;

import java.util.List;

/** Copied grid cells as text (no JavaFX here, so it can be tested on its own). */
final class CopyText {

    private CopyText() {
    }

    /** Puts the text on the clipboard. */
    static void toClipboard(String text) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * Tab-separated text: one line per row, NULL as empty. One value alone is copied as it is; in a grid,
     * a value with a tab, line break or quote is quoted ("..." with "" inside), as spreadsheets expect.
     */
    static String tsv(List<List<Object>> rows, List<String> header) {
        if (header == null && rows.size() == 1 && rows.get(0).size() == 1) {
            Object v = rows.get(0).get(0);
            return v == null ? "" : v.toString();
        }
        StringBuilder sb = new StringBuilder();
        if (header != null) sb.append(String.join("\t", header.stream().map(CopyText::field).toList())).append('\n');
        for (List<Object> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append('\t');
                sb.append(field(row.get(i) == null ? "" : row.get(i).toString()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String field(String s) {
        return s.indexOf('\t') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('"') >= 0
                ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
