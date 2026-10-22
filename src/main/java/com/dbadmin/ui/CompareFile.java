package com.dbadmin.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * The file behind a compare tab (Table or Data Compare) and whether the tab differs from it: results compared
 * again since a results file was saved, or edits (verdicts) not saved yet. Keeps the tab's name ("*" while
 * something is unsaved) and its Save button (clickable when there is something to save) in step, and asks
 * Save / Don't Save / Cancel before the tab or the app closes. Both compare tabs use one of these.
 */
final class CompareFile {

    private final Tab tab;
    private final Button saveButton;
    /** "Data Compare" or "Table Compare": the tab's name, followed by the file's name once there is one. */
    private final String kind;
    private Path file;
    /** The file holds only the settings (Save keeps it that way); else settings and results. */
    private boolean settingsOnly;
    /** Compared again since its results file was saved: the file is out of date. */
    private boolean resultsStale;
    /** Edits (verdicts) not saved yet. */
    private boolean editsUnsaved;

    CompareFile(Tab tab, Button saveButton, String kind) {
        this.tab = tab;
        this.saveButton = saveButton;
        this.kind = kind;
        refresh();
    }

    /** The compare's file, or null before it is saved or opened. */
    Path file() {
        return file;
    }

    boolean isSettingsOnly() {
        return settingsOnly;
    }

    boolean hasUnsavedEdits() {
        return editsUnsaved;
    }

    boolean isDirty() {
        return editsUnsaved || resultsStale;
    }

    /** Opened from a file, or just written to one: nothing unsaved. */
    void at(Path file, boolean settingsOnly) {
        this.file = file;
        this.settingsOnly = settingsOnly;
        resultsStale = false;
        editsUnsaved = false;
        refresh();
    }

    /** A new compare ran: a results file is now out of date (Save writes the new results to it); a settings file is not. */
    void compared() {
        resultsStale = file != null && !settingsOnly;
        editsUnsaved = false;
        refresh();
    }

    /** Edits were made (true) or thrown away (false). */
    void edited(boolean unsaved) {
        editsUnsaved = unsaved;
        refresh();
    }

    /** "*" before the tab's name while there are unsaved changes; Save clickable when there is something to save. */
    void refresh() {
        String name = file == null ? kind : kind + ": " + file.getFileName();
        tab.setText((isDirty() ? "*" : "") + name);
        saveButton.setDisable(file != null && !isDirty());
    }

    /**
     * Asks about unsaved changes before the tab (or the app) closes: Save runs {@code save} (true when it saved).
     * Returns false if the user cancelled, or Save did not save.
     */
    boolean confirmClose(BooleanSupplier save) {
        if (!isDirty()) return true;
        if (tab.getTabPane() != null) tab.getTabPane().getSelectionModel().select(tab);
        ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        String name = file == null ? kind : kind + ": " + file.getFileName();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "\"" + name + "\" has unsaved changes"
                + (editsUnsaved ? " (verdicts)." : " (the results of the last compare)."), saveButtonType, discard, ButtonType.CANCEL);
        alert.initOwner(tab.getTabPane().getScene().getWindow());
        alert.setHeaderText("Save changes?");
        ButtonType answer = InAppDialogs.showAndWait(alert).orElse(ButtonType.CANCEL);
        if (answer == saveButtonType) return save.getAsBoolean() || !isDirty();
        return answer == discard;
    }
}
