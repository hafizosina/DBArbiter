package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunWarningTest {

    private static final List<String> SCRIPT = List.of("select * from orders", "insert into orders (id) values (1)",
            "update orders set paid = true where id = 1", "delete from orders");

    private static ConnectionConfig connection(boolean readOnly, int level) {
        ConnectionConfig c = new ConnectionConfig();
        c.setName("prod");
        c.setType(DbType.POSTGRESQL);
        c.setReadOnly(readOnly);
        c.setWarningLevel(level);
        return c;
    }

    @Test
    void eachLevelWarnsAboutMoreStatements() {
        assertEquals(List.of(), RunWarning.warned(connection(false, 0), SCRIPT));
        assertEquals(List.of("deletes every row (no WHERE):  delete from orders"), RunWarning.warned(connection(false, 1), SCRIPT));
        assertEquals(2, RunWarning.warned(connection(false, 2), SCRIPT).size());
        assertEquals(3, RunWarning.warned(connection(false, 3), SCRIPT).size());
    }

    @Test
    void aReadOnlyConnectionNeedsNoWarning() {
        assertEquals(List.of(), RunWarning.warned(connection(true, 3), SCRIPT));
    }

    @Test
    void namesTheStatementNotTheCommentAboveIt() {
        assertEquals(List.of("may change data (DO):  DO $$ BEGIN UPDATE t SET a = 1 WHERE id = 1; END $$"), RunWarning.warned(connection(false, 2),
                List.of("-- Replace found values\n--   a  →  b\nDO $$ BEGIN UPDATE t SET a = 1 WHERE id = 1; END $$")));
    }
}
