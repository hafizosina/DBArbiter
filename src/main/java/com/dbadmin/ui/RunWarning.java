package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.service.QueryRisk;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks before running statements that the connection's warning level covers (see QueryRisk), naming the
 * connection, so a script run on production by mistake can still be stopped. Cancel is the default button.
 */
final class RunWarning {

    /** Statements listed in the question; the rest are counted. */
    private static final int SHOWN = 8;

    private RunWarning() {
    }

    /** The statements this connection warns about, each as "what it does: its first line". Empty: nothing to ask. */
    static List<String> warned(ConnectionConfig config, List<String> statements) {
        List<String> warned = new ArrayList<>();
        if (config.isReadOnly()) return warned; // the database refuses changes anyway
        for (String sql : statements) {
            QueryRisk.Assessment a = QueryRisk.assess(sql, config.getType());
            if (QueryRisk.warns(config.getWarningLevel(), a.risk())) warned.add(a.reason() + ":  " + firstLine(sql));
        }
        return warned;
    }

    /** True to run: nothing to warn about, or the user said Run. */
    static boolean confirm(Window owner, ConnectionConfig config, List<String> statements) {
        List<String> warned = warned(config, statements);
        if (warned.isEmpty()) return true;
        Alert ask = question(config, warned);
        if (owner != null) ask.initOwner(owner);
        return InAppDialogs.showAndWait(ask).orElse(ButtonType.CANCEL).getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    /** The question for these warned statements; its Run button is OK_DONE. */
    static Alert question(ConnectionConfig config, List<String> warned) {
        StringBuilder text = new StringBuilder();
        for (String w : warned.subList(0, Math.min(SHOWN, warned.size()))) text.append("• ").append(w).append('\n');
        if (warned.size() > SHOWN) text.append("… and ").append(warned.size() - SHOWN).append(" more\n");
        ButtonType run = new ButtonType("Run on " + config.getName(), ButtonBar.ButtonData.OK_DONE);
        Alert ask = new Alert(Alert.AlertType.WARNING, "", run, ButtonType.CANCEL);
        ask.setHeaderText("Run " + warned.size() + " statement(s) that change \"" + config.getName() + "\"?");
        Label list = new Label(text.toString().strip());
        list.setWrapText(true);
        list.setMinHeight(Region.USE_PREF_SIZE); // every line shown, none cut to "…"
        ask.getDialogPane().setContent(list);
        ask.getDialogPane().setPrefWidth(620);
        ((Button) ask.getDialogPane().lookupButton(run)).setDefaultButton(false);
        ((Button) ask.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return ask;
    }

    /** The statement's first line of code (comment lines above it skipped), shortened. */
    private static String firstLine(String sql) {
        String line = sql.strip().lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("--")).findFirst().orElse("");
        return line.length() > 90 ? line.substring(0, 89) + "…" : line;
    }
}
