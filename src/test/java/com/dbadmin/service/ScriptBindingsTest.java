package com.dbadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptBindingsTest {

    @TempDir
    Path tmp;

    private Map<String, String> saved(Path file) throws Exception {
        return new ObjectMapper().readValue(file.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    /** Scripts in the Script folder are saved relative to it, so a moved app folder keeps them. */
    @Test
    void scriptsInTheScriptFolderSurviveMovingTheApp() throws Exception {
        Path index = tmp.resolve("scripts.json");
        Path app = tmp.resolve("App/DBAdmin/Script");
        Files.createDirectories(app.resolve("orders"));
        Path script = Files.writeString(app.resolve("orders/fix.sql"), "select 1;");
        Path elsewhere = Files.writeString(tmp.resolve("other.sql"), "select 2;");
        ScriptBindings b = new ScriptBindings(index, app);
        b.bind(script, "c1");
        b.bind(elsewhere, "c2");
        assertEquals("c1", saved(index).get("Script/orders/fix.sql"));
        assertEquals("c2", saved(index).get(elsewhere.toAbsolutePath().normalize().toString()));

        // the app folder moves; the scripts come along
        Path moved = tmp.resolve("App/DBArbiter/Script");
        Files.createDirectories(moved.getParent());
        Files.move(app, moved);
        ScriptBindings after = new ScriptBindings(index, moved);
        assertEquals("c1", after.connectionFor(moved.resolve("orders/fix.sql")));
        assertEquals("c2", after.connectionFor(elsewhere));
        assertEquals("c1", after.all().get(moved.resolve("orders/fix.sql")));
    }

    /** A file written before relative keys: absolute keys still work and turn relative on the next save. */
    @Test
    void readsOldAbsoluteKeys() throws Exception {
        Path index = tmp.resolve("scripts.json");
        Path dir = tmp.resolve("App/DBArbiter/Script");
        Files.createDirectories(dir);
        Path here = Files.writeString(dir.resolve("a.sql"), "");
        Path fromOldFolder = Files.writeString(dir.resolve("b.sql"), "");
        Path gone = tmp.resolve("App/DBAdmin/Script/b.sql"); // the old app folder: no longer there
        Path missing = tmp.resolve("nowhere/Script/c.sql");
        Files.writeString(index, new ObjectMapper().writeValueAsString(Map.of(
                here.toString(), "c1", gone.toString(), "c2", missing.toString(), "c3")));

        ScriptBindings b = new ScriptBindings(index, dir);
        assertEquals("c1", b.connectionFor(here));
        assertEquals("c2", b.connectionFor(fromOldFolder), "matched by its place in the Script folder");
        assertNull(b.connectionFor(dir.resolve("c.sql")), "a file that is in neither place is not guessed");

        b.bind(Files.writeString(dir.resolve("d.sql"), ""), "c4");
        Map<String, String> after = saved(index);
        assertEquals("c1", after.get("Script/a.sql"));
        assertEquals("c2", after.get("Script/b.sql"));
        assertEquals("c4", after.get("Script/d.sql"));
        assertTrue(after.containsKey(missing.toString()), "kept as it was");
    }
}
