package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession.RowChanges;
import com.dbadmin.service.DatabaseSession.RowUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deletes, updates and inserts of the table view, saved together (SQLite). */
class RowChangesTest {

    @TempDir
    Path tmp;

    private DatabaseSession session;
    private TableRef orders;

    @BeforeEach
    void open() throws Exception {
        Path file = tmp.resolve("shop.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("create table orders (id integer primary key autoincrement, name text not null, paid boolean default 0)");
            st.execute("insert into orders (name) values ('a'), ('b'), ('c')");
        }
        ConnectionConfig c = new ConnectionConfig();
        c.setName("shop");
        c.setType(DbType.SQLITE);
        c.setDatabase(file.toString());
        c.setReadOnly(false);
        session = DatabaseSession.open(c);
        orders = session.resolveTable(null, "orders");
    }

    @AfterEach
    void close() {
        session.close();
    }

    private static Map<String, Object> ordered(String c1, Object v1, String c2, Object v2) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put(c1, v1);
        m.put(c2, v2);
        return m;
    }

    private List<List<Object>> rows() throws SQLException {
        return session.execute("select id, name, paid from orders order by id", 100).data().rows();
    }

    @Test
    void savesDeletesUpdatesAndInsertsTogether() throws Exception {
        RowChanges changes = new RowChanges(List.of(Map.of("id", 1)),
                List.of(new RowUpdate(Map.of("id", 2), Map.of("name", "B"))),
                List.of(Map.of("name", "d"), ordered("name", "e", "paid", true)));
        assertEquals(List.of("DELETE FROM \"orders\" WHERE \"id\" = 1", "UPDATE \"orders\" SET \"name\" = 'B' WHERE \"id\" = 2",
                "INSERT INTO \"orders\" (\"name\") VALUES ('d')", "INSERT INTO \"orders\" (\"name\", \"paid\") VALUES ('e', true)"),
                session.describeChanges(orders, changes).stream().map(s -> s.replaceAll("\"main\"\\.", "")).toList());
        assertEquals(4, session.applyChanges(orders, changes));
        List<List<Object>> rows = rows();
        assertEquals(4, rows.size());
        assertEquals("B", rows.get(0).get(1));
        assertEquals(List.of("B", "c", "d", "e"), rows.stream().map(r -> r.get(1)).toList());
    }

    @Test
    void aRowGoneMeanwhileSavesNothing() throws Exception {
        RowChanges changes = new RowChanges(List.of(Map.of("id", 3), Map.of("id", 99)), List.of(), List.of(Map.of("name", "d")));
        SQLException e = assertThrows(SQLException.class, () -> session.applyChanges(orders, changes));
        assertTrue(e.getMessage().contains("no longer exists"), e.getMessage());
        assertEquals(3, rows().size(), "the first delete was rolled back");
    }

    @Test
    void aNewRowCanLeaveEveryColumnToTheDatabase() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve("shop.db")); var st = c.createStatement()) {
            st.execute("create table events (id integer primary key autoincrement, at text default 'now')");
        }
        TableRef events = session.resolveTable(null, "events");
        assertEquals(1, session.applyChanges(events, new RowChanges(List.of(), List.of(), List.of(Map.of()))));
        assertEquals("now", session.execute("select at from events", 10).data().rows().get(0).get(0));
    }
}
