package com.dbadmin.service.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlsoSetStoreTest {

    @Test
    void keepsAListPerConnection(@TempDir Path tmp) {
        AlsoSetStore store = new AlsoSetStore(tmp.resolve("also-set.json"));
        assertEquals(List.of(), store.load("prod"));
        store.save("prod", List.of(new ReplaceSql.AlsoSet("modified_date", "NOW()"), new ReplaceSql.AlsoSet("modified_by", "'SYSTEM'")));
        store.save("uat", List.of(new ReplaceSql.AlsoSet("updated_at", "now()")));
        AlsoSetStore again = new AlsoSetStore(tmp.resolve("also-set.json"));
        assertEquals("modified_by", again.load("prod").get(1).column());
        assertEquals(List.of(new ReplaceSql.AlsoSet("updated_at", "now()")), again.load("uat"));
    }
}
