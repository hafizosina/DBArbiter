package com.dbadmin.service.compare;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.compare.DataCompare.RowStatus;
import com.dbadmin.service.compare.KeyJoins.Join;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyJoinsTest {

    /** m_product_image mpi → m_product_etalase mpe (etalase_id) → m_product_tier mpt (tier_3): key mpt.code. */
    private static final List<Join> JOINS = List.of(
            new Join(false, "m_product_etalase", "mpe", "mpe.id = mpi.etalase_id"),
            new Join(true, "m_product_tier", "mpt", "mpt.id = mpe.tier_3"));

    @Test
    void problemsAreExplained() {
        assertNull(KeyJoins.problem("mpi", JOINS));
        assertTrue(KeyJoins.problem("", JOINS).contains("alias"));
        assertTrue(KeyJoins.problem("mpe", JOINS).contains("used twice"));
        assertTrue(KeyJoins.problem("mpi", List.of(new Join(false, "t", "x", ""))).contains("ON condition"));
        assertTrue(KeyJoins.problem("mpi", List.of(new Join(false, "t", "x", "1=1; delete from t"))).contains("read-only"));
        assertTrue(KeyJoins.problem("mpi", List.of(new Join(false, null, "x", "x.id = mpi.x_id"))).contains("table"));
    }

    @Test
    void selectsTheMainTableWithItsJoinedKey() {
        assertEquals("""
                SELECT * FROM (
                SELECT mpi.*, mpt."code" AS "mpt.code"
                FROM "s"."m_product_image" mpi
                JOIN "s"."m_product_etalase" mpe ON (mpe.id = mpi.etalase_id)
                LEFT JOIN "s"."m_product_tier" mpt ON (mpt.id = mpe.tier_3)
                ) q WHERE (is_deleted = 0) ORDER BY "mpt.code", "image_url\"""",
                KeyJoins.select("\"s\".\"m_product_image\"", "mpi", JOINS,
                        List.of("\"s\".\"m_product_etalase\"", "\"s\".\"m_product_tier\""), List.of("mpt.code"),
                        "is_deleted = 0", List.of("mpt.code", "image_url"), "\""));
        assertEquals("join m_product_etalase mpe on mpe.id = mpi.etalase_id", KeyJoins.describe(JOINS.get(0)));
    }

    /** The ids of every table differ between the databases; the tier code still matches the images. */
    @Test
    void matchesRowsByAKeyFromJoinedTables(@TempDir Path dir) throws Exception {
        Path uat = dir.resolve("uat.db");
        Path pre = dir.resolve("pre.db");
        create(uat, """
                insert into m_product_tier values (1, 'GOLD'), (2, 'SILVER'), (3, 'BRONZE');
                insert into m_product_etalase values (10, 1), (11, 2), (12, 3);
                insert into m_product_image values (100, 10, 'gold.png', 0), (101, 11, 'silver-new.png', 0), (102, 12, 'bronze.png', 0);
                """);
        create(pre, """
                insert into m_product_tier values (7, 'GOLD'), (8, 'SILVER'), (9, 'PLATINUM');
                insert into m_product_etalase values (70, 7), (71, 8), (72, 9);
                insert into m_product_image values (500, 70, 'gold.png', 0), (501, 71, 'silver.png', 0), (502, 72, 'plat.png', 0);
                """);
        try (DatabaseSession s1 = DatabaseSession.open(sqlite(uat)); DatabaseSession s2 = DatabaseSession.open(sqlite(pre))) {
            List<String> keys = List.of("mpt.code");
            DataCompare.Result r = DataCompare.compare(read(s1, keys), read(s2, keys), keys, List.of("image_url"), List.of(), 100);
            Map<String, RowStatus> status = r.rows().stream().collect(Collectors.toMap(DataCompare.RowDiff::key, DataCompare.RowDiff::status));
            assertEquals(Map.of("GOLD", RowStatus.MATCH, "SILVER", RowStatus.CHANGED,
                    "BRONZE", RowStatus.ONLY_SOURCE, "PLATINUM", RowStatus.ONLY_TARGET), status);
            // the whole main row is kept (for INSERT / UPDATE later), plus the joined key
            assertEquals(List.of("id", "etalase_id", "image_url", "is_deleted", "mpt.code"), r.sourceColumns());
        }
    }

    /** category_id differs between the databases (other ids), so it is compared by the category's code. */
    @Test
    void comparesAnIdColumnByTheCodeItPointsTo(@TempDir Path dir) throws Exception {
        Path uat = dir.resolve("uat.db");
        Path pre = dir.resolve("pre.db");
        String tables = "create table m_product_category (id integer primary key, category_code text);"
                + "create table m_product_variant (id integer primary key, product_code text, category_id integer, price numeric)";
        exec(uat, tables, "insert into m_product_category values (1, 'BT'), (2, 'GJ')",
                "insert into m_product_variant values (10, 'AA00647', 1, 100), (11, 'AA00648', 1, 200), (12, 'AA00649', 2, 300)");
        exec(pre, tables, "insert into m_product_category values (7, 'BT'), (8, 'GJ')",
                "insert into m_product_variant values (50, 'AA00647', 7, 100), (51, 'AA00648', 8, 200), (52, 'AA00649', 8, 300)");
        List<Join> joins = List.of(new Join(true, "m_product_category", "mpc", "mpc.id = mpv.category_id"));
        List<String> keys = List.of("product_code");
        try (DatabaseSession s1 = DatabaseSession.open(sqlite(uat)); DatabaseSession s2 = DatabaseSession.open(sqlite(pre))) {
            DataCompare.Result r = DataCompare.compare(readVariants(s1, joins, keys), readVariants(s2, joins, keys), keys,
                    List.of("mpc.category_code", "price"), List.of(), 100);
            Map<String, RowStatus> status = r.rows().stream().collect(Collectors.toMap(DataCompare.RowDiff::key, DataCompare.RowDiff::status));
            // AA00647: category ids 1 vs 7 but both BT -> the same; AA00648: BT vs GJ -> changed
            assertEquals(Map.of("AA00647", RowStatus.MATCH, "AA00648", RowStatus.CHANGED, "AA00649", RowStatus.MATCH), status);
            assertTrue(r.rows().stream().filter(d -> d.key().equals("AA00648")).findFirst().get().details().contains("mpc.category_code: BT → GJ"));
        }
    }

    private static com.dbadmin.model.TableData readVariants(DatabaseSession s, List<Join> joins, List<String> keys) throws Exception {
        String sql = KeyJoins.select(s.qualifiedName(table("m_product_variant")), "mpv", joins,
                List.of(s.qualifiedName(table("m_product_category"))), List.of("mpc.category_code"), "", keys, s.identifierQuote());
        return s.execute(sql, 1000).data();
    }

    private static void exec(Path file, String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            for (String block : statements) for (String sql : block.split(";")) if (!sql.isBlank()) st.executeUpdate(sql);
        }
    }

    private static com.dbadmin.model.TableData read(DatabaseSession s, List<String> keys) throws Exception {
        String sql = KeyJoins.select(s.qualifiedName(table("m_product_image")), "mpi", JOINS,
                List.of(s.qualifiedName(table("m_product_etalase")), s.qualifiedName(table("m_product_tier"))),
                List.of("mpt.code"), "is_deleted = 0", keys, s.identifierQuote());
        return s.execute(sql, 1000).data();
    }

    private static TableRef table(String name) {
        return new TableRef(null, null, name, "TABLE");
    }

    private static void create(Path file, String rows) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            st.executeUpdate("create table m_product_tier (id integer primary key, code text)");
            st.executeUpdate("create table m_product_etalase (id integer primary key, tier_3 integer)");
            st.executeUpdate("create table m_product_image (id integer primary key, etalase_id integer, image_url text, is_deleted integer)");
            for (String sql : rows.split(";")) if (!sql.isBlank()) st.executeUpdate(sql);
        }
    }

    private static ConnectionConfig sqlite(Path file) {
        ConnectionConfig c = new ConnectionConfig();
        c.setId(file.getFileName().toString());
        c.setName(file.getFileName().toString());
        c.setType(DbType.SQLITE);
        c.setDatabase(file.toString());
        return c;
    }
}
