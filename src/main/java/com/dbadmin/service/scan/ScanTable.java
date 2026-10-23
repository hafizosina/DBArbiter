package com.dbadmin.service.scan;

import com.dbadmin.model.TableRef;

import java.util.List;

/** A table that can be scanned: its columns, and its row count as the catalog estimates it (null: unknown). */
public record ScanTable(TableRef table, Long estimatedRows, List<ScanColumn> columns) {

    public List<ScanColumn> searchableColumns() {
        return columns.stream().filter(ScanColumn::searchable).toList();
    }

    /** Bigger than {@code limit} rows by the estimate; unknown is not big. */
    public boolean isBig(long limit) {
        return estimatedRows != null && estimatedRows > limit;
    }
}
