package com.dbadmin.service.scan;

import com.dbadmin.model.CatalogColumn;
import com.dbadmin.model.DbType;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.Job;
import com.dbadmin.service.SchemaChange;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * Finds values in many tables and columns at once, letting the database do the reading: one query per table counts,
 * for each column and value, the rows that hold it (PostgreSQL {@code count(*) FILTER}, elsewhere
 * {@code SUM(CASE …)}), so no rows are held here. Values are matched as typed: {@code % _ !} in a value match only
 * themselves (LIKE … ESCAPE '!'), and values are sent as parameters, never written into the SQL.
 */
public final class DataScan {

    /** Aggregates per query; a wider table is counted in several queries (PostgreSQL allows 1,664 select items). */
    static final int MAX_AGGREGATES = 1000;

    private DataScan() {
    }

    /** The tables of the namespaces (not views: their rows are their tables' rows) with their columns and estimated size. */
    public static List<ScanTable> candidates(DatabaseSession session, List<Namespace> namespaces) throws SQLException {
        DbType type = session.getConfig().getType();
        List<ScanTable> result = new ArrayList<>();
        for (Namespace ns : namespaces) {
            Map<String, Long> estimates = session.estimatedRows(ns);
            Map<String, List<ScanColumn>> columns = new LinkedHashMap<>();
            for (CatalogColumn c : session.namespaceColumns(ns)) {
                columns.computeIfAbsent(c.table(), k -> new ArrayList<>()).add(ScanColumn.of(type, c.name(), c.typeName(), c.sqlType()));
            }
            for (TableRef t : session.listTables(ns)) {
                if (t.isView()) continue;
                result.add(new ScanTable(t, estimates.get(t.name()), columns.getOrDefault(t.name(), List.of())));
            }
        }
        return result;
    }

    /** A count query with its parameters, and the columns it counts (in order). */
    record CountQuery(String sql, List<String> params, List<ScanColumn> columns) {
    }

    /**
     * The queries that count one table's columns: per column, the rows holding any value, then per value the rows
     * holding it, then (ignoring case) per value the rows holding it in the exact case.
     */
    static List<CountQuery> countQueries(DbType type, TableRef table, List<ScanColumn> columns, List<String> values,
                                         boolean wholeValue, boolean ignoreCase) {
        int perColumn = 1 + values.size() * (ignoreCase ? 2 : 1);
        int chunk = Math.max(1, MAX_AGGREGATES / perColumn);
        List<CountQuery> queries = new ArrayList<>();
        for (int from = 0; from < columns.size(); from += chunk) {
            List<ScanColumn> part = columns.subList(from, Math.min(columns.size(), from + chunk));
            List<String> selects = new ArrayList<>();
            List<String> params = new ArrayList<>();
            List<String> anyColumn = new ArrayList<>();
            List<String> whereParams = new ArrayList<>();
            for (ScanColumn c : part) {
                String e = expression(type, c);
                List<String> anyParams = new ArrayList<>();
                List<String> any = new ArrayList<>();
                for (String v : values) {
                    any.add(predicate(type, e, wholeValue, ignoreCase));
                    anyParams.add(param(v, wholeValue, ignoreCase));
                }
                String anyOf = "(" + String.join(" OR ", any) + ")";
                selects.add(count(type, anyOf));
                params.addAll(anyParams);
                for (String v : values) {
                    selects.add(count(type, predicate(type, e, wholeValue, ignoreCase)));
                    params.add(param(v, wholeValue, ignoreCase));
                }
                if (ignoreCase) {
                    for (String v : values) {
                        selects.add(count(type, predicate(type, e, wholeValue, false)));
                        params.add(param(v, wholeValue, false));
                    }
                }
                anyColumn.add(anyOf);
                whereParams.addAll(anyParams);
            }
            params.addAll(whereParams);
            String sql = "SELECT " + String.join(", ", selects) + " FROM " + SchemaChange.table(type, table)
                    + " WHERE " + String.join(" OR ", anyColumn);
            queries.add(new CountQuery(sql, params, List.copyOf(part)));
        }
        return queries;
    }

