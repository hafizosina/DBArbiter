package com.dbadmin.service.scan;

import com.dbadmin.service.AppLog;
import com.dbadmin.service.JsonFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Replace dialog's "Also set" columns, per connection (a project usually names its audit columns one way), in
 * ~/.dbadmin/also-set.json. An unreadable file means none saved.
 */
public class AlsoSetStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public AlsoSetStore() {
        this(Path.of(System.getProperty("user.home"), ".dbadmin", "also-set.json"));
    }

    public AlsoSetStore(Path file) {
        this.file = file;
    }

    private Map<String, List<ReplaceSql.AlsoSet>> all() {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            return mapper.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, List<ReplaceSql.AlsoSet>>>() {
            });
        } catch (IOException e) {
            AppLog.get(AlsoSetStore.class).warning("Cannot read " + file + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public List<ReplaceSql.AlsoSet> load(String connectionId) {
        return all().getOrDefault(connectionId, List.of());
    }

    /** Keeps the list for the connection; an error is logged, not thrown (losing it costs only retyping). */
    public void save(String connectionId, List<ReplaceSql.AlsoSet> alsoSet) {
        Map<String, List<ReplaceSql.AlsoSet>> all = all();
        all.put(connectionId, List.copyOf(alsoSet));
        try {
            JsonFiles.write(mapper, file, all, false);
        } catch (IOException e) {
            AppLog.get(AlsoSetStore.class).warning("Cannot write " + file + ": " + e.getMessage());
        }
    }
}
