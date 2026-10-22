package com.dbadmin.model;

import java.util.UUID;

/** A saved connection. For SQLite, {@code database} holds the file path. */
public class ConnectionConfig {
    private String id = UUID.randomUUID().toString();
    private String name;
    private DbType type = DbType.POSTGRESQL;
    private String host = "localhost";
    private int port = DbType.POSTGRESQL.getDefaultPort();
    private String database;
    private String username;
    private String password;
    /** Write the password to connections.json; off: it is asked when connecting and kept in memory only. Missing in older saved files means on. */
    private boolean savePassword = true;
    /** Blocks all changes to the database. On by default; missing in older saved files means on. */
    private boolean readOnly = true;
    /** When not read-only, what to ask before running (see QueryRisk): 0 nothing, 1 dangerous, 2 any change, 3 inserts too. Missing in older saved files means 1. */
    private int warningLevel = 1;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public DbType getType() { return type; }
    public void setType(DbType type) { this.type = type; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isSavePassword() { return savePassword; }
    public void setSavePassword(boolean savePassword) { this.savePassword = savePassword; }

    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public int getWarningLevel() { return warningLevel; }
    public void setWarningLevel(int warningLevel) { this.warningLevel = Math.max(0, Math.min(3, warningLevel)); }

    public String jdbcUrl() {
        return type.buildUrl(this);
    }

    @Override
    public String toString() {
        return name;
    }
}
