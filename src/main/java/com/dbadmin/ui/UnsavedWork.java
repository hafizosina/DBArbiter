package com.dbadmin.ui;

/**
 * A tab that can hold unsaved work (a script, cell edits, compare verdicts). The window asks every such tab before
 * it closes the app, and a tab asks itself before it closes, so a new kind of tab only implements this.
 */
interface UnsavedWork {

    /**
     * Asks what to do with unsaved work (showing the tab first), e.g. Save / Don't Save / Cancel. True when the tab
     * may close: nothing unsaved, saved, or discarded; false when the user cancelled.
     */
    boolean confirmClose();
}
