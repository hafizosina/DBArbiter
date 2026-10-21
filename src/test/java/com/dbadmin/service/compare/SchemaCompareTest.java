package com.dbadmin.service.compare;

import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession.ColumnDef;
import com.dbadmin.service.compare.SchemaCompare.Item;
import com.dbadmin.service.compare.SchemaCompare.Status;
import com.dbadmin.service.compare.SchemaCompare.TableDiff;
import com.dbadmin.service.compare.SchemaCompare.TableSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaCompareTest {

    private static TableSnapshot table(String name, Map<String, String> columns) {
        return new TableSnapshot(new TableRef(null, "public", name, "TABLE"), columns, Map.of(), Map.of(), Map.of(), null);
    }

    private static TableSnapshot view(String name, String sql) {
        return new TableSnapshot(new TableRef(null, "public", name, "VIEW"), Map.of("id", "int4 NULL"), Map.of(), Map.of(),
                Map.of(), sql);
    }

    /** Keeps insertion order, like the snapshots do for columns. */
    private static Map<String, String> map(String... keysAndValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put(keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    @Test
    void describesColumnsWithTheSizesThatMatter() {
        assertEquals("numeric(18,2) NOT NULL DEFAULT 0 PK",
                SchemaCompare.describe(new ColumnDef("amount", "NUMERIC", 18, 2, false, "0"), true));
        assertEquals("varchar(50) NULL", SchemaCompare.describe(new ColumnDef("name", "varchar", 50, null, true, null), false));
        // an integer's size is not part of its type
        assertEquals("int4 NOT NULL DEFAULT nextval('s'::regclass)",
                SchemaCompare.describe(new ColumnDef("id", "int4", 10, 0, false, "nextval('s'::regclass)"), false));
        // unbounded: no size, whatever the driver reports
        assertEquals("text NULL DEFAULT 'a b'",
                SchemaCompare.describe(new ColumnDef("body", "text", Integer.MAX_VALUE, null, true, "  'a\n  b'  "), false));
        assertEquals("numeric NULL", SchemaCompare.describe(new ColumnDef("n", "numeric", 131089, 0, true, ""), false));
    }

    @Test
    void comparesNamedEntries() {
        List<Item> items = SchemaCompare.compareMaps("Column",
                map("id", "int4", "name", "text", "old", "text"),
                map("id", "int4", "name", "varchar(20)", "new", "text"), false);
        assertEquals(List.of(
                new Item("Column", "id", Status.SAME, "int4", "int4"),
                new Item("Column", "name", Status.CHANGED, "text", "varchar(20)"),
                new Item("Column", "old", Status.ONLY_SOURCE, "text", ""),
                new Item("Column", "new", Status.ONLY_TARGET, "", "text")), items);
    }

    @Test
    void entriesRenamedWithTheSameDefinitionCountAsTheSame() {
        Map<String, String> src = map("users_email_idx", "UNIQUE (email)");
        Map<String, String> dst = map("idx_16384", "UNIQUE (email)");
        assertEquals(List.of(new Item("Index", "users_email_idx = idx_16384", Status.SAME, "UNIQUE (email)", "UNIQUE (email)")),
                SchemaCompare.compareMaps("Index", src, dst, true));
        // columns are never paired up that way: a renamed column is a real difference
        assertEquals(List.of(Status.ONLY_SOURCE, Status.ONLY_TARGET),
                SchemaCompare.compareMaps("Column", src, dst, false).stream().map(Item::status).toList());
    }

    @Test
    void tablesOnOneSideOnly() {
        List<TableDiff> diffs = SchemaCompare.compare(
                List.of(table("orders", map("id", "int4")), table("users", map("id", "int4"))),
                List.of(table("Users", map("id", "int4")), table("audit", map("id", "int4"))));
        // matched by name ignoring case, listed in name order
        assertEquals(List.of("audit", "orders", "users"), diffs.stream().map(TableDiff::name).toList());
        assertEquals(List.of(Status.ONLY_TARGET, Status.ONLY_SOURCE, Status.SAME), diffs.stream().map(TableDiff::status).toList());
        assertEquals("TABLE", diffs.get(0).kind());
    }

    @Test
    void aChangedColumnChangesTheTable() {
        TableDiff d = SchemaCompare.compare(
                List.of(table("users", map("id", "int4", "name", "text"))),
                List.of(table("users", map("id", "int4", "name", "varchar(20)")))).get(0);
        assertEquals(Status.CHANGED, d.status());
        assertEquals(1, d.differences());
    }

    @Test
    void viewSqlIsComparedIgnoringLayout() {
        TableDiff same = SchemaCompare.compare(List.of(view("v", "select id\n  from t;")), List.of(view("v", " select id from t"))).get(0);
        assertEquals(Status.SAME, same.status());
        assertEquals("Definition", same.items().get(0).category());

        TableDiff changed = SchemaCompare.compare(List.of(view("v", "select id from t")),
                List.of(view("v", "select id from t where id > 0"))).get(0);
        assertEquals(Status.CHANGED, changed.status());
        assertEquals(new Item("Definition", "SQL", Status.CHANGED, "select id from t", "select id from t where id > 0"),
                changed.items().get(0));
    }
}
