package com.dbadmin.service;

import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** MySQL / MariaDB: databases are the namespaces (catalogs); information_schema and SHOW CREATE. */
final class MySqlDialect implements Dialect {

    @Override
    public List<Namespace> namespaces(Metadata m) throws SQLException {
        List<Namespace> result = new ArrayList<>();
        try (ResultSet rs = m.connection().getMetaData().getCatalogs()) {
            while (rs.next()) {
                String db = rs.getString("TABLE_CAT");
                result.add(new Namespace(db, null, db));
            }
        }
        return result;
    }

    @Override
    public String defaultNamespace(Metadata m) throws SQLException {
        return m.connection().getCatalog();
    }

    @Override
    public String viewDefinition(Metadata m, TableRef t) throws SQLException {
        return Metadata.first(m.query("select VIEW_DEFINITION from information_schema.VIEWS where TABLE_SCHEMA = ? and TABLE_NAME = ?",
                t.catalog(), t.name()));
    }

    @Override
    public String ddl(Metadata m, TableRef t) throws SQLException {
        String what = t.isView() ? "VIEW" : "TABLE";
        List<List<Object>> rows = m.query("SHOW CREATE " + what + " " + m.qualifiedName(t));
        return rows.isEmpty() ? "" : rows.get(0).get(1) + ";";
    }

    /** From information_schema; a definition MySQL leaves empty (keys) is written as "TYPE (columns)". */
    @Override
    public List<List<Object>> constraints(Metadata m, TableRef t) throws SQLException {
        return withDefinitions(m.query("""
                select tc.CONSTRAINT_NAME, tc.CONSTRAINT_TYPE,
                       coalesce((select group_concat(k.COLUMN_NAME order by k.ORDINAL_POSITION separator ', ')
                                 from information_schema.KEY_COLUMN_USAGE k
                                 where k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA and k.TABLE_NAME = tc.TABLE_NAME
                                   and k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME), ''),
                       coalesce((select cc.CHECK_CLAUSE from information_schema.CHECK_CONSTRAINTS cc
                                 where cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                                   and cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME), '')
                from information_schema.TABLE_CONSTRAINTS tc
                where tc.TABLE_SCHEMA = ? and tc.TABLE_NAME = ? and tc.CONSTRAINT_TYPE <> 'FOREIGN KEY'
                order by tc.CONSTRAINT_TYPE = 'PRIMARY KEY' desc, tc.CONSTRAINT_NAME
                """, t.catalog(), t.name()));
    }

    /** Fills an empty Definition with "TYPE (columns)", e.g. MySQL primary keys. */
    private static List<List<Object>> withDefinitions(List<List<Object>> rows) {
        List<List<Object>> result = new ArrayList<>();
        for (List<Object> r : rows) {
            String definition = String.valueOf(r.get(3));
            if (definition.isEmpty()) definition = r.get(1) + " (" + r.get(2) + ")";
            result.add(List.of(r.get(0), r.get(1), r.get(2), definition));
        }
        return result;
    }