    /** The column as text: json / jsonb / enum read as text. */
    private static String expression(DbType type, ScanColumn c) {
        String col = type.quote(c.name());
        return switch (type) {
            case POSTGRESQL -> "CAST(" + col + " AS text)";
            case MYSQL -> c.kind() == ScanColumn.Kind.JSON ? "CAST(" + col + " AS CHAR)" : col;
            case SQLITE -> "CAST(" + col + " AS TEXT)";
        };
    }

    /** The test of one value (one parameter): LIKE patterns for ignoring case, exact functions otherwise. */
    private static String predicate(DbType type, String e, boolean wholeValue, boolean ignoreCase) {
        return switch (type) {
            case POSTGRESQL -> ignoreCase
                    ? (wholeValue ? "lower(" + e + ") = lower(CAST(? AS text))" : e + " ILIKE CAST(? AS text) ESCAPE '!'")
                    : (wholeValue ? e + " = CAST(? AS text)" : "strpos(" + e + ", CAST(? AS text)) > 0");
            case MYSQL -> ignoreCase
                    ? (wholeValue ? "LOWER(" + e + ") = LOWER(?)" : "LOWER(" + e + ") LIKE LOWER(?) ESCAPE '!'")
                    : (wholeValue ? "CAST(" + e + " AS BINARY) = CAST(? AS BINARY)"
                    : "INSTR(CAST(" + e + " AS BINARY), CAST(? AS BINARY)) > 0");
            case SQLITE -> ignoreCase
                    ? (wholeValue ? "LOWER(" + e + ") = LOWER(?)" : "LOWER(" + e + ") LIKE LOWER(?) ESCAPE '!'")
                    : (wholeValue ? e + " = ?" : "instr(" + e + ", ?) > 0");
        };
    }

