package com.dbadmin.service;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The app's log: ~/.dbadmin/logs/dbadmin.0.log, rotated at 1 MB (dbadmin.1.log, .2 are older). Errors
 * go there so problems can be looked into afterwards. The SQL that is run and passwords are never
 * logged, only what went wrong (a database's error message may still quote part of a statement);
 * the folder is readable by its owner only.
 */
public final class AppLog {

    /** Every logger of the app is below this one. */
    private static final Logger ROOT = Logger.getLogger("com.dbadmin");
    private static final int LIMIT_BYTES = 1024 * 1024;
    private static final int FILES = 3;

    private AppLog() {
    }

    /** Starts logging to the default folder; if that fails the app runs without a log file. */
    public static void init() {
        try {
            init(Path.of(System.getProperty("user.home"), ".dbadmin", "logs"));
        } catch (IOException e) {
            System.err.println("DBAdmin: no log file: " + e.getMessage());
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
                ROOT.log(Level.SEVERE, "Uncaught exception on thread " + thread.getName(), e));
    }

    /** Logs to {@code dir}; returns the handler, so it can be removed again (tests). */
    static Handler init(Path dir) throws IOException {
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX file system (Windows)
        }
        // %g: the generation, 0 is the current file ('%' in the folder itself has to be doubled)
        String pattern = dir.toAbsolutePath().toString().replace("%", "%%") + "/dbadmin.%g.log";
        FileHandler handler = new FileHandler(pattern, LIMIT_BYTES, FILES, true);
        handler.setFormatter(new LineFormatter());
        handler.setLevel(Level.INFO);
        ROOT.addHandler(handler);
        ROOT.setLevel(Level.INFO);
        return handler;
    }

    public static Logger get(Class<?> type) {
        return Logger.getLogger(type.getName());
    }

    /** "2026-10-21 14:03:07 WARNING DatabaseSession: message", then the stack trace if there is one. */
    private static final class LineFormatter extends Formatter {
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @Override
        public String format(LogRecord r) {
            String source = r.getLoggerName() == null ? "" : r.getLoggerName().substring(r.getLoggerName().lastIndexOf('.') + 1);
            StringBuilder sb = new StringBuilder()
                    .append(LocalDateTime.now().format(TIME)).append(' ')
                    .append(r.getLevel().getName()).append(' ')
                    .append(source).append(": ")
                    .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter trace = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(trace));
                sb.append(trace);
            }
            return sb.toString();
        }
    }
}
