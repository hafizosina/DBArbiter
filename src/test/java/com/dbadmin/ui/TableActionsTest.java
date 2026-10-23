package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.TableRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableActionsTest {

    private static ConnectionConfig connection(boolean readOnly, int level) {
        ConnectionConfig c = new ConnectionConfig();
        c.setReadOnly(readOnly);
        c.setWarningLevel(level);
        return c;
    }

    private static final TableRef TABLE = new TableRef(null, "app", "orders", "TABLE");
    private static final TableRef VIEW = new TableRef(null, "app", "v_orders", "VIEW");

    @Test
    void truncateResetAndDropOnlyWhereTheConnectionWarnsAboutNothing() {
        assertTrue(TableActions.canDestroy(connection(false, 0), TABLE));
        for (int level = 1; level <= 3; level++) assertFalse(TableActions.canDestroy(connection(false, level), TABLE));
        assertFalse(TableActions.canDestroy(connection(true, 0), TABLE), "never on a read-only connection");
        assertFalse(TableActions.canDestroy(connection(false, 0), VIEW), "a view is not emptied or dropped here");
    }

    @Test
    void backupAndRenameOnAnyWritableConnection() {
        assertTrue(TableActions.canChange(connection(false, 3), TABLE));
        assertFalse(TableActions.canChange(connection(true, 0), TABLE));
        assertFalse(TableActions.canChange(connection(false, 0), VIEW));
    }
}
