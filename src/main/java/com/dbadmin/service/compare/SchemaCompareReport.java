package com.dbadmin.service.compare;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** A table compare as it is saved, reopened and exported: both sides and every table's differences. */
public final class SchemaCompareReport {

    public static final String KIND = "table";

    public String kind = KIND;
    public int version = 1;
    /** Only the settings (no tables): opening it compares again. */
    public boolean settingsOnly;
    public String created;
    public String appVersion;
    /** E.g. "UAT" or "UAT/brankas_mobile". */
    public String sourceLabel;
    public String targetLabel;
    /** The form's settings, to compare again after reopening (null in reports saved before they were kept). */
    public String sourceConnection;
    public String sourceSchema;
    public String targetConnection;
    public String targetSchema;
    /** The tables picked to compare (lower case); empty = all. */
    public List<String> pickedTables = List.of();
    public List<SchemaCompare.TableDiff> tables = new ArrayList<>();

    public SchemaCompareReport() {
    }

    public static SchemaCompareReport of(List<SchemaCompare.TableDiff> tables, String sourceLabel, String targetLabel,
                                         String appVersion) {
        SchemaCompareReport report = new SchemaCompareReport();
        report.created = LocalDateTime.now().withNano(0).toString();
        report.appVersion = appVersion;
        report.sourceLabel = sourceLabel;
        report.targetLabel = targetLabel;
        report.tables = new ArrayList<>(tables);
        return report;
    }

    public long count(SchemaCompare.Status status) {
        return tables.stream().filter(t -> t.status() == status).count();
    }
}
