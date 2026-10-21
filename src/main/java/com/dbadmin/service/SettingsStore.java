package com.dbadmin.service;

import com.dbadmin.model.AppSettings;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads and writes ~/.dbadmin/settings.json; an unreadable file means default settings. */
public class SettingsStore {

    private final Path file = Path.of(System.getProperty("user.home"), ".dbadmin", "settings.json");
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public AppSettings load() {
        if (!Files.exists(file)) return new AppSettings();
        try {
            return mapper.readValue(file.toFile(), AppSettings.class);
        } catch (IOException e) {
            AppLog.get(SettingsStore.class).warning("Cannot read " + file + ", using default settings: " + e.getMessage());
            return new AppSettings();
        }
    }

    public void save(AppSettings settings) {
        try {
            JsonFiles.write(mapper, file, settings, false);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
