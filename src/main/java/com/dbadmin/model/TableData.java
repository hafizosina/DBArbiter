package com.dbadmin.model;

import java.util.List;

/**
 * A generic grid of values: column headers plus rows. {@code types} describes each column
 * for query results; it is empty for metadata listings.
 */
public record TableData(List<String> columns, List<ColumnType> types, List<List<Object>> rows) {

    public TableData(List<String> columns, List<List<Object>> rows) {
        this(columns, List.of(), rows);
    }

    /** The type of column {@code i}, or null when unknown. */
    public ColumnType type(int i) {
        return i < types.size() ? types.get(i) : null;
    }
}
