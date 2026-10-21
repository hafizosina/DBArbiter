package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves connections to ~/.dbadmin/connections.json.
 * Passwords are stored in plain text, so the file is made readable by the owner only.
 */
public class ConnectionStore {

    private final Path file = Path.of(System.getProperty("user.home"), ".dbadmin", "connections.json");
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public List<ConnectionConfig> load() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(file.toFile(), new TypeReference<ArrayList<ConnectionConfig>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public void save(List<ConnectionConfig> connections) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), connections);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system (Windows)
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