    /**
     * The AUTO_INCREMENT counters: (table.column, last value given, increment, offset, table, column). MySQL 8 caches
     * information_schema's AUTO_INCREMENT for a day; the cache is turned off for this session first.
     */
    @Override
    public List<List<Object>> sequences(Metadata m, Namespace ns, String table) throws SQLException {
        try (java.sql.Statement st = m.connection().createStatement()) {
            st.execute("set session information_schema_stats_expiry = 0");
        } catch (SQLException noSuchVariable) {
            // MariaDB and MySQL 5.7 have no such cache
        }
        String only = table == null ? "" : table;
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> r : m.query("""
                select t.TABLE_NAME, c.COLUMN_NAME, t.AUTO_INCREMENT, @@auto_increment_increment, @@auto_increment_offset
                from information_schema.TABLES t
                join information_schema.COLUMNS c on c.TABLE_SCHEMA = t.TABLE_SCHEMA and c.TABLE_NAME = t.TABLE_NAME
                     and c.EXTRA like '%auto_increment%'
                where t.TABLE_SCHEMA = ? and (? = '' or t.TABLE_NAME = ?)
                order by t.TABLE_NAME
                """, ns.catalog(), only, only)) {
            Object next = r.get(2);
            Object last = next instanceof Number n ? (Object) (n.longValue() - 1) : "";
            rows.add(List.of(r.get(0) + "." + r.get(1), last, r.get(3), r.get(4), r.get(0), r.get(1)));
        }
        return rows;
    }

    @Override
    public String restartSequence(Metadata m, Namespace ns, String sequence, String table, long next) throws SQLException {
        return "ALTER TABLE " + m.qualifiedName(new TableRef(ns.catalog(), null, table, "TABLE")) + " AUTO_INCREMENT = " + next;
    }

    @Override
    public List<List<Object>> triggers(Metadata m, TableRef t) throws SQLException {
        return m.query("""
                select TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, ACTION_ORIENTATION, ACTION_STATEMENT
                from information_schema.TRIGGERS
                where EVENT_OBJECT_SCHEMA = ? and EVENT_OBJECT_TABLE = ?
                order by TRIGGER_NAME
                """, t.catalog(), t.name());
    }

    /**
     * From information_schema.COLUMNS: COLUMN_TYPE is the full type (enum values, unsigned, display width). A default
     * becomes an SQL expression: MySQL lists a literal unquoted and an expression (DEFAULT_GENERATED) as written;
     * MariaDB lists literals quoted already and a NULL default as NULL.
     */
    @Override
    public List<ColumnSpec> columns(Metadata m, TableRef t) throws SQLException {
        boolean maria = m.connection().getMetaData().getDatabaseProductVersion().contains("MariaDB");
        List<ColumnSpec> result = new ArrayList<>();
        for (List<Object> r : m.query("""
                select COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, case when COLUMN_DEFAULT is null then 'NO' else 'YES' end,
                       coalesce(COLUMN_DEFAULT, ''), EXTRA, COLUMN_COMMENT, coalesce(COLLATION_NAME, '')
                from information_schema.COLUMNS
                where TABLE_SCHEMA = ? and TABLE_NAME = ?
                order by ORDINAL_POSITION
                """, t.catalog(), t.name())) {
            String extra = String.valueOf(r.get(5));
            String raw = String.valueOf(r.get(4));
            String def = null;
            if ("YES".equals(r.get(3)) && !(maria && raw.equalsIgnoreCase("NULL"))) {
                if (maria || raw.matches("b'[01]*'")) def = raw; // a bit value is listed as b'…'
                else if (!extra.contains("DEFAULT_GENERATED")) def = "'" + raw.replace("\\", "\\\\").replace("'", "''") + "'";
                else def = raw.toUpperCase().startsWith("CURRENT_TIMESTAMP") ? raw : "(" + raw + ")";
            }
            boolean generated = extra.contains("VIRTUAL GENERATED") || extra.contains("STORED GENERATED");
            result.add(new ColumnSpec(String.valueOf(r.get(0)), String.valueOf(r.get(1)), "YES".equals(r.get(2)), def,
                    String.valueOf(r.get(6)), extra.replace("DEFAULT_GENERATED", "").strip(), String.valueOf(r.get(7)), generated));
        }
        return result;
    }

    /** information_schema.TABLES.TABLE_ROWS: InnoDB's estimate. */
    @Override
    public java.util.Map<String, Long> estimatedRows(Metadata m, Namespace ns) throws SQLException {
        java.util.Map<String, Long> result = new java.util.HashMap<>();
        for (List<Object> r : m.query("select TABLE_NAME, TABLE_ROWS from information_schema.TABLES "
                + "where TABLE_SCHEMA = ? and TABLE_ROWS is not null", ns.catalog())) {
            result.put(String.valueOf(r.get(0)), ((Number) r.get(1)).longValue());
        }
        return result;
    }
}
