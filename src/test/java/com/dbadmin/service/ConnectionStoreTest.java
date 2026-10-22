package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionStoreTest {

    @TempDir
    Path tmp;

    private static ConnectionConfig connection(String name, String password, boolean save) {
        ConnectionConfig c = new ConnectionConfig();
        c.setName(name);
        c.setDatabase("db");
        c.setUsername("me");
        c.setPassword(password);
        c.setSavePassword(save);
        return c;
    }

    /** "Save password" off: the password never reaches the file, and the setting comes back. */
    @Test
    void leavesOutPasswordsThatAreNotSaved() throws Exception {
        Path file = tmp.resolve("connections.json");
        ConnectionStore store = new ConnectionStore(file);
        ConnectionConfig kept = connection("kept", "secret-1", true);
        ConnectionConfig asked = connection("asked", "secret-2", false);
        store.save(List.of(kept, asked));

        String json = Files.readString(file);
        assertTrue(json.contains("secret-1"));
        assertFalse(json.contains("secret-2"));
        assertEquals("secret-2", asked.getPassword(), "the one in memory is untouched");

        List<ConnectionConfig> back = store.load();
        assertEquals("secret-1", back.get(0).getPassword());
        assertTrue(back.get(0).isSavePassword());
        assertNull(back.get(1).getPassword());
        assertFalse(back.get(1).isSavePassword());
    }

    /** Files from before the option keep their passwords: a missing "savePassword" means on. */
    @Test
    void olderFilesSavePasswords() throws Exception {
        Path file = tmp.resolve("connections.json");
        Files.writeString(file, "[{\"id\":\"a\",\"name\":\"old\",\"type\":\"POSTGRESQL\",\"database\":\"db\",\"password\":\"pw\"}]");
        ConnectionConfig c = new ConnectionStore(file).load().get(0);
        assertEquals("pw", c.getPassword());
        assertTrue(c.isSavePassword());
    }
}
