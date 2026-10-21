package com.dbadmin.model;

/**
 * Outcome of one executed statement: either rows ({@code data} non-null) or an update count.
 * {@code truncated} means more rows existed than were fetched.
 */
public record StatementResult(String sql, TableData data, int updateCount, long millis, boolean truncated) {
}
