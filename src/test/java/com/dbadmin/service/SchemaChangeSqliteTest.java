package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema changes run on a real (SQLite) database, and the columns read back as they were written. */
class SchemaChangeSqliteTest {

    @TempDir
    Path tmp;

    private DatabaseSession session;
    private TableRef orders;

    @BeforeEach
    void open() throws Exception {
        Path file = tmp.resolve("shop.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("create table orders (id integer primary key autoincrement, code text not null default 'x', "
                    + "total real, doubled real generated always as (total * 2))");
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

    private ColumnSpec column(String name) throws SQLException {
        return session.columnSpecs(orders).stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    @Test
    void readsColumnsAsDefinedAndRenamesThemBack() throws Exception {
        ColumnSpec code = column("code");
        assertEquals(new ColumnSpec("code", "TEXT", false, "'x'", "", "", "", false), code);
        assertTrue(column("doubled").generated());
        for (ColumnSpec c : session.columnSpecs(orders)) assertEquals(List.of(), SchemaChange.alterColumn(DbType.SQLITE, orders, c, c));

        session.applySchemaChange(SchemaChange.alterColumn(DbType.SQLITE, orders, code, code.withName("order_code")));
        assertEquals(code.withName("order_code"), column("order_code"));
    }

    @Test
    void addsAndDropsColumnsAndIndexes() throws Exception {
        session.applySchemaChange(SchemaChange.addColumn(DbType.SQLITE, orders, ColumnSpec.of("note", "TEXT", false, "'n'")));
        assertEquals(ColumnSpec.of("note", "TEXT", false, "'n'"), column("note"));
        session.applySchemaChange(SchemaChange.addIndex(DbType.SQLITE, orders,
                new SchemaChange.IndexSpec("ix_note", List.of("note", "total desc"), true)));
        assertEquals(List.of("ix_note", "note, total", "YES"), session.listIndexes(orders).rows().get(0)); // SQLite's driver does not report DESC
        session.applySchemaChange(SchemaChange.dropIndex(DbType.SQLITE, orders, "ix_note"));
        session.applySchemaChange(SchemaChange.dropColumn(DbType.SQLITE, orders, "note"));
        assertEquals(null, column("note"));
        assertEquals(0, session.listIndexes(orders).rows().size());
    }

    /** One transaction: the first statement is undone when the second fails. */
    @Test
    void aFailureChangesNothing() throws Exception {
        List<String> sql = List.of(SchemaChange.addColumn(DbType.SQLITE, orders, ColumnSpec.of("tmp", "INT", true, null)).get(0),
                "ALTER TABLE nope ADD COLUMN x INT");
        SQLException e = assertThrows(SQLException.class, () -> session.applySchemaChange(sql));
        assertTrue(e.getMessage().startsWith("Statement 2 of 2:") && e.getMessage().endsWith("Nothing was changed."), e.getMessage());
        assertEquals(null, column("tmp"));
    }

    private long count(String sql) throws SQLException {
        return ((Number) session.execute(sql, 1).data().rows().get(0).get(0)).longValue();
    }

    @Test
    void truncateResetBackupRenameAndDropRun() throws Exception {
        session.execute("insert into orders (code, total) values ('a', 1), ('b', 2)", 1);
        session.applySchemaChange(SchemaChange.backupTable(DbType.SQLITE, orders, "orders_backup"));
        assertEquals(2, count("select count(*) from orders_backup"));

        session.applySchemaChange(SchemaChange.truncateTable(DbType.SQLITE, orders));
        session.execute("insert into orders (code) values ('c')", 1);
        assertEquals(3, count("select max(id) from orders"), "truncate: the ids keep counting");

        session.applySchemaChange(SchemaChange.resetTable(DbType.SQLITE, orders, true));
        session.execute("insert into orders (code) values ('d')", 1);
        assertEquals(1, count("select max(id) from orders"), "reset: the ids start again");

        session.applySchemaChange(SchemaChange.renameTable(DbType.SQLITE, orders, "orders_old"));
        TableRef renamed = session.resolveTable(null, "orders_old");
        assertEquals(1, count("select count(*) from orders_old"));
        session.applySchemaChange(SchemaChange.dropTable(DbType.SQLITE, renamed));
        assertEquals(null, session.resolveTable(null, "orders_old"));
    }
}
