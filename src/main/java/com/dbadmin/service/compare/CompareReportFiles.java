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

    /**
     * Reads a report: a {@link DataCompareReport} or a {@link SchemaCompareReport}. A file from a newer version of
     * the app (a higher format version) is refused rather than misread; an older one would be upgraded here, field
     * by field, before it is read (none yet: version 1 is the first). A file without a version is version 1.
     */
    public static Object open(Path file) throws IOException {
        JsonNode json = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        String kind = json.path("kind").asText("");
        int version = json.path("version").asInt(1);
        int current = switch (kind) {
            case DataCompareReport.KIND -> DataCompareReport.VERSION;
            case SchemaCompareReport.KIND -> SchemaCompareReport.VERSION;
            default -> throw new IOException(file.getFileName() + " is not a DBArbiter compare report.");
        };
        if (version > current) {
            throw new IOException(file.getFileName() + " was saved by a newer version of DBArbiter (file format " + version
                    + "; this version reads up to " + current + "). Update the app to open it.");
        }
        return kind.equals(DataCompareReport.KIND) ? MAPPER.treeToValue(json, DataCompareReport.class)
                : MAPPER.treeToValue(json, SchemaCompareReport.class);
    }
}
