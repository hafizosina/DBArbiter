package com.dbadmin.ui;

import javafx.scene.control.Label;

/**
 * The small coloured word in front of a tab's name that says what kind of tab it is (table, script, compare, scan …),
 * so tabs that share a name pattern are told apart at a glance. Words, not symbols: they read the same with the
 * bundled fonts on every system. Colours in app.css / dark.css (tab-badge-*).
 */
final class TabBadge {

    enum Kind {
        TABLE("TBL"), VIEW("VIEW"), SCRIPT("SQL"), COMPARE("CMP"), SCAN("SCAN"), CONNECTION("CONN");

        final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    private TabBadge() {
    }

    static Label of(Kind kind) {
        Label badge = new Label(kind.word);
        badge.getStyleClass().addAll("tab-badge", "tab-badge-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        badge.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return badge;
    }
}
