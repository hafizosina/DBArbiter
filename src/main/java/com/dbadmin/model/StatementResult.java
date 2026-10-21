package com.dbadmin.model;

import java.util.List;

/**
 * Outcome of one executed statement: either rows ({@code data} non-null) or an update count.
 * {@code truncated} means more rows existed than were fetched. {@code sql} and {@code params}
 * are what was sent, so a query can be re-run to fetch its next chunk (params may hold nulls).
 */
public record StatementResult(String sql, List<String> params, TableData data, int updateCount, long millis,
                              boolean truncated) {
}
