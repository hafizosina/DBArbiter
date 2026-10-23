package com.dbadmin.service.scan;

import com.dbadmin.model.TableRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * What to find and where. {@code values} as typed, one per line (blank lines and repeats are dropped; nothing is
 * trimmed, a space can be part of a value); {@code wholeValue}: the cell is the value, else it contains it;
 * {@code timeoutSeconds}: a table taking longer is left out and reported (0: no limit).
 */
public record ScanRequest(List<String> values, boolean wholeValue, boolean ignoreCase, List<Target> targets, int timeoutSeconds) {

    /** One table and the columns of it to search. */
    public record Target(TableRef table, List<ScanColumn> columns) {
    }

    public ScanRequest {
        values = List.copyOf(new LinkedHashSet<>(values.stream().filter(v -> !v.isBlank()).toList()));
        targets = List.copyOf(targets);
    }

    /** The values from a text box: one per line. */
    public static List<String> lines(String text) {
        return new ArrayList<>(text.lines().toList());
    }
}
