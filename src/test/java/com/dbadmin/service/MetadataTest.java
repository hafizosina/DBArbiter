package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The structure, read through a session (which hands it to Metadata), on SQLite: no server needed. */
class MetadataTest {

    @TempDir
    Path tmp;

    @Test
    void readsTheStructure() throws Exception {
        Path file = tmp.resolve("t.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("create table tier (id integer primary key, code text not null unique)");
            st.execute("create table variant (id integer primary key, weight numeric check (weight >= 0), tier_id integer references tier(id))");
            st.execute("create view v as select id from variant");
        }
        ConnectionConfig config = new ConnectionConfig();
        config.setName("t");
        config.setType(DbType.SQLITE);
        config.setDatabase(file.toString());
        try (DatabaseSession s = DatabaseSession.open(config)) {
            // these depend on the database type: a session whose Metadata missed the config fails here
            assertEquals("main", s.defaultNamespace());
            List<Namespace> namespaces = s.listNamespaces();
            assertEquals(List.of("main"), namespaces.stream().map(Namespace::label).toList());
            List<TableRef> tables = s.listTables(namespaces.get(0));
            assertEquals(List.of("tier", "v", "variant"), tables.stream().map(TableRef::name).sorted().toList());
            TableRef variant = s.resolveTable(null, "variant");
            assertEquals(List.of("id"), s.primaryKey(variant));
            assertTrue(s.listConstraints(variant).rows().stream().anyMatch(r -> String.valueOf(r.get(3)).contains("weight >= 0")));
            assertEquals("tier", s.listForeignKeys(variant).rows().get(0).get(2));
            assertTrue(s.ddl(variant).startsWith("CREATE TABLE"));
            assertTrue(s.viewDefinition(tables.stream().filter(TableRef::isView).findFirst().orElseThrow()).contains("select id from variant"));
        }
    }
}
