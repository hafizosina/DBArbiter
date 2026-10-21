package com.dbadmin.ui;

import com.dbadmin.model.BlobValue;
import com.dbadmin.ui.DataGrid.CellValue;
import com.dbadmin.ui.ValueFormatter.Format;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shows the selected cell's full value, auto-formatted (JSON, XML, hex) and highlighted. */
class CellInspector extends VBox {

    private static final String AUTO = "Auto";
    private static final int MAX_HIGHLIGHT = 200_000;
    private static final Pattern META_REFRESH = Pattern.compile("<meta[^>]*http-equiv\\s*=\\s*[\"']?refresh[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern JSON_TOKENS = Pattern.compile(
            "(?<KEY>\"(?:[^\"\\\\]|\\\\.)*\"(?=\\s*:))|(?<STRING>\"(?:[^\"\\\\]|\\\\.)*\")"
                    + "|(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)|(?<LITERAL>\\b(?:true|false|null)\\b)");
    private static final Pattern XML_TOKENS = Pattern.compile(
            "(?<COMMENT><!--(?s:.*?)-->)|(?<TAG></?[\\w:.-]+|/?>|<\\?[\\w:.-]+|\\?>)"
                    + "|(?<ATTR>[\\w:.-]+(?=\\s*=))|(?<STRING>\"[^\"]*\"|'[^']*')");

    private final Label column = new Label();
    private final Label type = new Label();
    private final ComboBox<Object> format = new ComboBox<>();
    private final CheckBox wrap = new CheckBox("Wrap");
    private final CodeArea text = new CodeArea();
    private final Label info = new Label();
    private final ToggleButton preview = new ToggleButton("Preview");
    /** Off by default and for every new cell: loading images contacts the servers they are on. */
    private final ToggleButton loadImages = new ToggleButton("Load Images");
    private final StackPane body = new StackPane();
    private VirtualizedScrollPane<CodeArea> source;
    /** Created on first use: WebView is heavy. */
    private WebView web;
    private String webContent;
    private CellValue current;

    CellInspector() {
        getStyleClass().add("cell-inspector");
        column.getStyleClass().add("inspector-column");
        type.getStyleClass().add("hint-small");
        info.getStyleClass().add("hint-small");

        format.getItems().add(AUTO);
        format.getItems().addAll((Object[]) Format.values());
        format.setValue(AUTO);
        format.valueProperty().addListener((o, a, b) -> render());
        wrap.selectedProperty().addListener((o, a, b) -> text.setWrapText(b));
        preview.setSelected(true);
        preview.setTooltip(new Tooltip("Render HTML (scripts and external content are blocked)"));
        preview.selectedProperty().addListener((o, a, b) -> render());
        loadImages.setTooltip(new Tooltip("Load https:// images in this preview. Off by default: loading them tells\n"
                + "the image servers this content was opened. Scripts, forms and links stay blocked."));
        loadImages.selectedProperty().addListener((o, a, b) -> render());
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox title = new HBox(6, column, type);
        title.setAlignment(Pos.BASELINE_LEFT);
        HBox tools = new HBox(6, format, preview, loadImages, wrap, spacer, copy);
        tools.setAlignment(Pos.CENTER_LEFT);

        text.setEditable(false);
        text.getStyleClass().add("inspector-text");
        source = new VirtualizedScrollPane<>(text);
        body.getChildren().add(source);
        VBox.setVgrow(body, Priority.ALWAYS);

        setSpacing(6);
        setPadding(new Insets(6));
        getChildren().addAll(title, tools, body, info);
        show(null);
    }

    void show(CellValue cell) {
        current = cell;
        loadImages.setSelected(false); // a new value never loads images by itself
        column.setText(cell == null ? "No cell selected" : cell.column());
        type.setText(cell == null || cell.type() == null ? "" : cell.type().name());
        render();
    }

