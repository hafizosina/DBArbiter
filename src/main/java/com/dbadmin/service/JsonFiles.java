package com.dbadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Writes the app's JSON files so that a crash or full disk never leaves a half-written file behind. */
public final class JsonFiles {

    private JsonFiles() {
    }

    /**
     * Writes {@code value} to a temporary file next to {@code file}, then moves it over {@code file}
     * in one step: readers see the old content or the new, never a mix. With {@code ownerOnly} the
     * file is readable by the owner only (on POSIX systems) from the moment it is created.
     */
    public static void write(ObjectMapper mapper, Path file, Object value, boolean ownerOnly) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = createTemp(dir, file.getFileName().toString(), ownerOnly);
        try {
            mapper.writeValue(temp.toFile(), value);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp); // only still there if writing or moving failed
        }
    }

    private static Path createTemp(Path dir, String name, boolean ownerOnly) throws IOException {
        if (ownerOnly) {
            try {
                return Files.createTempFile(dir, name, ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system (Windows)
            }
        }
        return Files.createTempFile(dir, name, ".tmp");
    }
}
