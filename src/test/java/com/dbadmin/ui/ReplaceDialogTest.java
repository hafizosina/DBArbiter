package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.scan.AlsoSetStore;
import com.dbadmin.service.scan.ReplaceSql;
import com.dbadmin.service.scan.ScanColumn;
import com.dbadmin.service.scan.ScanFinding;
import com.dbadmin.service.scan.ScanRequest;
import javafx.scene.control.ButtonType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Replace dialog against an SQLite file: columns that cannot change, checks, preview, the script. */
class ReplaceDialogTest {

    @TempDir
    Path tmp;

    private DatabaseSession session;

    @AfterEach
    void close() {
        if (session != null) session.close();
    }

    @Test
    void writesTheScriptForTheTickedColumns() throws Exception {
        Path file = tmp.resolve("shop.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("create table shops (code text primary key, name text)");
            st.execute("create table t (id integer primary key, shop_code text references shops(code), body varchar(40), "
                    + "doubled text generated always as (body || body), modified_by text)");
            st.execute("insert into shops values ('https://cdn/X.png', 'x')");
            st.execute("insert into t (shop_code, body) values ('https://cdn/X.png', '{\"icon\": \"https://cdn/X.png\"}')");
        }
        ConnectionConfig config = new ConnectionConfig();
        config.setName("shop");
        config.setType(DbType.SQLITE);
        config.setDatabase(file.toString());
        config.setReadOnly(false);
        session = DatabaseSession.open(config);
        TableRef t = session.resolveTable(null, "t");
        ScanRequest request = new ScanRequest(List.of("https://cdn/X.png"), false, true, List.of(), 30);
        List<ScanFinding> findings = List.of(
                new ScanFinding(t, new ScanColumn("shop_code", "TEXT", ScanColumn.Kind.TEXT), 1, List.of(1L), List.of(1L)),
                new ScanFinding(t, new ScanColumn("body", "VARCHAR(40)", ScanColumn.Kind.TEXT), 1, List.of(1L), List.of(1L)),
                new ScanFinding(t, new ScanColumn("doubled", "TEXT", ScanColumn.Kind.TEXT), 1, List.of(1L), List.of(1L)));
        AlsoSetStore store = new AlsoSetStore(tmp.resolve("also-set.json"));
        store.save(config.getId(), List.of(new ReplaceSql.AlsoSet("modified_by", "'SYSTEM'")));
        ReplaceDialog[] d = new ReplaceDialog[1];
        Fx.run(() -> d[0] = new ReplaceDialog(null, config, session, request, findings, store));
        for (int i = 0; i < 100 && !Fx.call(() -> d[0].ready()); i++) Thread.sleep(50);
        Fx.run(() -> {
            ReplaceDialog dialog = d[0];
            List<String> labels = dialog.columnList().items().stream().map(ReplaceDialog.ColumnChoice::label).toList();
            assertTrue(labels.get(0).endsWith("part of a key: not changed"), labels.toString());
            assertTrue(labels.get(2).endsWith("generated: not changed"), labels.toString());
            assertTrue(dialog.checksText().contains("Type what each value becomes."), dialog.checksText());
            dialog.newFields().get(0).setText("https://cdn/a/much/longer/path/x.png\"");
        });
        Thread.sleep(500); // the longest-value check reads the database
        Fx.run(() -> {
            ReplaceDialog dialog = d[0];
            String checks = dialog.checksText();
            assertTrue(checks.contains("holds JSON, and a value would no longer be valid JSON"), checks);
            assertTrue(checks.contains("t.body is varchar(40), but its longest value becomes 49 characters"), checks);
            assertTrue(dialog.previewText().contains("before  {\"icon\": \"https://cdn/X.png\"}"), dialog.previewText());
            dialog.newFields().get(0).setText("https://cdn/y.png");
            ButtonType create = dialog.getDialogPane().getButtonTypes().get(0);
            String script = dialog.getResultConverter().call(create);
            assertTrue(script.contains("SET \"body\" = REPLACE(\"body\", 'https://cdn/X.png', 'https://cdn/y.png'),\n    \"modified_by\" = 'SYSTEM'"), script);
            assertFalse(script.contains("shop_code\" ="), "a key column is not changed");
            assertFalse(script.contains("doubled\" ="), "a generated column is not changed");
        });
        assertEquals(List.of(new ReplaceSql.AlsoSet("modified_by", "'SYSTEM'")), store.load(config.getId()), "kept for next time");
    }
}
