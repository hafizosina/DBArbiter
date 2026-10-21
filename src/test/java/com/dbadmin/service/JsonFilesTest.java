package com.dbadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JsonFilesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path dir;

    /** Serializing this fails halfway through, like a crash while writing. */
    public static class Broken {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void writesAndReplaces() throws IOException {
        Path file = dir.resolve("sub").resolve("settings.json");
        JsonFiles.write(mapper, file, Map.of("a", 1), false);
        JsonFiles.write(mapper, file, Map.of("a", 2), false);
        assertEquals("{\"a\":2}", Files.readString(file));
        assertEquals(List.of(file), listing(file.getParent())); // no temporary files left
    }

    @Test
    void aFailedWriteKeepsTheOldFile() throws IOException {
        Path file = dir.resolve("connections.json");
        JsonFiles.write(mapper, file, List.of("old"), false);
        assertThrows(IOException.class, () -> JsonFiles.write(mapper, file, List.of(new Broken()), false));
        assertEquals("[\"old\"]", Files.readString(file));
        assertEquals(List.of(file), listing(dir));
    }

    @Test
    void ownerOnlyFiles() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path file = dir.resolve("connections.json");
        JsonFiles.write(mapper, file, List.of(), true);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    private static List<Path> listing(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.toList();
        }
    }
}
