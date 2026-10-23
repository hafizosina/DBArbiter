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
}
