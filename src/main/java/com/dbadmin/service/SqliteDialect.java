package com.dbadmin.service;

import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQLite: one namespace ("main"); everything else is read from the stored CREATE text in sqlite_master. */
final class SqliteDialect implements Dialect {

    private static final Pattern CHECK_START = Pattern.compile(
            "(?:\\bCONSTRAINT\\s+(\"[^\"]+\"|`[^`]+`|\\[[^]]+]|\\w+)\\s+)?\\bCHECK\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIGGER_TIMING = Pattern.compile("\\b(BEFORE|AFTER|INSTEAD\\s+OF)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIGGER_EVENT = Pattern.compile("\\b(INSERT|UPDATE|DELETE)\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public List<Namespace> namespaces(Metadata m) {
        return List.of(new Namespace(null, null, "main"));
    }

    @Override
    public String defaultNamespace(Metadata m) {
        return "main";
    }

    @Override
    public String viewDefinition(Metadata m, TableRef t) throws SQLException {
        return Metadata.first(m.query("select sql from sqlite_master where type = 'view' and name = ?", t.name()));
    }

    /** The stored SQL: the table or view first, then its indexes and triggers. */
    @Override
    public String ddl(Metadata m, TableRef t) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (List<Object> r : m.query("select sql from sqlite_master where tbl_name = ? and sql is not null "
                + "order by case type when 'table' then 0 when 'view' then 0 when 'index' then 1 else 2 end, name", t.name())) {
            sb.append(r.get(0)).append(";\n\n");
        }
        return sb.toString().strip();
    }

    /** What JDBC tells (primary key, unique indexes), plus the check constraints from the CREATE TABLE text. */
    @Override
    public List<List<Object>> constraints(Metadata m, TableRef t) throws SQLException {
        List<List<Object>> rows = m.genericConstraints(t);
        rows.addAll(checks(m, t));
        return rows;
    }

    /** SQLite has no catalog of check constraints, so they are read from the table's CREATE TABLE text. */
    private static List<List<Object>> checks(Metadata m, TableRef t) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> r : m.query("select sql from sqlite_master where type = 'table' and name = ?", t.name())) {
            String sql = String.valueOf(r.get(0));
            Matcher match = CHECK_START.matcher(sql);
            int from = 0;
            while (match.find(from)) {
                int open = match.end() - 1;
                int close = matchingParen(sql, open);
                String name = match.group(1) == null ? "CHECK" : match.group(1).replaceAll("^[\"`\\[]|[\"`\\]]$", "");
                rows.add(List.of(name, "CHECK", "", "CHECK " + sql.substring(open, close + 1)));
                from = close + 1;
            }
        }
        return rows;
    }

    /** Index of the ')' matching the '(' at {@code open}, skipping quoted text; the last index if unbalanced. */
    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = SqlLexer.closingQuote(s, i, false) - 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return s.length() - 1;
    }

    /** From the stored CREATE TRIGGER: timing and event come before "ON <table>". */
    @Override
    public List<List<Object>> triggers(Metadata m, TableRef t) throws SQLException {
        List<List<Object>> result = new ArrayList<>();
        for (List<Object> r : m.query("select name, sql from sqlite_master where type = 'trigger' and tbl_name = ? order by name", t.name())) {
            String sql = String.valueOf(r.get(1));
            int on = sql.toUpperCase().indexOf(" ON ");
            String head = on > 0 ? sql.substring(0, on) : sql;
            Matcher timing = TRIGGER_TIMING.matcher(head);
            Matcher event = TRIGGER_EVENT.matcher(head);
            result.add(List.of(r.get(0), timing.find() ? timing.group(1).toUpperCase() : "BEFORE",
                    event.find() ? event.group(1).toUpperCase() : "", "ROW", sql));
        }
        return result;
    }

    /** From pragma_table_xinfo: the declared type, NOT NULL, the default as written; hidden columns are generated ones. */
    @Override
    public List<ColumnSpec> columns(Metadata m, TableRef t) throws SQLException {
        List<ColumnSpec> result = new ArrayList<>();
        for (List<Object> r : m.query("select name, type, \"notnull\", coalesce(dflt_value, ''), hidden from pragma_table_xinfo(?) order by cid",
                t.name())) {
            String def = String.valueOf(r.get(3));
            result.add(new ColumnSpec(String.valueOf(r.get(0)), String.valueOf(r.get(1)), !"1".equals(String.valueOf(r.get(2))),
                    def.isEmpty() ? null : def, "", "", "", !"0".equals(String.valueOf(r.get(4)))));
        }
        return result;
    }
}
