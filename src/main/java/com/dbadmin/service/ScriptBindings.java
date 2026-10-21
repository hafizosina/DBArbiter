package com.dbadmin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Remembers which connection (by id, never by name) each saved script belongs to, in
 * ~/.dbadmin/scripts.json, so the .sql files themselves stay plain SQL.
 */
public class ScriptBindings {

    private final Path file = Path.of(System.getProperty("user.home"), ".dbadmin", "scripts.json");
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, String> connectionByScript = new TreeMap<>();

    public ScriptBindings() {
        if (Files.exists(file)) {
            try {
                connectionByScript.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, String>>() {}));
            } catch (IOException e) {
                // unreadable index: scripts open without a connection, which is the safe default
                AppLog.get(ScriptBindings.class).warning("Cannot read " + file + ", scripts open without a connection: "
                        + e.getMessage());
            }
        }
    }

    /** The id of the connection the script was saved for, or null if unknown. */
    public String connectionFor(Path script) {
        return connectionByScript.get(key(script));
    }

    /** Every script with a recorded connection, as path -> connection id. */
    public Map<Path, String> all() {
        Map<Path, String> result = new java.util.LinkedHashMap<>();
        connectionByScript.forEach((path, id) -> result.put(Path.of(path), id));
        return result;
    }

    public void bind(Path script, String connectionId) {
        connectionByScript.put(key(script), connectionId);
        try {
            JsonFiles.write(mapper, file, connectionByScript, false);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private static String key(Path script) {
        return script.toAbsolutePath().normalize().toString();
    }
}
