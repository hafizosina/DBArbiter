package com.dbadmin.service.scan;

import com.dbadmin.model.TableRef;

import java.util.List;

/** One table's outcome: its findings (columns holding a value), or why it was not scanned ({@code notScanned}, else null). */
public record TableResult(TableRef table, List<ScanFinding> findings, String notScanned) {

    public boolean scanned() {
        return notScanned == null;
    }
}
