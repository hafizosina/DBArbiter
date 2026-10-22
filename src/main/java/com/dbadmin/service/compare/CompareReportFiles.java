package com.dbadmin.service.compare;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saves and opens compare reports (.dbcompare: JSON, a data or a table compare). */
public final class CompareReportFiles {

    public static final String EXTENSION = ".dbcompare";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private CompareReportFiles() {
    }

    /** Writes a {@link DataCompareReport} or {@link SchemaCompareReport}; never leaves a half-written file. */
    public static void save(Path file, Object report) throws IOException {
        com.dbadmin.service.JsonFiles.write(MAPPER, file, report, false);
    }

    /** Reads a report: a {@link DataCompareReport} or a {@link SchemaCompareReport}. */
    public static Object open(Path file) throws IOException {
        JsonNode json = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        String kind = json.path("kind").asText("");
        return switch (kind) {
            case DataCompareReport.KIND -> MAPPER.treeToValue(json, DataCompareReport.class);
            case SchemaCompareReport.KIND -> MAPPER.treeToValue(json, SchemaCompareReport.class);
            default -> throw new IOException(file.getFileName() + " is not a DBArbiter compare report.");
        };
    }
}
