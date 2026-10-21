package com.dbadmin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppLogTest {

    @TempDir
    Path dir;

    @Test
    void writesOneLinePerRecordWithTheStackTrace() throws IOException {
        Path logs = dir.resolve("logs");
        Handler handler = AppLog.init(logs);
        try {
            AppLog.get(AppLogTest.class).warning("something failed");
            AppLog.get(AppLogTest.class).log(Level.SEVERE, "crashed", new IllegalStateException("boom"));
            Logger.getLogger("org.other").warning("not ours"); // only the app's own loggers go to the file
            handler.flush();
        } finally {
            Logger.getLogger("com.dbadmin").removeHandler(handler);
            handler.close();
        }
        String log = Files.readString(logs.resolve("dbadmin.0.log"));
        assertTrue(log.matches("(?s)\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d WARNING AppLogTest: something failed\\R.*"), log);
        assertTrue(log.contains("SEVERE AppLogTest: crashed"), log);
        assertTrue(log.contains("java.lang.IllegalStateException: boom"), log);
        assertFalse(log.contains("not ours"), log);
    }

    @Test
    void theFolderIsOwnerOnly() throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        Path logs = dir.resolve("logs");
        Handler handler = AppLog.init(logs);
        Logger.getLogger("com.dbadmin").removeHandler(handler);
        handler.close();
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(logs)));
    }
}
