package com.dbadmin.service;

import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.SchemaChange.ConstraintKind;
import com.dbadmin.service.SchemaChange.ConstraintSpec;
import com.dbadmin.service.SchemaChange.ForeignKeySpec;
import com.dbadmin.service.SchemaChange.IndexSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dbadmin.model.DbType.MYSQL;
import static com.dbadmin.model.DbType.POSTGRESQL;
import static com.dbadmin.model.DbType.SQLITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaChangeTest {

    private static final TableRef PG = new TableRef("shop", "app", "orders", "TABLE");
    private static final TableRef MY = new TableRef("shop", null, "orders", "TABLE");
    private static final TableRef LITE = new TableRef(null, "main", "orders", "TABLE");

    private static TableRef table(DbType type) {
        return switch (type) {
            case POSTGRESQL -> PG;
            case MYSQL -> MY;
            case SQLITE -> LITE;
        };
    }

    @Test
    void addsAColumnWithItsDefinition() {
        ColumnSpec c = ColumnSpec.of("note", "varchar(50)", false, "'none'").withComment("it's free text");
        assertEquals(List.of("ALTER TABLE \"app\".\"orders\" ADD COLUMN \"note\" varchar(50) NOT NULL DEFAULT 'none'",
                        "COMMENT ON COLUMN \"app\".\"orders\".\"note\" IS 'it''s free text'"),
                SchemaChange.addColumn(POSTGRESQL, PG, c));
        assertEquals(List.of("ALTER TABLE `shop`.`orders` ADD COLUMN `note` varchar(50) NOT NULL DEFAULT 'none' COMMENT 'it''s free text'"),
                SchemaChange.addColumn(MYSQL, MY, c));
        assertEquals(List.of("ALTER TABLE \"main\".\"orders\" ADD COLUMN \"note\" varchar(50) NOT NULL DEFAULT 'none'"),
                SchemaChange.addColumn(SQLITE, LITE, c.withComment("")));
        assertThrows(SchemaChange.Unsupported.class, () -> SchemaChange.addColumn(SQLITE, LITE, c));
        assertThrows(IllegalArgumentException.class, () -> SchemaChange.addColumn(POSTGRESQL, PG, c.withType(" ")));
    }

    /** Opening a column and saving it unchanged writes nothing, on every database. */
    @Test
    void anUnchangedColumnNeedsNoStatement() {
        ColumnSpec c = new ColumnSpec("id", "int", false, null, "", "auto_increment", "", false);
        for (DbType type : DbType.values()) assertEquals(List.of(), SchemaChange.alterColumn(type, table(type), c, c));
        assertEquals(List.of(), SchemaChange.alterColumn(POSTGRESQL, PG, c.withDefault(""), c.withDefault(null)));
    }

    @Test
    void postgresChangesOnlyWhatChanged() {
        ColumnSpec before = new ColumnSpec("total", "numeric(10,2)", true, null, "", "", "", false);
        ColumnSpec after = before.withName("amount").withType("numeric(12,2)").withNullable(false).withDefault("0").withComment("in IDR");
        assertEquals(List.of(
                "ALTER TABLE \"app\".\"orders\" RENAME COLUMN \"total\" TO \"amount\"",
                "ALTER TABLE \"app\".\"orders\" ALTER COLUMN \"amount\" TYPE numeric(12,2) USING \"amount\"::numeric(12,2)",
                "ALTER TABLE \"app\".\"orders\" ALTER COLUMN \"amount\" SET DEFAULT 0",
                "ALTER TABLE \"app\".\"orders\" ALTER COLUMN \"amount\" SET NOT NULL",
                "COMMENT ON COLUMN \"app\".\"orders\".\"amount\" IS 'in IDR'"), SchemaChange.alterColumn(POSTGRESQL, PG, before, after));
        assertEquals(List.of("ALTER TABLE \"app\".\"orders\" ALTER COLUMN \"amount\" DROP DEFAULT",
                        "COMMENT ON COLUMN \"app\".\"orders\".\"amount\" IS NULL"),
                SchemaChange.alterColumn(POSTGRESQL, PG, after, after.withDefault("").withComment("")));
    }

    /** MySQL restates the column: auto_increment and the collation of a text column must survive a rename. */
    @Test
    void mysqlKeepsWhatTheFormDoesNotShow() {
        ColumnSpec id = new ColumnSpec("id", "int unsigned", false, null, "", "auto_increment", "", false);
        assertEquals(List.of("ALTER TABLE `shop`.`orders` CHANGE COLUMN `id` `order_id` int unsigned NOT NULL auto_increment COMMENT 'key'"),
                SchemaChange.alterColumn(MYSQL, MY, id, id.withName("order_id").withComment("key")));
        assertEquals(List.of("ALTER TABLE `shop`.`orders` RENAME COLUMN `id` TO `order_id`"),
                SchemaChange.alterColumn(MYSQL, MY, id, id.withName("order_id")), "only the name: nothing restated");
        ColumnSpec name = new ColumnSpec("name", "varchar(20)", true, "'x'", "", "", "utf8mb4_bin", false);
        assertEquals(List.of("ALTER TABLE `shop`.`orders` CHANGE COLUMN `name` `name` varchar(40) COLLATE utf8mb4_bin NULL DEFAULT 'x'"),
                SchemaChange.alterColumn(MYSQL, MY, name, name.withType("varchar(40)")));
        assertEquals(List.of("ALTER TABLE `shop`.`orders` CHANGE COLUMN `name` `name` int NULL DEFAULT 'x'"),
                SchemaChange.alterColumn(MYSQL, MY, name, name.withType("int")), "a collation only stays on text");
    }

    @Test
    void sqliteOnlyRenames() {
        ColumnSpec c = ColumnSpec.of("note", "TEXT", true, null);
        assertEquals(List.of("ALTER TABLE \"main\".\"orders\" RENAME COLUMN \"note\" TO \"remark\""),
                SchemaChange.alterColumn(SQLITE, LITE, c, c.withName("remark")));
        SchemaChange.Unsupported e = assertThrows(SchemaChange.Unsupported.class,
                () -> SchemaChange.alterColumn(SQLITE, LITE, c, c.withType("INTEGER")));
        assertTrue(e.getMessage().contains("only rename"));
    }

    @Test
    void generatedColumnsAreRenamedOnly() {
        ColumnSpec g = new ColumnSpec("total", "numeric", true, null, "", "", "", true);
        assertEquals(1, SchemaChange.alterColumn(POSTGRESQL, PG, g, g.withName("sum")).size());
        assertThrows(SchemaChange.Unsupported.class, () -> SchemaChange.alterColumn(POSTGRESQL, PG, g, g.withType("int")));
        assertEquals(1, SchemaChange.alterColumn(MYSQL, MY, g, g.withName("sum")).size());
        assertThrows(SchemaChange.Unsupported.class, () -> SchemaChange.alterColumn(MYSQL, MY, g, g.withComment("x")));
    }

    @Test
    void constraints() {
        ConstraintSpec uq = new ConstraintSpec("uq_code", ConstraintKind.UNIQUE, List.of("code", "shop id"), null);
        assertEquals(List.of("ALTER TABLE \"app\".\"orders\" ADD CONSTRAINT \"uq_code\" UNIQUE (\"code\", \"shop id\")"),
                SchemaChange.addConstraint(POSTGRESQL, PG, uq));
        assertEquals(List.of("ALTER TABLE `shop`.`orders` DROP INDEX `uq_code`"), SchemaChange.dropConstraint(MYSQL, MY, uq));
        ConstraintSpec pk = new ConstraintSpec("orders_pkey", ConstraintKind.PRIMARY_KEY, List.of("id"), null);
        assertEquals(List.of("ALTER TABLE `shop`.`orders` ADD PRIMARY KEY (`id`)"), SchemaChange.addConstraint(MYSQL, MY, pk));
        assertEquals(List.of("ALTER TABLE `shop`.`orders` DROP PRIMARY KEY"), SchemaChange.dropConstraint(MYSQL, MY, pk));
        ConstraintSpec ck = new ConstraintSpec("ck_total", ConstraintKind.CHECK, List.of(), "total >= 0");
        assertEquals(List.of("ALTER TABLE \"app\".\"orders\" ADD CONSTRAINT \"ck_total\" CHECK (total >= 0)"),
                SchemaChange.addConstraint(POSTGRESQL, PG, ck));
        assertEquals(List.of("ALTER TABLE `shop`.`orders` DROP CHECK `ck_total`"), SchemaChange.dropConstraint(MYSQL, MY, ck));
        assertEquals(List.of("ALTER TABLE \"app\".\"orders\" DROP CONSTRAINT \"ck_total\""), SchemaChange.dropConstraint(POSTGRESQL, PG, ck));
        assertThrows(SchemaChange.Unsupported.class, () -> SchemaChange.addConstraint(SQLITE, LITE, uq));
        assertThrows(IllegalArgumentException.class, () -> SchemaChange.addConstraint(POSTGRESQL, PG,
                new ConstraintSpec("x", ConstraintKind.UNIQUE, List.of(), null)));
        assertEquals(ConstraintKind.PRIMARY_KEY, ConstraintKind.of("PRIMARY KEY"));
    }

    @Test
    void indexes() {
        IndexSpec i = new IndexSpec("ix_orders_at", List.of("shop_id", "created_at desc"), true);
        assertEquals(List.of("CREATE UNIQUE INDEX \"ix_orders_at\" ON \"app\".\"orders\" (\"shop_id\", \"created_at\" DESC)"),
                SchemaChange.addIndex(POSTGRESQL, PG, i));
        assertEquals(List.of("CREATE UNIQUE INDEX \"main\".\"ix_orders_at\" ON \"orders\" (\"shop_id\", \"created_at\" DESC)"),
                SchemaChange.addIndex(SQLITE, LITE, i));
        assertEquals(List.of("DROP INDEX \"app\".\"ix_orders_at\""), SchemaChange.dropIndex(POSTGRESQL, PG, "ix_orders_at"));
        assertEquals(List.of("DROP INDEX `ix_orders_at` ON `shop`.`orders`"), SchemaChange.dropIndex(MYSQL, MY, "ix_orders_at"));
        assertEquals(List.of("DROP INDEX \"main\".\"ix_orders_at\""), SchemaChange.dropIndex(SQLITE, LITE, "ix_orders_at"));
    }

    @Test
    void foreignKeys() {
        ForeignKeySpec f = new ForeignKeySpec("fk_orders_shop", List.of("shop_id"), new TableRef("shop", "app", "shops", "TABLE"),
                List.of("id"), "NO ACTION", "CASCADE");
        assertEquals(List.of("ALTER TABLE \"app\".\"orders\" ADD CONSTRAINT \"fk_orders_shop\" FOREIGN KEY (\"shop_id\") "
                + "REFERENCES \"app\".\"shops\" (\"id\") ON DELETE CASCADE"), SchemaChange.addForeignKey(POSTGRESQL, PG, f));
        assertEquals(List.of("ALTER TABLE `shop`.`orders` DROP FOREIGN KEY `fk_orders_shop`"),
                SchemaChange.dropForeignKey(MYSQL, MY, "fk_orders_shop"));
        assertThrows(SchemaChange.Unsupported.class, () -> SchemaChange.addForeignKey(SQLITE, LITE, f));
        assertThrows(IllegalArgumentException.class, () -> SchemaChange.addForeignKey(POSTGRESQL, PG,
                new ForeignKeySpec("f", List.of("a", "b"), f.references(), List.of("id"), null, null)));
    }

    @Test
    void columnListsAsTyped() {
        assertEquals(List.of("a", "b c"), SchemaChange.parseColumns(" a, ,b c ,"));
    }

    @Test
    void wholeTables() {
        assertEquals(List.of("TRUNCATE TABLE \"app\".\"orders\""), SchemaChange.truncateTable(POSTGRESQL, PG));
        assertEquals(List.of("DELETE FROM \"main\".\"orders\""), SchemaChange.truncateTable(SQLITE, LITE));
        assertEquals(List.of("TRUNCATE TABLE \"app\".\"orders\" RESTART IDENTITY"), SchemaChange.resetTable(POSTGRESQL, PG, false));
        assertEquals(List.of("TRUNCATE TABLE `shop`.`orders`"), SchemaChange.resetTable(MYSQL, MY, false));
        assertEquals(List.of("DELETE FROM \"main\".\"orders\"", "DELETE FROM sqlite_sequence WHERE name = 'orders'"),
                SchemaChange.resetTable(SQLITE, LITE, true));
        assertEquals(List.of("DROP TABLE `shop`.`orders`"), SchemaChange.dropTable(MYSQL, MY));
        assertEquals(List.of("ALTER TABLE \"app\".\"orders\" RENAME TO \"orders_old\""), SchemaChange.renameTable(POSTGRESQL, PG, "orders_old"));
        assertEquals(List.of("RENAME TABLE `shop`.`orders` TO `shop`.`orders_old`"), SchemaChange.renameTable(MYSQL, MY, " orders_old "));
        assertEquals(List.of(), SchemaChange.renameTable(SQLITE, LITE, "orders"));
        assertEquals(List.of("CREATE TABLE \"app\".\"orders_backup_20261023\" AS SELECT * FROM \"app\".\"orders\""),
                SchemaChange.backupTable(POSTGRESQL, PG, SchemaChange.backupName(PG, java.time.LocalDate.of(2026, 10, 23))));
        assertThrows(IllegalArgumentException.class, () -> SchemaChange.backupTable(POSTGRESQL, PG, "orders"));
    }
}
