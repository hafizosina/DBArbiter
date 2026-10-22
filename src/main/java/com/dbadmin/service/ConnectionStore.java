package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves connections to ~/.dbadmin/connections.json.
 * A saved password is stored in plain text, so the file is made readable by the owner only (POSIX); a connection with
 * "Save password" off is written without its password.
 */
public class ConnectionStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public ConnectionStore() {
        this(Path.of(System.getProperty("user.home"), ".dbadmin", "connections.json"));
    }

    ConnectionStore(Path file) {
        this.file = file;
    }

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
            com.fasterxml.jackson.databind.node.ArrayNode out = mapper.createArrayNode();
            for (ConnectionConfig c : connections) {
                com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(c);
                if (!c.isSavePassword()) json.remove("password"); // asked when connecting instead
                out.add(json);
            }
            JsonFiles.write(mapper, file, out, true);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
