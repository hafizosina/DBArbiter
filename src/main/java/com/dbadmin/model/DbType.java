package com.dbadmin.model;

public enum DbType {
    POSTGRESQL("PostgreSQL", 5432),
    MYSQL("MySQL / MariaDB", 3306),
    SQLITE("SQLite", 0);

    private final String displayName;
    private final int defaultPort;

    DbType(String displayName, int defaultPort) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    /** File-based databases have no host, port or credentials. */
    public boolean isFileBased() {
        return this == SQLITE;
    }

    public String buildUrl(ConnectionConfig c) {
        return switch (this) {
            case POSTGRESQL -> "jdbc:postgresql://%s:%d/%s".formatted(c.getHost(), c.getPort(), c.getDatabase());
            case MYSQL -> "jdbc:mysql://%s:%d/%s".formatted(c.getHost(), c.getPort(), c.getDatabase());
            case SQLITE -> "jdbc:sqlite:" + c.getDatabase();
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
