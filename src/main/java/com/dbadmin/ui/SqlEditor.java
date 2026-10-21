package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.service.CompletionContext;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.SqlSplitter;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The SQL editor used by script tabs and by the query box of a table: syntax colors, line numbers,
 * autocomplete (typing / Ctrl+Space), find and replace (Ctrl+F / Ctrl+R / F3) and Ctrl+Enter to run.
 */
class SqlEditor extends VBox {

    private final CodeArea area = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scroll;
    private final FindBar findBar;
    private final CompletionPopup popup = new CompletionPopup();
    private final SqlCompleter completer;
    private final SessionProvider sessions;
    private final Supplier<ConnectionConfig> connection;
    private int completionRequest;
    /** Connections that failed to open while typing; only Ctrl+Space retries them. */
    private final Set<String> autoConnectFailed = new HashSet<>();

    /**
     * @param connection the connection the SQL is for (may return null), used for dialect and metadata
     * @param onRun      called for Ctrl+Enter (see {@link #statementToRun()})
     */
    SqlEditor(SqlCompleter completer, SessionProvider sessions, Supplier<ConnectionConfig> connection, Runnable onRun) {
        this.completer = completer;
        this.sessions = sessions;
        this.connection = connection;
        this.findBar = new FindBar(area, this::rehighlight);

        area.getStyleClass().add("sql-editor");
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        // re-highlight once typing pauses, not on every keystroke
        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(150))
                .subscribe(ignore -> rehighlight());
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::completionKeys);
        area.plainTextChanges().subscribe(change -> {
            String inserted = change.getInserted();
            if (inserted.length() == 1 && change.getRemoved().isEmpty() && isCompletionTrigger(inserted.charAt(0))) {
                Platform.runLater(() -> requestCompletion(false));
            } else if (inserted.isEmpty() && popup.isShowing()) {
                Platform.runLater(() -> requestCompletion(false)); // backspace: refilter
            } else {
                popup.hide();
            }
        });
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> popup.hide());
        // a filter, so the text area never sees Ctrl+Enter and inserts a newline
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                e.consume();
                onRun.run();
            }
        });
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (handleFindKeys(e)) e.consume();
        });

        scroll = new VirtualizedScrollPane<>(area);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(findBar, scroll);
    }

    CodeArea area() {
        return area;
    }

    /**
     * Makes the text area exactly {@code lines} lines high (the find bar adds to that when open).
     * Long lines wrap, so a horizontal scroll bar never hides one of them.
     */
    void setVisibleLines(int lines) {
        area.setWrapText(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        boolean[] applied = {false};
        // the line height is known once the first paragraph has been laid out
        area.totalHeightEstimateProperty().addListener((o, before, total) -> {
            if (applied[0] || total == null || total <= 0) return;
            double lineHeight = area.getParagraphBoundsOnScreen(0)
                    .map(javafx.geometry.Bounds::getHeight)
                    .orElse(total / area.getParagraphs().size());
            if (lineHeight <= 0) return;
            applied[0] = true;
            double height = Math.ceil(lines * lineHeight);
            scroll.setMinHeight(height);
            scroll.setPrefHeight(height);
            scroll.setMaxHeight(height);
        });
    }

    /** Replaces the text as a fresh start: not undoable. */
    void setText(String text) {
        area.replaceText(text);
        area.getUndoManager().forgetHistory();
        rehighlight();
    }

    /** For Ctrl+Enter: the selected text, or the statement at the caret ("" if there is none). */
    String statementToRun() {
        String selected = area.getSelectedText();
        if (!selected.isBlank()) return selected;
        ConnectionConfig c = connection.get();
        SqlSplitter.Statement s = SqlSplitter.statementAt(area.getText(), area.getCaretPosition(), c == null ? null : c.getType());
        return s == null ? "" : s.sql();
    }

    /** The selected text, or all of it when nothing is selected. */
    String sqlToRun() {
        String selected = area.getSelectedText();
        return selected.isBlank() ? area.getText() : selected;
    }

    /** Focuses the text; for a tab just opened, waits until the editor is actually in the window. */
    void focusEditor() {
        if (area.getScene() != null) {
            area.requestFocus();
            return;
        }
        area.sceneProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends javafx.scene.Scene> o,
                                javafx.scene.Scene before, javafx.scene.Scene scene) {
                if (scene == null) return;
                area.sceneProperty().removeListener(this);
                Platform.runLater(area::requestFocus);
            }
        });
    }

    /** The connection changed: re-color for its dialect and drop suggestions for the old one. */
    void connectionChanged() {
        rehighlight();
        popup.hide();
    }

    void hidePopup() {
        popup.hide();
    }

    /** Ctrl+F, Ctrl+R and (Shift+)F3; true if the key was one of them. */
    boolean handleFindKeys(KeyEvent e) {
        if (e.isShortcutDown() && !e.isShiftDown() && e.getCode() == KeyCode.F) findBar.open(false);
        else if (e.isShortcutDown() && !e.isShiftDown() && e.getCode() == KeyCode.R) findBar.open(true);
        else if (e.getCode() == KeyCode.F3) findBar.move(!e.isShiftDown());
        else return false;
        return true;
    }

    // ---- highlighting ----------------------------------------------------

    void rehighlight() {
        ConnectionConfig c = connection.get();
        StyleSpans<Collection<String>> spans = SqlHighlighter.highlight(area.getText(), c == null ? null : c.getType());
        List<int[]> found = findBar.matchRanges();
        if (!found.isEmpty()) {
            // add "search-match" on top of the syntax colors
            StyleSpansBuilder<Collection<String>> marks = new StyleSpansBuilder<>();
            int last = 0;
            for (int[] m : found) {
                marks.add(List.of(), m[0] - last);
                marks.add(List.of("search-match"), m[1] - m[0]);
                last = m[1];
            }
            marks.add(List.of(), area.getLength() - last);
            spans = spans.overlay(marks.create(), (syntax, mark) -> {
                if (mark.isEmpty()) return syntax;
                List<String> both = new ArrayList<>(syntax);
                both.addAll(mark);
                return both;
            });
        }
        area.setStyleSpans(0, spans);
    }

    // ---- autocomplete ----------------------------------------------------

    private static boolean isCompletionTrigger(char c) {
        return c == '.' || c == '_' || Character.isLetterOrDigit(c);
    }

    private void completionKeys(KeyEvent e) {
        if (e.getCode() == KeyCode.SPACE && e.isShortcutDown()) {
            e.consume();
            requestCompletion(true);
            return;
        }
        if (!popup.isShowing()) return;
        switch (e.getCode()) {
            case UP -> popup.move(-1);
            case DOWN -> popup.move(1);
            case PAGE_UP -> popup.move(-8);
            case PAGE_DOWN -> popup.move(8);
            case ENTER, TAB -> {
                if (e.isShortcutDown()) return; // Ctrl+Enter still runs
                popup.acceptSelected();
            }
            case ESCAPE -> popup.hide();
            default -> {
                if (e.getCode().isNavigationKey()) popup.hide();
                return;
            }
        }
        e.consume();
    }

    /**
     * Shows suggestions for the word at the caret. Typing triggers it after two characters or a '.';
     * Ctrl+Space ("explicit") always shows it. Both open the connection if needed, except that a
     * connection which failed while typing is only retried by Ctrl+Space.
     */
    private void requestCompletion(boolean explicit) {
        int request = ++completionRequest;
        ConnectionConfig config = connection.get();
        DbType type = config == null ? null : config.getType();
        CompletionContext ctx = CompletionContext.analyze(area.getText(), area.getCaretPosition(), type);
        if (ctx == null || !explicit && ctx.qualifier() == null && ctx.prefix().length() < 2) {
            popup.hide();
            return;
        }
        Consumer<DatabaseSession> complete = session -> completer.complete(ctx, session, items -> {
            if (request == completionRequest) showCompletions(ctx, type, items, explicit);
        });
        if (config == null) {
            complete.accept(null);
        } else if (explicit || !autoConnectFailed.contains(config.getId())) {
            sessions.withSession(config, session -> {
                autoConnectFailed.remove(config.getId());
                complete.accept(session);
            }, ex -> {
                autoConnectFailed.add(config.getId());
                complete.accept(null);
            });
        } else {
            complete.accept(sessions.openSession(config).orElse(null));
        }
    }

    private void showCompletions(CompletionContext ctx, DbType type, List<SqlCompleter.Suggestion> items, boolean explicit) {
        boolean alreadyComplete = items.size() == 1 && items.get(0).text().equalsIgnoreCase(ctx.prefix());
        Bounds caret = area.getCaretBounds().orElse(null);
        if (items.isEmpty() || !explicit && alreadyComplete || caret == null || !area.isFocused()) {
            popup.hide();
            return;
        }
        popup.show(area, caret.getMinX(), caret.getMaxY() + 2, items, s -> accept(s, type));
    }

    /**
     * A table picked after FROM or JOIN gets an alias ("m_product_variant mpv"), unless something other
     * than a separator already follows it on the line (e.g. the user is renaming an existing table).
     */
    private boolean wantsAlias(SqlCompleter.Suggestion s, CompletionContext ctx, int caret) {
        boolean table = s.kind() == SqlCompleter.Kind.TABLE || s.kind() == SqlCompleter.Kind.VIEW;
        if (!table || !ctx.aliasAllowed()) return false;
        String text = area.getText();
        int end = caret;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
        if (end != caret) return false; // in the middle of a word: the alias would be glued to the rest
        int lineEnd = text.indexOf('\n', end);
        String rest = text.substring(end, lineEnd < 0 ? text.length() : lineEnd).strip();
        return rest.isEmpty() || rest.startsWith(",") || rest.startsWith(";") || rest.startsWith(")");
    }

    private void accept(SqlCompleter.Suggestion s, DbType type) {
        // re-read the word at the caret: the text may have changed since the list was built
        int caret = area.getCaretPosition();
        CompletionContext now = CompletionContext.analyze(area.getText(), caret, type);
        if (now == null) return;
        String text = SqlCompleter.insertText(s, now.prefix(), type);
        if (wantsAlias(s, now, caret)) {
            Set<String> taken = new HashSet<>();
            for (CompletionContext.TableMention m : now.mentions()) {
                if (m.alias() != null) taken.add(m.alias());
            }
            text += " " + SqlCompleter.aliasFor(s.text(), taken);
        }
        area.replaceText(now.start(), caret, text);
    }
}
