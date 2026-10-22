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
 * <p>
 * A script in the app's Script folder is recorded relative to it ("Script/orders/fix.sql"), so moving or
 * re-extracting the app keeps its scripts' connections; a script elsewhere is recorded by its absolute path.
 * Files written before that (every key absolute) are read too: a key inside today's Script folder becomes
 * relative, and one in another app folder's Script folder (the app was moved) is matched to the same file here.
 */
public class ScriptBindings {

    /** The start of a key relative to the Script folder. */
    static final String IN_SCRIPT_FOLDER = "Script/";

    private final Path file;
    private final Path scriptDir;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, String> connectionByScript = new TreeMap<>();

    public ScriptBindings() {
        this(Path.of(System.getProperty("user.home"), ".dbadmin", "scripts.json"), ScriptFolder.defaultDir());
    }

    ScriptBindings(Path file, Path scriptDir) {
        this.file = file;
        this.scriptDir = scriptDir.toAbsolutePath().normalize();
        if (Files.exists(file)) {
            try {
                Map<String, String> saved = mapper.readValue(file.toFile(), new TypeReference<Map<String, String>>() {});
                saved.forEach((key, id) -> connectionByScript.put(upgraded(key), id));
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

    /** Every script with a recorded connection, as absolute path -> connection id. */
    public Map<Path, String> all() {
        Map<Path, String> result = new java.util.LinkedHashMap<>();
        connectionByScript.forEach((key, id) -> result.put(path(key), id));
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

    /** "Script/a/b.sql" for a script in the Script folder (with / on every system), else the absolute path. */
    private String key(Path script) {
        Path p = script.toAbsolutePath().normalize();
        if (!p.startsWith(scriptDir) || p.equals(scriptDir)) return p.toString();
        return IN_SCRIPT_FOLDER + scriptDir.relativize(p).toString().replace('\\', '/');
    }

    private Path path(String key) {
        return key.startsWith(IN_SCRIPT_FOLDER) ? scriptDir.resolve(key.substring(IN_SCRIPT_FOLDER.length())) : Path.of(key);
    }

    /**
     * A key from an older file: an absolute path inside the Script folder becomes relative; an absolute path into
     * another folder named Script that no longer exists (the app was moved) becomes relative when the same file
     * is in today's Script folder.
     */
    private String upgraded(String key) {
        if (key.startsWith(IN_SCRIPT_FOLDER)) return key;
        Path old = Path.of(key);
        String k = key(old);
        if (k.startsWith(IN_SCRIPT_FOLDER) || Files.exists(old)) return k;
        for (int i = old.getNameCount() - 2; i >= 0; i--) {
            if (!old.getName(i).toString().equals("Script")) continue;
            Path moved = scriptDir.resolve(old.subpath(i + 1, old.getNameCount()).toString());
            if (Files.exists(moved)) return key(moved);
            break;
        }
        return k;
    }
}
