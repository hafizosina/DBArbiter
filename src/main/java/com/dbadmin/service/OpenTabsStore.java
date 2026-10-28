package com.dbadmin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The tabs open when the app was closed (~/.dbadmin/open-tabs.json), opened again at startup when the setting is on. */
public class OpenTabsStore {

    /**
     * One tab: kind SCRIPT (file, connection), TABLE (connection, catalog / schema / table / type) or COMPARE (file).
     * {@code selected}: the tab that was in front.
     */
    public record OpenTab(String kind, String file, String connectionId, String catalog, String schema, String table, String type,
                          boolean selected) {
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public OpenTabsStore() {
        this(Path.of(System.getProperty("user.home"), ".dbadmin", "open-tabs.json"));
    }

    OpenTabsStore(Path file) {
        this.file = file;
    }

    public List<OpenTab> load() {
        if (!Files.exists(file)) return List.of();
        try {
            return mapper.readValue(file.toFile(), new TypeReference<List<OpenTab>>() { });
        } catch (IOException e) {
            AppLog.get(OpenTabsStore.class).warning("Cannot read " + file + ": " + e.getMessage());
            return List.of();
        }
    }

    /** Writes the tabs; an empty list removes the file. */
    public void save(List<OpenTab> tabs) {
        try {
            if (tabs.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            JsonFiles.write(mapper, file, tabs, false);
        } catch (IOException e) {
            AppLog.get(OpenTabsStore.class).warning("Cannot write " + file + ": " + e.getMessage());
        }
    }
}