    /** The parameter for a value: a LIKE pattern (wildcards escaped with '!') when the predicate is a contains-LIKE. */
    private static String param(String value, boolean wholeValue, boolean ignoreCase) {
        if (wholeValue || !ignoreCase) return value;
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String count(DbType type, String predicate) {
        return type == DbType.POSTGRESQL ? "count(*) FILTER (WHERE " + predicate + ")"
                : "COALESCE(SUM(CASE WHEN " + predicate + " THEN 1 ELSE 0 END), 0)";
    }

    /**
     * Scans every target in order, on the job's session: reports each table's result to {@code each} as it is done
     * (on the job's thread), and returns them all. A table that fails or times out is reported as not scanned;
     * Cancel stops the scan.
     */
    public static List<TableResult> run(DatabaseSession session, ScanRequest request, Job<?> job, Consumer<TableResult> each) {
        DbType type = session.getConfig().getType();
        List<TableResult> results = new ArrayList<>();
        int n = request.targets().size();
        for (int i = 0; i < n; i++) {
            job.checkCancelled();
            ScanRequest.Target target = request.targets().get(i);
            job.progress("Table " + (i + 1) + " of " + n + " · " + target.table().displayName());
            TableResult result = scanTable(session, type, request, target, job);
            results.add(result);
            each.accept(result);
        }
        return results;
    }

    private static TableResult scanTable(DatabaseSession session, DbType type, ScanRequest request, ScanRequest.Target target, Job<?> job) {
        List<ScanColumn> columns = target.columns().stream().filter(ScanColumn::searchable).toList();
        if (columns.isEmpty() || request.values().isEmpty()) return new TableResult(target.table(), List.of(), null);
        List<ScanFinding> findings = new ArrayList<>();
        int values = request.values().size();
        try {
            for (CountQuery q : countQueries(type, target.table(), columns, request.values(), request.wholeValue(), request.ignoreCase())) {
                List<Long> counts = session.countRow(q.sql(), q.params(), request.timeoutSeconds());
                int at = 0;
                for (ScanColumn c : q.columns()) {
                    long rows = counts.get(at++);
                    List<Long> perValue = counts.subList(at, at + values);
                    at += values;
                    List<Long> exact = List.of();
                    if (request.ignoreCase()) {
                        exact = counts.subList(at, at + values);
                        at += values;
                    }
                    if (rows > 0) findings.add(new ScanFinding(target.table(), c, rows, List.copyOf(perValue), List.copyOf(exact)));
                }
            }
        } catch (SQLException e) {
            if (job.isCancelled()) throw new CancellationException();
            return new TableResult(target.table(), List.of(), timedOut(e)
                    ? "took longer than " + request.timeoutSeconds() + " s" : e.getMessage());
        }
        return new TableResult(target.table(), findings, null);
    }

    /** A statement timeout, as each driver reports it. */
    private static boolean timedOut(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        return e instanceof SQLTimeoutException || state.equals("57014") // PostgreSQL: canceled (by the timeout here)
                || e.getErrorCode() == 3024 || e.getErrorCode() == 1317; // MySQL: max execution time / interrupted
    }

    // ---- the rows behind a finding ----

    /**
     * The first rows of a column that hold a value: their primary key ({@code keyColumns}, empty if the table has
     * none), the column's value as text, and whether more rows match ({@code more}).
     */
    public record MatchRows(List<String> keyColumns, List<List<Object>> keys, List<String> values, boolean more) {

        /** Every value parses as a JSON object or array: the column holds JSON (e.g. a template stored as text). */
        public boolean holdsJson() {
            return values.stream().anyMatch(java.util.Objects::nonNull) && values.stream().allMatch(DataScan::isJson);
        }
    }

    /** The first {@code limit} rows of {@code column} that hold one of the request's values, by primary key. */
    public static MatchRows matchingRows(DatabaseSession session, ScanRequest request, TableRef table, ScanColumn column, int limit)
            throws SQLException {
        DbType type = session.getConfig().getType();
        List<String> key = session.primaryKey(table);
        String e = expression(type, column);
        List<String> any = new ArrayList<>();
        List<String> params = new ArrayList<>();
        for (String v : request.values()) {
            any.add(predicate(type, e, request.wholeValue(), request.ignoreCase()));
            params.add(param(v, request.wholeValue(), request.ignoreCase()));
        }
        List<String> select = new ArrayList<>();
        key.forEach(k -> select.add(type.quote(k)));
        select.add(e);
        String sql = "SELECT " + String.join(", ", select) + " FROM " + SchemaChange.table(type, table)
                + " WHERE " + String.join(" OR ", any)
                + (key.isEmpty() ? "" : " ORDER BY " + String.join(", ", key.stream().map(type::quote).toList()))
                + " LIMIT " + (limit + 1);
        List<List<Object>> rows = session.execute(sql, params, limit + 1).data().rows();
        List<List<Object>> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (List<Object> row : rows.subList(0, Math.min(limit, rows.size()))) {
            keys.add(new ArrayList<>(row.subList(0, key.size())));
            Object v = row.get(key.size());
            values.add(v == null ? null : v.toString());
        }
        return new MatchRows(key, keys, values, rows.size() > limit);
    }

    /**
     * Where the values are in {@code text}, as [start, end) ranges in order, overlaps merged; matched as the scan
     * matched them (whole value: the whole text or nothing).
     */
    public static List<int[]> matches(String text, List<String> values, boolean wholeValue, boolean ignoreCase) {
        List<int[]> ranges = new ArrayList<>();
        if (text == null) return ranges;
        for (String v : values) {
            if (v.isEmpty()) continue;
            if (wholeValue) {
                if (ignoreCase ? text.equalsIgnoreCase(v) : text.equals(v)) ranges.add(new int[]{0, text.length()});
                continue;
            }
            for (int at = 0; at <= text.length() - v.length(); at++) {
                if (text.regionMatches(ignoreCase, at, v, 0, v.length())) {
                    ranges.add(new int[]{at, at + v.length()});
                    at += v.length() - 1;
                }
            }
        }
        ranges.sort(java.util.Comparator.comparingInt(r -> r[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : ranges) {
            if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1]) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], r[1]);
            } else {
                merged.add(r);
            }
        }
        return merged;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** A JSON object or array (not a bare string or number: plain text would count). */
    public static boolean isJson(String value) {
        if (value == null) return true;
        String t = value.strip();
        if (!(t.startsWith("{") || t.startsWith("["))) return false;
        try {
            JSON.readTree(t);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
