package com.dbadmin.service.compare;

import com.dbadmin.service.compare.DataCompare.RowStatus;
import com.dbadmin.service.compare.KeyJoins.Join;
import com.dbadmin.service.compare.MigrationSql.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationSqlTest {

    private static final MigrationSql.Options OPTIONS = new MigrationSql.Options(true, "SYSTEM", true);

    private static final List<Column> TIER = List.of(new Column("id", "int8", true), new Column("parent_id", "int8", false),
            new Column("name", "varchar", false), new Column("code", "varchar", false), new Column("tier", "int4", false),
            new Column("created_date", "timestamp", false), new Column("created_by", "varchar", false),
            new Column("modified_date", "timestamp", false), new Column("modified_by", "varchar", false),
            new Column("is_deleted", "bool", false));
    private static final List<Column> VARIANT = List.of(new Column("id", "int8", true), new Column("product_code", "varchar", false),
            new Column("is_deleted", "bool", false));
    private static final List<Column> PUBLISH = List.of(new Column("id", "int8", true), new Column("product_id", "int8", false),
            new Column("tier", "int8", false), new Column("priority", "int4", false), new Column("publication_date", "timestamp", false),
            new Column("image_url", "varchar", false), new Column("is_deleted", "bool", false));

    private static DataCompareReport report(String table, String alias, List<Join> joins, List<String> keys, List<String> compared,
                                            List<String> sourceColumns) {
        DataCompareReport r = new DataCompareReport();
        r.created = "2026-10-22T20:00:00";
        r.source = new DataCompareReport.Side("UAT", "brankas_mobile", table, "UAT");
        r.target = new DataCompareReport.Side("Prod", "brankas_mobile", table, "Prod");
        r.keyColumns = keys;
        r.comparedColumns = compared;
        List<String> shown = new ArrayList<>(keys);
        shown.addAll(compared);
        r.shownColumns = shown;
        r.sourceColumns = sourceColumns;
        r.where = "is_deleted = false";
        r.mainAlias = alias;
        r.joins = joins;
        return r;
    }

    private static DataCompareReport.Row row(RowStatus status, Verdict verdict, String key, List<String> source, List<String> target,
                                             String... sourceRow) {
        DataCompareReport.Row row = new DataCompareReport.Row();
        row.status = status;
        row.verdict = verdict;
        row.key = key;
        row.source = source;
        row.target = target;
        row.sourceRow = sourceRow.length == 0 ? null : Arrays.asList(sourceRow);
        return row;
    }

    private static List<String> values(String... v) {
        return Arrays.asList(v);
    }

    /** m_product_publish: product_id and tier found again by the variant's product_code and the tier's code. */
    @Test
    void foreignKeysAreFoundByTheJoinedColumns() {
        DataCompareReport r = report("m_product_publish", "mpp", List.of(
                        new Join(false, "m_product_variant", "mpv", "mpv.id = mpp.product_id"),
                        new Join(false, "m_product_tier", "mpt", "mpt.id = mpp.tier")),
                List.of("mpv.product_code"), List.of("priority", "publication_date", "mpt.code"),
                List.of("id", "product_id", "tier", "priority", "publication_date", "image_url", "is_deleted", "mpv.product_code", "mpt.code"));
        r.targetRows = 1;
        r.rows.add(row(RowStatus.ONLY_SOURCE, Verdict.INSERT, "AA01", values("AA01", null, "2022-10-27 00:00:00.0", "A02.A03"), null,
                "113", "14", "114", null, "2022-10-27 00:00:00.0", "it's.jpg", "false", "AA01", "A02.A03"));
        r.rows.add(row(RowStatus.CHANGED, Verdict.UPDATE, "AA02", values("AA02", "2", null, "A02.A04"), values("AA02", "2", null, "A02.A03"),
                "120", "15", "115", "2", null, "x.jpg", "false", "AA02", "A02.A04"));
        r.rows.add(row(RowStatus.CHANGED, Verdict.SKIP, "AA03", values("AA03", "1", null, "A02.A04"), values("AA03", "3", null, "A02.A04"),
                "121", "16", "116", "1", null, "y.jpg", "false", "AA03", "A02.A04"));
        String sql = MigrationSql.generate(r, Map.of("m_product_publish", PUBLISH, "m_product_variant", VARIANT, "m_product_tier", TIER),
                OPTIONS, "M_PRODUCT_PUBLISH.dbcompare");

        // the source ids (14, 114, 113) are not copied: product_id and tier are looked up, id comes from the sequence
        assertTrue(sql.contains("(SELECT mpv.id FROM brankas_mobile.m_product_variant mpv WHERE mpv.product_code = 'AA01' "
                + "AND mpv.is_deleted = false ORDER BY mpv.id LIMIT 1) AS product_id"), sql);
        assertTrue(sql.contains("(SELECT mpt.id FROM brankas_mobile.m_product_tier mpt WHERE mpt.code = 'A02.A03' "
                + "AND mpt.is_deleted = false ORDER BY mpt.id LIMIT 1) AS tier"), sql);
        assertFalse(sql.contains("'113'") || sql.contains(" 113 ") || sql.contains("114 AS"), sql);
        assertTrue(sql.contains("INSERT INTO brankas_mobile.m_product_publish (product_id, tier, priority, publication_date, image_url, is_deleted)"), sql);
        // typed literals: NULL of an int, a timestamp, an escaped text, a boolean
        assertTrue(sql.contains("NULL::int4 AS priority"), sql);
        assertTrue(sql.contains("'2022-10-27 00:00:00.0'::timestamp AS publication_date"), sql);
        assertTrue(sql.contains("'it''s.jpg' AS image_url"), sql);
        assertTrue(sql.contains("false AS is_deleted"), sql);
        // only inserted when the variant has no publish yet
        assertTrue(sql.contains("WHERE NOT EXISTS (SELECT 1 FROM brankas_mobile.m_product_publish mpp WHERE (is_deleted = false) AND "
                + "EXISTS (SELECT 1 FROM brankas_mobile.m_product_variant mpv WHERE (mpv.id = mpp.product_id) AND mpv.product_code = 'AA01'));"), sql);
        // the update sets only what differs (the tier, by its code); the table has no modified_* to fill
        assertTrue(sql.contains("--AA02: mpt.code\nUPDATE brankas_mobile.m_product_publish mpp SET\n"
                + "  tier = (SELECT mpt.id FROM brankas_mobile.m_product_tier mpt WHERE mpt.code = 'A02.A04' AND mpt.is_deleted = false ORDER BY mpt.id LIMIT 1)\n"
                + "WHERE (is_deleted = false) AND EXISTS (SELECT 1 FROM brankas_mobile.m_product_variant mpv WHERE (mpv.id = mpp.product_id) AND mpv.product_code = 'AA02');"), sql);
        assertFalse(sql.contains("AA03"), "SKIP is left out");
        // the guard looks for every variant and tier the new values point to
        assertTrue(sql.contains("FROM (VALUES ('AA01')) AS v(product_code)"), sql);
        assertTrue(sql.contains("FROM (VALUES ('A02.A03'), ('A02.A04')) AS v(code)"), sql);
        assertTrue(sql.contains("RAISE EXCEPTION 'm_product_tier rows missing on Prod (tier by mpt.code): %', missing;"), sql);
        assertTrue(sql.contains("-- check: expect 2 rows (1 at the compare, +1 inserted)"), sql);
        assertTrue(sql.startsWith("-- m_product_publish: UAT -> Prod") && sql.contains("\nBEGIN;\n") && sql.endsWith("COMMIT;\n"), sql);
    }

    /** m_product_tier joined to itself (the parent by code): a new parent is inserted before its new children. */
    @Test
    void parentsAreInsertedFirst() {
        DataCompareReport r = report("m_product_tier", "mpt", List.of(new Join(false, "m_product_tier", "mpt2", "mpt2.id = mpt.parent_id")),
                List.of("code", "tier"), List.of("name", "mpt2.code"),
                List.of("id", "parent_id", "name", "code", "tier", "created_date", "created_by", "modified_date", "modified_by", "is_deleted", "mpt2.code"));
        r.rows.add(row(RowStatus.ONLY_SOURCE, Verdict.INSERT, "A01.A02.A03 | 3", values("A01.A02.A03", "3", "child", "A01.A02"), null,
                "30", "20", "child", "A01.A02.A03", "3", "2026-01-01 00:00:00", "someone", null, null, "false", "A01.A02"));
        r.rows.add(row(RowStatus.ONLY_SOURCE, Verdict.INSERT, "A01.A02 | 2", values("A01.A02", "2", "parent", "A01"), null,
                "20", "10", "parent", "A01.A02", "2", "2026-01-01 00:00:00", "someone", null, null, "false", "A01"));
        String sql = MigrationSql.generate(r, Map.of("m_product_tier", TIER), OPTIONS, null);

        assertTrue(sql.indexOf("--A01.A02 | 2") < sql.indexOf("--A01.A02.A03 | 3"), sql);
        assertTrue(sql.contains("(SELECT mpt2.id FROM brankas_mobile.m_product_tier mpt2 WHERE mpt2.code = 'A01.A02' AND mpt2.is_deleted = false "
                + "ORDER BY mpt2.id LIMIT 1) AS parent_id"), sql);
        // audit columns are set, not copied
        assertTrue(sql.contains("now() AS created_date,\n  'SYSTEM' AS created_by,\n  NULL::timestamp AS modified_date,\n  NULL AS modified_by"), sql);
        assertFalse(sql.contains("someone"), sql);
        // the parent inserted by the script is not looked for before it runs; A01 is
        assertTrue(sql.contains("FROM (VALUES ('A01')) AS v(code)"), sql);
        assertTrue(sql.contains("WHERE NOT EXISTS (SELECT 1 FROM brankas_mobile.m_product_tier mpt WHERE (is_deleted = false) "
                + "AND mpt.code = 'A01.A02' AND mpt.tier = 2);"), sql);
    }

    /** m_product_image (one-to-many): the etalase found through its tier's code; DELETE keeps the row as is_deleted. */
    @Test
    void chainedJoinsAndDeletes() {
        List<Column> image = List.of(new Column("id", "int8", true), new Column("image_path", "varchar", false),
                new Column("main_image", "bool", false), new Column("etalase_id", "int8", false), new Column("is_deleted", "bool", false),
                new Column("modified_date", "timestamp", false), new Column("modified_by", "varchar", false));
        List<Column> etalase = List.of(new Column("id", "int8", true), new Column("tier_3", "int8", false), new Column("is_deleted", "bool", false));
        DataCompareReport r = report("m_product_image", "mpi", List.of(
                        new Join(true, "m_product_etalase", "mpe", "mpe.id = mpi.etalase_id"),
                        new Join(true, "m_product_tier", "mpt", "mpt.id = mpe.tier_3")),
                List.of("mpt.code"), List.of("image_path", "main_image"),
                List.of("id", "image_path", "main_image", "etalase_id", "is_deleted", "modified_date", "modified_by", "mpt.code"));
        r.oneToMany = true;
        r.targetRows = 5;
        r.rows.add(row(RowStatus.ONLY_SOURCE, Verdict.INSERT, "A02.A01.A38", values("A02.A01.A38", "new.jpg", "true"), null,
                "330", "new.jpg", "true", "7", "false", null, null, "A02.A01.A38"));
        r.rows.add(row(RowStatus.ONLY_TARGET, Verdict.DELETE, "A02.A01.A38", null, values("A02.A01.A38", "old.jpg", "false")));
        Map<String, List<Column>> tables = Map.of("m_product_image", image, "m_product_etalase", etalase, "m_product_tier", TIER);
        String sql = MigrationSql.generate(r, tables, OPTIONS, null);

        assertTrue(sql.contains("(SELECT mpe.id FROM brankas_mobile.m_product_etalase mpe, brankas_mobile.m_product_tier mpt "
                + "WHERE (mpt.id = mpe.tier_3) AND mpt.code = 'A02.A01.A38' AND mpe.is_deleted = false AND mpt.is_deleted = false "
                + "ORDER BY mpe.id LIMIT 1) AS etalase_id"), sql);
        // one-to-many: a row is the key and its compared values
        String old = "mpi.image_path = 'old.jpg' AND mpi.main_image = false AND EXISTS (SELECT 1 FROM brankas_mobile.m_product_etalase mpe, "
                + "brankas_mobile.m_product_tier mpt WHERE (mpe.id = mpi.etalase_id) AND (mpt.id = mpe.tier_3) AND mpt.code = 'A02.A01.A38');";
        assertTrue(sql.contains("UPDATE brankas_mobile.m_product_image mpi SET is_deleted = true, modified_date = now(), modified_by = 'SYSTEM'\n"
                + "WHERE (is_deleted = false) AND " + old), sql);
        assertTrue(sql.contains("mpi.image_path = 'new.jpg' AND mpi.main_image = true AND EXISTS"), sql);
        assertTrue(sql.contains("-- check: expect 5 rows (5 at the compare, +1 inserted, -1 deleted)"), sql);

        String hard = MigrationSql.generate(r, tables, new MigrationSql.Options(false, "SYSTEM", false), null);
        assertTrue(hard.contains("DELETE FROM brankas_mobile.m_product_image mpi\nWHERE (is_deleted = false) AND " + old), hard);
        assertFalse(hard.contains("'SYSTEM'"), hard);
    }

    @Test
    void literalsFollowTheColumnType() {
        assertEquals("12.5", MigrationSql.literal("12.5", new Column("c", "numeric", false)));
        assertEquals("'abc'::int4", MigrationSql.literal("abc", new Column("c", "int4", false)));
        assertEquals("true", MigrationSql.literal("t", new Column("c", "bool", false)));
        assertEquals("'{\"a\":1}'::jsonb", MigrationSql.literal("{\"a\":1}", new Column("c", "jsonb", false)));
        assertEquals("'{1,2}'::int4[]", MigrationSql.literal("{1,2}", new Column("c", "_int4", false)));
        assertEquals("NULL", MigrationSql.literal(null, new Column("c", "varchar", false)));
        assertEquals("'x'", MigrationSql.literal("x", null));
        assertEquals("\"name\"", MigrationSql.name("name"));
        assertEquals("\"Code\"", MigrationSql.name("Code"));
        assertEquals("code", MigrationSql.name("code"));
    }

    /** A primary key the target does not generate (uuid): copied, or left to the target's default with the option. */
    @Test
    void insertsCanLeaveOutAPrimaryKeyTheTargetDoesNotGenerate() {
        List<Column> param = List.of(new Column("id", "uuid", false, true), new Column("code", "varchar", false, false),
                new Column("value", "varchar", false, false), new Column("is_deleted", "bool", false, false));
        DataCompareReport r = report("m_param", "mp", List.of(), List.of("code"), List.of("value"), List.of("id", "code", "value", "is_deleted"));
        r.rows.add(row(RowStatus.ONLY_SOURCE, Verdict.INSERT, "A", values("A", "x"), null, "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", "A", "x", "false"));

        String copied = MigrationSql.generate(r, Map.of("m_param", param), OPTIONS, null);
        assertTrue(copied.contains("INSERT INTO brankas_mobile.m_param (id, code, \"value\", is_deleted)"), copied);

        MigrationSql.Options without = new MigrationSql.Options(true, "SYSTEM", true, true);
        String sql = MigrationSql.generate(r, Map.of("m_param", param), without, null);
        assertTrue(sql.contains("INSERT INTO brankas_mobile.m_param (code, \"value\", is_deleted)"), sql);
        assertTrue(sql.contains("-- new id: left out of the INSERTs"), sql);
        assertFalse(sql.contains("review: the key id"), sql); // matched by code: the script can run again

        DataCompareReport byId = report("m_param", "mp", List.of(), List.of("id"), List.of("code", "value"), List.of("id", "code", "value", "is_deleted"));
        byId.rows.add(row(RowStatus.ONLY_SOURCE, Verdict.INSERT, "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
                values("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", "A", "x"), null, "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", "A", "x", "false"));
        String again = MigrationSql.generate(byId, Map.of("m_param", param), without, null);
        assertTrue(again.contains("the key id is left out of the INSERTs"), again);
    }

}
