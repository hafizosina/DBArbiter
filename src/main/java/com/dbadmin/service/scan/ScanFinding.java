package com.dbadmin.service.scan;

import com.dbadmin.model.TableRef;

import java.util.List;

/**
 * One column that holds at least one value: {@code rows} that hold any of them; per value (in the request's order)
 * the rows that hold it; and when the scan ignored case, the rows that hold it in exactly the case typed
 * ({@code exactRows}, else empty), which is what a replace changes.
 */
public record ScanFinding(TableRef table, ScanColumn column, long rows, List<Long> valueRows, List<Long> exactRows) {
}
