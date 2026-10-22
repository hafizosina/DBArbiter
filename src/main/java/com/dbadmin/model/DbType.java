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

    // ---- SQL text: what differs between the databases, in one place (a fourth database fills these in) ----

    /** The quote around a name: "name", or `name` on MySQL. */
    public String identifierQuote() {
        return this == MYSQL ? "`" : "\"";
    }

    /** A name in quotes, the quote character doubled inside. */
    public String quote(String name) {
        String q = identifierQuote();
        return q + name.replace(q, q + q) + q;
    }

    /**
     * A name as written in SQL: bare when the database reads it back unchanged, else quoted. PostgreSQL folds bare
     * names to lower case, so a name with capitals needs quotes there; SQLite and MySQL keep the case.
     */
    public String quoteIfNeeded(String name) {
        String plain = switch (this) {
            case POSTGRESQL -> "[a-z_][a-z0-9_$]*";
            case MYSQL -> "[A-Za-z0-9_$]+";
            case SQLITE -> "[A-Za-z_][A-Za-z0-9_]*";
        };
        return name.matches(plain) ? name : quote(name);
    }

    /** A backslash in a string literal is an escape (MySQL), not a plain character. */
    public boolean backslashEscapes() {
        return this == MYSQL;
    }

    /** $tag$ … $tag$ quoting (PostgreSQL). */
    public boolean dollarQuotes() {
        return this == POSTGRESQL;
    }

    /** '#' starts a line comment (MySQL); elsewhere it is an operator (e.g. PostgreSQL's #>). */
    public boolean hashComments() {
        return this == MYSQL;
    }

    /** TRUE / FALSE, or 1 / 0 on SQLite (no boolean type). */
    public String booleanLiteral(boolean b) {
        return this == SQLITE ? (b ? "1" : "0") : (b ? "TRUE" : "FALSE");
    }

    /** Bytes as a literal from their hex digits: '\x…'::bytea on PostgreSQL, X'…' elsewhere. */
    public String binaryLiteral(String hex) {
        return this == POSTGRESQL ? "'\\x" + hex + "'::bytea" : "X'" + hex + "'";
    }

    /** A table is qualified by its database (catalog) on MySQL, by its schema elsewhere: db.table vs schema.table. */
    public boolean qualifiesByCatalog() {
        return this == MYSQL;
    }

    /** Has catalog schemas of its own (pg_catalog, information_schema, mysql, …) to hide in the navigator. */
    public boolean hasSystemSchemas() {
        return this != SQLITE;
    }

    /** Has materialized views (PostgreSQL). */
    public boolean hasMaterializedViews() {
        return this == POSTGRESQL;
    }

    /**
     * Streams a large result only inside a transaction (PostgreSQL: autocommit off, else every row is read at once).
     */
    public boolean streamsInTransactionOnly() {
        return this == POSTGRESQL;
    }

    /** The JDBC fetch size that streams rows: MySQL's driver streams row by row only with Integer.MIN_VALUE. */
    public int streamingFetchSize(int rows) {
        return this == MYSQL ? Integer.MIN_VALUE : rows;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
