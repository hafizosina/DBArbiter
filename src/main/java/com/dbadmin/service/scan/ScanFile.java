package com.dbadmin.service.scan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A saved scan (.dbscan, JSON): the form as it was (connection, values, options, the ticked schemas, tables and
 * columns) and what the scan found, so it opens again without scanning.
 */
public class ScanFile {

    public static final String EXTENSION = ".dbscan";
    public static final String KIND = "scan";
    /** The file format; a file from a newer one is refused rather than misread. */
    public static final int VERSION = 1;

    public String kind = KIND;
    public int version = VERSION;
    public String created;
    public String appVersion;
    /** The connection by id, and its name for when the id is not known here. */
    public String connectionId;
    public String connectionName;
    public String values = "";
    public boolean wholeValue;
    public boolean ignoreCase = true;
    public String timeoutSeconds = "60";
    public String bigTableRows = "1000000";
    /** Ticked schemas by label, tables by display name, columns as "table|column". */
    public List<String> schemas = List.of();
    public List<String> tables = List.of();
    public List<String> columns = List.of();
    /** The scan that ran (null when only the form was saved) and what each table gave. */
    public ScanRequest request;
    public List<TableResult> results = new ArrayList<>();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Writes the file; never leaves a half-written one. */
    public static void save(Path file, ScanFile scan) throws IOException {
        com.dbadmin.service.JsonFiles.write(MAPPER, file, scan, false);
    }

    public static ScanFile open(Path file) throws IOException {
        JsonNode json = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        if (!KIND.equals(json.path("kind").asText(""))) throw new IOException(file.getFileName() + " is not a DBArbiter scan.");
        int version = json.path("version").asInt(1);
        if (version > VERSION) {
            throw new IOException(file.getFileName() + " was saved by a newer version of DBArbiter (file format " + version
                    + "; this version reads up to " + VERSION + "). Update the app to open it.");
        }
        return MAPPER.treeToValue(json, ScanFile.class);
    }

    /** For tests: the JSON of a scan. */
    static String json(ScanFile scan) throws IOException {
        return MAPPER.writeValueAsString(scan);
    }

}