    private void render() {
        preview.setVisible(false);
        preview.setManaged(false);
        loadImages.setVisible(false);
        loadImages.setManaged(false);
        showSource();
        if (current == null) {
            setText("", null);
            info.setText("Select a cell to see its value");
            return;
        }
        Object value = current.value();
        if (value == null) {
            setText("", null);
            info.setText("NULL");
            return;
        }
        Format chosen = format.getValue() instanceof Format f ? f : ValueFormatter.detect(value, current.type());
        String formatted;
        String problem = null;
        try {
            formatted = ValueFormatter.format(value, chosen);
        } catch (Exception e) {
            // the chosen format does not fit: show the raw text and say why
            problem = "Not valid " + chosen + ": " + e.getMessage();
            chosen = Format.TEXT;
            formatted = value.toString();
        }
        setText(formatted, chosen);
        info.setText(problem != null ? problem : describe(value, chosen, formatted));
        if (chosen == Format.HTML) {
            preview.setVisible(true);
            preview.setManaged(true);
            loadImages.setVisible(preview.isSelected());
            loadImages.setManaged(preview.isSelected());
            if (preview.isSelected()) showPreview(value.toString());
        }
    }

    private void showSource() {
        if (body.getChildren().get(0) != source) body.getChildren().setAll(source);
    }

    /** Renders HTML with scripts off and every external load blocked by a Content-Security-Policy. */
    private void showPreview(String html) {
        if (web == null) {
            web = new WebView();
            web.getEngine().setJavaScriptEnabled(false);
            web.setContextMenuEnabled(false);
            // a clicked link must not navigate away (or fetch anything): put the value back
            web.getEngine().locationProperty().addListener((o, a, location) -> {
                if (location != null && !location.isEmpty() && !location.equals("about:blank") && webContent != null) {
                    javafx.application.Platform.runLater(() -> web.getEngine().loadContent(webContent));
                }
            });
        }
        // no fetches (CSP; https images only when asked), no form posts, no clickable links,
        // no meta-refresh redirects, and no referrer sent with image requests
        String images = loadImages.isSelected() ? "data: https:" : "data:";
        webContent = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "img-src " + images + "; style-src 'unsafe-inline'; font-src data:; form-action 'none'\">"
                + "<meta name=\"referrer\" content=\"no-referrer\">"
                + "<style>a { pointer-events: none; }</style>"
                + META_REFRESH.matcher(html).replaceAll("");
        web.getEngine().loadContent(webContent);
        body.getChildren().setAll(web);
    }

    private static String describe(Object value, Format format, String formatted) {
        if (value instanceof BlobValue b) {
            return b.length() + " bytes" + (b.isTruncated() ? " (showing the first " + b.head().length + ")" : "");
        }
        String auto = format == Format.TEXT ? "" : format + " · ";
        return auto + value.toString().length() + " chars · " + formatted.lines().count() + " lines";
    }

    private void setText(String content, Format format) {
        text.replaceText(content);
        text.moveTo(0);
        text.requestFollowCaret();
        if (content.length() <= MAX_HIGHLIGHT && (format == Format.JSON || format == Format.XML || format == Format.HTML)) {
            text.setStyleSpans(0, highlight(content, format == Format.JSON ? JSON_TOKENS : XML_TOKENS));
        }
    }

    private static StyleSpans<Collection<String>> highlight(String content, Pattern tokens) {
        Matcher m = tokens.matcher(content);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int last = 0;
        while (m.find()) {
            String style = null;
            for (String g : new String[]{"KEY", "STRING", "NUMBER", "LITERAL", "COMMENT", "TAG", "ATTR"}) {
                try {
                    if (m.group(g) != null) {
                        style = "fmt-" + g.toLowerCase();
                        break;
                    }
                } catch (IllegalArgumentException ignored) {
                    // group not in this pattern
                }
            }
            spans.add(Collections.emptyList(), m.start() - last);
            spans.add(List.of(style), m.end() - m.start());
            last = m.end();
        }
        spans.add(Collections.emptyList(), content.length() - last);
        return spans.create();
    }
}
