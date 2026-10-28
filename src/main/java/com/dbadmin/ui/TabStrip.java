package com.dbadmin.ui;

import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.List;

/**
 * The window's tabs: showing one, the right-click menu (Close, Close Others / to the Left / to the Right, Close All),
 * closing a tab the way its ✕ does (so unsaved work asks first), and asking every tab with unsaved work
 * ({@link UnsavedWork}) before the window closes.
 */
final class TabStrip {

    private final TabPane tabs;
    /** Reopen Closed Tab in the menu (set by the window): what it opens, and the closed tab's title or null. */
    private Runnable reopen;
    private java.util.function.Supplier<String> lastClosed = () -> null;

    TabStrip(TabPane tabs) {
        this.tabs = tabs;
        tabs.getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) {
                for (Tab t : change.getAddedSubList()) {
                    if (t.getContextMenu() == null) t.setContextMenu(menu(t));
                }
            }
        });
    }

    /** Adds Reopen Closed Tab (Ctrl+Shift+T) to the tabs' menu. */
    void setReopen(Runnable reopen, java.util.function.Supplier<String> lastClosed) {
        this.reopen = reopen;
        this.lastClosed = lastClosed;
    }

    /** Adds the tab and selects it. */
    void show(Tab tab) {
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    /** Closes a tab the way its ✕ button does, so unsaved work still asks first. */
    void close(Tab tab) {
        if (tab == null || !tab.isClosable()) return;
        if (tab.getOnCloseRequest() != null) {
            Event request = new Event(Tab.TAB_CLOSE_REQUEST_EVENT);
            tab.getOnCloseRequest().handle(request);
            if (request.isConsumed()) return;
        }
        tabs.getTabs().remove(tab);
        if (tab.getOnClosed() != null) tab.getOnClosed().handle(new Event(Tab.CLOSED_EVENT));
    }

    /** Asks every tab with unsaved work (scripts, cell edits, compares); false if one was cancelled. */
    boolean confirmCloseAll() {
        for (Tab t : List.copyOf(tabs.getTabs())) {
            if (t instanceof UnsavedWork w && !w.confirmClose()) return false;
        }
        return true;
    }

    private ContextMenu menu(Tab tab) {
        MenuItem close = new MenuItem("Close");
        close.setOnAction(e -> close(tab));
        MenuItem others = new MenuItem("Close Others");
        others.setOnAction(e -> closeAll(tabs.getTabs().stream().filter(t -> t != tab).toList()));
        MenuItem left = new MenuItem("Close Tabs to the Left");
        left.setOnAction(e -> closeAll(List.copyOf(tabs.getTabs().subList(0, tabs.getTabs().indexOf(tab)))));
        MenuItem right = new MenuItem("Close Tabs to the Right");
        right.setOnAction(e -> {
            int i = tabs.getTabs().indexOf(tab);
            closeAll(List.copyOf(tabs.getTabs().subList(i + 1, tabs.getTabs().size())));
        });
        MenuItem all = new MenuItem("Close All");
        all.setOnAction(e -> closeAll(List.copyOf(tabs.getTabs())));
        MenuItem reopenItem = new MenuItem("Reopen Closed Tab");
        reopenItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+T"));
        reopenItem.setOnAction(e -> {
            if (reopen != null) reopen.run();
        });
        ContextMenu menu = new ContextMenu(close, others, left, right, new SeparatorMenuItem(), all, new SeparatorMenuItem(), reopenItem);
        menu.setOnShowing(e -> {
            String last = lastClosed.get();
            reopenItem.setText(last == null ? "Reopen Closed Tab" : "Reopen " + last);
            reopenItem.setDisable(last == null);
            int i = tabs.getTabs().indexOf(tab);
            others.setDisable(tabs.getTabs().size() < 2);
            left.setDisable(i <= 0);
            right.setDisable(i < 0 || i == tabs.getTabs().size() - 1);
        });
        return menu;
    }

    /** Closes the tabs one by one, each as its ✕ would; stops at the first one the user keeps open (Cancel). */
    private void closeAll(List<Tab> list) {
        for (Tab t : list) {
            close(t);
            if (tabs.getTabs().contains(t) && t.isClosable()) return;
        }
    }
}
