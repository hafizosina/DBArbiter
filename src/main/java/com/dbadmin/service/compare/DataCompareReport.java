package com.dbadmin.service.compare;

import com.dbadmin.model.BlobValue;
import com.dbadmin.service.compare.DataCompare.RowStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A data compare as it is saved, reopened and exported: where it ran, its settings and counts, and
 * every listed row with the user's verdict. Values are kept as text (null for SQL NULL), the form
 * they are shown in and later written into generated SQL. Rows that may get an INSERT or UPDATE keep
 * the whole source row, so SQL can be generated from a reopened report without running it again.
 */
public final class DataCompareReport {

    public static final String KIND = "data";

    /** One side of the compare: the connection's name (as in this app), schema and table. */
    public record Side(String connection, String schema, String table, String label) {
    }

    /** A listed row. {@code source} / {@code target}: the shown columns' values, null when the row is not on that side. */
    public static final class Row {
        public RowStatus status;
        public String key;
        public String note;
        public List<String> source;
        public List<String> target;
        /** All source columns ({@link #sourceColumns}); only for rows that are in the source and differ. */
        public List<String> sourceRow;
        public Verdict verdict;

        public Row() {
        }
    }

    public String kind = KIND;
    public int version = 1;
    public String created;
    public String appVersion;
    public Side source;
    public Side target;
    public List<String> keyColumns = List.of();
    public List<String> comparedColumns = List.of();
    /** Key, compared and show-only columns: what {@link Row#source} and {@link Row#target} hold. */
    public List<String> shownColumns = List.of();
    public List<String> sourceColumns = List.of();
    public String where = "";
    public int rowsPerSide;
    /** A side had more rows than {@link #rowsPerSide}: only the first ones were compared. */
    public boolean truncated;
    public int sourceRows;
    public int targetRows;
    public int same;
    public int changed;
    public int onlySource;
    public int onlyTarget;
    public int duplicateKeys;
    public List<Row> rows = new ArrayList<>();

    public DataCompareReport() {
    }

    public static DataCompareReport of(DataCompare.Result r, Side source, Side target, List<String> keys,
                                       String where, int rowsPerSide, boolean truncated, String appVersion) {
        DataCompareReport report = new DataCompareReport();
        report.created = LocalDateTime.now().withNano(0).toString();
        report.appVersion = appVersion;
        report.source = source;
        report.target = target;
        report.keyColumns = List.copyOf(keys);
        report.comparedColumns = List.copyOf(r.comparedColumns());
        report.shownColumns = List.copyOf(r.shownColumns());
        report.sourceColumns = List.copyOf(r.sourceColumns());
        report.where = where;
        report.rowsPerSide = rowsPerSide;
        report.truncated = truncated;
        report.sourceRows = r.sourceRows();
        report.targetRows = r.targetRows();
        report.same = r.same();
        report.changed = r.changed();
        report.onlySource = r.onlySource();
        report.onlyTarget = r.onlyTarget();
        report.duplicateKeys = r.duplicateKeys();
        for (DataCompare.RowDiff d : r.rows()) {
            Row row = new Row();
            row.status = d.status();
            row.key = d.key();
            row.note = d.details();
            row.source = texts(d.sourceValues());
            row.target = texts(d.targetValues());
            if (d.status() == RowStatus.ONLY_SOURCE || d.status() == RowStatus.CHANGED) row.sourceRow = texts(d.sourceRow());
            report.rows.add(row);
        }
        return report;
    }

    /** Rows without a verdict that could have one. */
    public long undecided() {
        return rows.stream().filter(r -> r.verdict == null && !Verdict.allowed(r.status).isEmpty()).count();
    }

    public long count(Verdict v) {
        return rows.stream().filter(r -> r.verdict == v).count();
    }

    private static List<String> texts(List<Object> values) {
        if (values == null) return null;
        List<String> result = new ArrayList<>(values.size());
        for (Object v : values) result.add(text(v));
        return result;
    }

    /** A value as saved: numbers without trailing zeros (1.50 and 1.5 are the same value), the rest as shown. */
    static String text(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
        if (v instanceof Double || v instanceof Float) return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
        if (v instanceof BlobValue b) return b.toString();
        return v.toString();
    }
}
