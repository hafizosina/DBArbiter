package com.dbadmin.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Where scripts are saved by default, and how new scripts are named. */
public final class ScriptFolder {

    private ScriptFolder() {
    }

    /**
     * "Script/" next to the running jar, created if missing. When running from
     * target/classes (mvn javafx:run) it is placed in the working directory instead,
     * so "mvn clean" does not delete saved scripts.
     */
    public static Path defaultDir() {
        Path dir = appDir().resolve("Script");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + dir, e);
        }
        return dir;
    }

    /** "Reports" next to the app (like Script): where compare reports are saved by default. */
    public static Path reportDir() {
        Path dir = appDir().resolve("Reports");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + dir, e);
        }
        return dir;
    }

    private static Path appDir() {
        try {
            Path location = Path.of(ScriptFolder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) return location.getParent();
        } catch (URISyntaxException | SecurityException | NullPointerException ignored) {
            // fall back to the working directory
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    /**
     * The next free name "Script-&lt;connection&gt;-&lt;n&gt;" (without extension), numbering per connection.
     * Names of .sql files in {@code dir} and the {@code taken} names (open, unsaved scripts) are skipped.
     */
    public static String nextName(Path dir, String connectionName, Collection<String> taken) {
        String prefix = connectionName == null ? "Script-" : "Script-" + safe(connectionName) + "-";
        Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "(\\d+)(\\.sql)?");
        int max = 0;
        for (String name : taken) max = Math.max(max, number(pattern, name));
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) max = Math.max(max, number(pattern, f.getFileName().toString()));
            } catch (IOException ignored) {
                // numbering then only considers open scripts
            }
        }
        return prefix + (max + 1);
    }

    private static int number(Pattern pattern, String name) {
        Matcher m = pattern.matcher(name);
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** A connection name made safe for file names: "Antam Prod" -> "Antam_Prod". */
    public static String safe(String name) {
        return name.trim().replaceAll("\\s+", "_").replaceAll("[\\\\/:*?\"<>|]", "");
    }
}
