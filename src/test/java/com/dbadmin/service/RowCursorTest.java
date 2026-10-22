package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.TableData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowCursorTest {

    @TempDir
    Path tmp;

    private static List<Object> ids(TableData data) {
        return data.rows().stream().map(r -> r.get(0)).toList();
    }

    /** One run of the query: the rows already shown are passed over, then read in chunks to the end. */
    @Test
    void readsTheRestInChunks() throws Exception {
        Path file = tmp.resolve("t.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("create table t (id integer primary key)");
            for (int i = 1; i <= 25; i++) st.execute("insert into t values (" + i + ")");
        }
        ConnectionConfig config = new ConnectionConfig();
        config.setName("t");
        config.setType(DbType.SQLITE);
        config.setDatabase(file.toString());
        try (DatabaseSession s = DatabaseSession.open(config)) {
            DatabaseSession.RowCursor cursor = s.openCursor("select id from t order by id", List.of(), 10);
            TableData first = cursor.next(5);
            assertEquals(List.of("id"), first.columns());
            assertEquals(List.of(11, 12, 13, 14, 15), ids(first));
            assertTrue(cursor.hasMore());
            assertEquals(10, cursor.next(100).rows().size());
            assertFalse(cursor.hasMore());
            assertEquals(List.of(), ids(cursor.next(5)));

            // a parameter, and passing over more rows than there are
            DatabaseSession.RowCursor few = s.openCursor("select id from t where id > ?", List.of("20"), 50);
            assertFalse(few.hasMore());
        }
    }
}
