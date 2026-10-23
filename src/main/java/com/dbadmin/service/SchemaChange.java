package com.dbadmin.service;

import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The DDL that changes a table's structure: columns, constraints, indexes and foreign keys, written for each
 * database. Names are always quoted. A change the database cannot make (most of them on SQLite, which can only add,
 * rename and drop columns and create or drop indexes) throws {@link Unsupported} with the reason.
 */
public final class SchemaChange {

    public enum ConstraintKind {
        PRIMARY_KEY("PRIMARY KEY"), UNIQUE("UNIQUE"), CHECK("CHECK");

        public final String sql;

        ConstraintKind(String sql) {
            this.sql = sql;
        }

        /** As the Constraints section lists it ("PRIMARY KEY", "UNIQUE", "CHECK"); null for others (e.g. EXCLUDE). */
        public static ConstraintKind of(String listed) {
            for (ConstraintKind k : values()) if (k.sql.equalsIgnoreCase(listed.strip())) return k;
            return null;
        }
    }

    /** {@code check}: the condition of a CHECK constraint, without the parentheses around it. */
    public record ConstraintSpec(String name, ConstraintKind kind, List<String> columns, String check) {
    }

    /** {@code columns} may end in " DESC" (or " ASC"). */
    public record IndexSpec(String name, List<String> columns, boolean unique) {
    }

    /** {@code onUpdate} / {@code onDelete}: CASCADE, SET NULL, SET DEFAULT, RESTRICT or NO ACTION (the default). */
    public record ForeignKeySpec(String name, List<String> columns, TableRef references, List<String> referencedColumns,
                                 String onUpdate, String onDelete) {
    }

    /** The database cannot make this change; the message says why. */
    public static final class Unsupported extends RuntimeException {
        public Unsupported(String message) {
            super(message);
        }
    }

    public static final List<String> RULES = List.of("NO ACTION", "RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT");

    private SchemaChange() {
    }

    // ---- columns ----

    public static List<String> addColumn(DbType type, TableRef t, ColumnSpec c) {
        requireName(c.name(), "column");
        requireName(c.type(), "type");
        if (type == DbType.SQLITE && !c.comment().isEmpty()) throw new Unsupported("SQLite has no column comments.");
        List<String> sql = new ArrayList<>();
        sql.add(alter(type, t) + " ADD COLUMN " + columnDefinition(type, c));
        if (type == DbType.POSTGRESQL && !c.comment().isEmpty()) sql.add(commentOn(type, t, c.name(), c.comment()));
        return sql;
    }

    /** The statements that turn {@code before} into {@code after}; none when nothing changed. */
    public static List<String> alterColumn(DbType type, TableRef t, ColumnSpec before, ColumnSpec after) {
        requireName(after.name(), "column");
        requireName(after.type(), "type");
        boolean renamed = !before.name().equals(after.name());
        boolean retyped = !before.type().equals(after.type());
        boolean nullability = before.nullable() != after.nullable();
        boolean defaulted = !Objects.equals(blankToNull(before.defaultValue()), blankToNull(after.defaultValue()));
        boolean commented = !before.comment().equals(after.comment());
        List<String> sql = new ArrayList<>();
        if (!renamed && !retyped && !nullability && !defaulted && !commented) return sql;
        String table = alter(type, t);
        switch (type) {
            case POSTGRESQL -> {
                if (before.generated() && (retyped || nullability || defaulted)) {
                    throw new Unsupported(before.name() + " is a generated column: only its name and comment can change here.");
                }
                String column = type.quote(after.name());
                if (renamed) sql.add(table + " RENAME COLUMN " + type.quote(before.name()) + " TO " + column);
                if (retyped) {
                    String collate = after.collation().isEmpty() ? "" : " COLLATE " + type.quote(after.collation());
                    sql.add(table + " ALTER COLUMN " + column + " TYPE " + after.type() + collate
                            + " USING " + column + "::" + after.type());
                }
                if (defaulted) {
                    sql.add(table + " ALTER COLUMN " + column + (blankToNull(after.defaultValue()) == null ? " DROP DEFAULT"
                            : " SET DEFAULT " + after.defaultValue().strip()));
                }
                if (nullability) sql.add(table + " ALTER COLUMN " + column + (after.nullable() ? " DROP NOT NULL" : " SET NOT NULL"));
                if (commented) sql.add(commentOn(type, t, after.name(), after.comment()));
            }
            case MYSQL -> {
                if (renamed && !retyped && !nullability && !defaulted && !commented) {
                    // only the name: the definition is not restated at all (MySQL 8, MariaDB 10.5)
                    sql.add(table + " RENAME COLUMN " + type.quote(before.name()) + " TO " + type.quote(after.name()));
                    return sql;
                }
                if (before.generated()) throw new Unsupported(before.name() + " is a generated column: only its name can change here.");
                // MySQL restates the whole column, so what the form does not show (auto_increment, collation) is kept
                ColumnSpec kept = new ColumnSpec(after.name(), after.type(), after.nullable(), after.defaultValue(), after.comment(),
                        before.extra(), textType(after.type()) ? before.collation() : "", false);
                sql.add(table + " CHANGE COLUMN " + type.quote(before.name()) + " " + columnDefinition(type, kept));
            }
            case SQLITE -> {
                if (retyped || nullability || defaulted || commented) {
                    throw new Unsupported("SQLite can only rename a column. To change its type, NOT NULL or default, the table "
                            + "has to be created again (CREATE TABLE …, copy the rows, DROP, RENAME) in a script.");
                }
                sql.add(table + " RENAME COLUMN " + type.quote(before.name()) + " TO " + type.quote(after.name()));
            }
        }
        return sql;
    }

    public static List<String> dropColumn(DbType type, TableRef t, String column) {
        return List.of(alter(type, t) + " DROP COLUMN " + type.quote(column));
    }

    /** "name type [COLLATE …] [NOT NULL] [DEFAULT …] [extra] [COMMENT …]" as each database writes it. */
    private static String columnDefinition(DbType type, ColumnSpec c) {
        StringBuilder sb = new StringBuilder(type.quote(c.name())).append(' ').append(c.type().strip());
        if (!c.collation().isEmpty()) sb.append(" COLLATE ").append(type == DbType.MYSQL ? c.collation() : type.quote(c.collation()));
        if (!c.nullable()) sb.append(" NOT NULL");
        else if (type == DbType.MYSQL) sb.append(" NULL");
        if (blankToNull(c.defaultValue()) != null) sb.append(" DEFAULT ").append(c.defaultValue().strip());
        if (type == DbType.MYSQL && !c.extra().isEmpty()) sb.append(' ').append(c.extra());
        if (type == DbType.MYSQL && !c.comment().isEmpty()) sb.append(" COMMENT ").append(literal(type, c.comment()));
        return sb.toString();
    }

    private static String commentOn(DbType type, TableRef t, String column, String comment) {
        return "COMMENT ON COLUMN " + table(type, t) + "." + type.quote(column) + " IS "
                + (comment.isEmpty() ? "NULL" : literal(type, comment));
    }

    private static boolean textType(String type) {
        return type.strip().toLowerCase(Locale.ROOT).matches("(var)?char.*|.*text.*|enum.*|set.*");
    }

    // ---- constraints ----

    public static List<String> addConstraint(DbType type, TableRef t, ConstraintSpec c) {
        noSqlite(type, "add constraints to a table after it is created; a unique index does what a UNIQUE constraint does");
        String body = switch (c.kind()) {
            case PRIMARY_KEY, UNIQUE -> c.kind().sql + " (" + columns(type, c.columns(), false) + ")";
            case CHECK -> {
                requireName(c.check(), "condition");
                yield "CHECK (" + c.check().strip() + ")";
            }
        };
        // MySQL names its primary key PRIMARY whatever it is called
        boolean named = !c.name().isBlank() && !(type == DbType.MYSQL && c.kind() == ConstraintKind.PRIMARY_KEY);
        return List.of(alter(type, t) + " ADD " + (named ? "CONSTRAINT " + type.quote(c.name().strip()) + " " : "") + body);
    }

    public static List<String> dropConstraint(DbType type, TableRef t, ConstraintSpec c) {
        noSqlite(type, "drop a constraint from a table; the table has to be created again without it");
        if (type == DbType.MYSQL) {
            return List.of(alter(type, t) + switch (c.kind()) {
                case PRIMARY_KEY -> " DROP PRIMARY KEY";
                case UNIQUE -> " DROP INDEX " + type.quote(c.name());
                case CHECK -> " DROP CHECK " + type.quote(c.name());
            });
        }
        return List.of(alter(type, t) + " DROP CONSTRAINT " + type.quote(c.name()));
    }

    // ---- indexes ----

    public static List<String> addIndex(DbType type, TableRef t, IndexSpec i) {
        requireName(i.name(), "index name");
        String name = type == DbType.SQLITE ? namespaced(type, t, i.name()) : type.quote(i.name().strip());
        // SQLite: the index is named in the table's schema, the table itself is not qualified
        String on = type == DbType.SQLITE ? type.quote(t.name()) : table(type, t);
        return List.of("CREATE " + (i.unique() ? "UNIQUE " : "") + "INDEX " + name + " ON " + on
                + " (" + columns(type, i.columns(), true) + ")");
    }

    public static List<String> dropIndex(DbType type, TableRef t, String name) {
        return List.of(switch (type) {
            case MYSQL -> "DROP INDEX " + type.quote(name) + " ON " + table(type, t);
            case POSTGRESQL, SQLITE -> "DROP INDEX " + namespaced(type, t, name);
        });
    }

    // ---- foreign keys ----

    public static List<String> addForeignKey(DbType type, TableRef t, ForeignKeySpec f) {
        noSqlite(type, "add a foreign key to a table after it is created; the table has to be created again with it");
        if (f.references() == null) throw new IllegalArgumentException("Choose the table it references.");
        if (f.columns().size() != f.referencedColumns().size()) {
            throw new IllegalArgumentException(f.columns().size() + " column(s) cannot reference " + f.referencedColumns().size() + ".");
        }
        StringBuilder sb = new StringBuilder(alter(type, t)).append(" ADD ");
        if (!f.name().isBlank()) sb.append("CONSTRAINT ").append(type.quote(f.name().strip())).append(' ');
        sb.append("FOREIGN KEY (").append(columns(type, f.columns(), false)).append(") REFERENCES ")
                .append(table(type, f.references())).append(" (").append(columns(type, f.referencedColumns(), false)).append(')');
        if (f.onUpdate() != null && !f.onUpdate().equals("NO ACTION")) sb.append(" ON UPDATE ").append(f.onUpdate());
        if (f.onDelete() != null && !f.onDelete().equals("NO ACTION")) sb.append(" ON DELETE ").append(f.onDelete());
        return List.of(sb.toString());
    }

    public static List<String> dropForeignKey(DbType type, TableRef t, String name) {
        noSqlite(type, "drop a foreign key from a table; the table has to be created again without it");
        return List.of(alter(type, t) + (type == DbType.MYSQL ? " DROP FOREIGN KEY " : " DROP CONSTRAINT ") + type.quote(name));
    }

    // ---- whole tables ----

    /** Every row deleted; ids keep counting (PostgreSQL). MySQL's TRUNCATE also restarts them; SQLite has no TRUNCATE. */
    public static List<String> truncateTable(DbType type, TableRef t) {
        return List.of(type == DbType.SQLITE ? "DELETE FROM " + table(type, t) : "TRUNCATE TABLE " + table(type, t));
    }

    /**
     * Every row deleted and the ids restarted from 1. {@code sqliteSequence}: the database has sqlite_sequence (made
     * by AUTOINCREMENT), where SQLite keeps the last id.
     */
    public static List<String> resetTable(DbType type, TableRef t, boolean sqliteSequence) {
        return switch (type) {
            case POSTGRESQL -> List.of("TRUNCATE TABLE " + table(type, t) + " RESTART IDENTITY");
            case MYSQL -> List.of("TRUNCATE TABLE " + table(type, t));
            case SQLITE -> sqliteSequence
                    ? List.of("DELETE FROM " + table(type, t), "DELETE FROM sqlite_sequence WHERE name = " + literal(type, t.name()))
                    : List.of("DELETE FROM " + table(type, t));
        };
    }

    public static List<String> dropTable(DbType type, TableRef t) {
        return List.of("DROP TABLE " + table(type, t));
    }

    public static List<String> renameTable(DbType type, TableRef t, String newName) {
        requireName(newName, "new name");
        if (newName.strip().equals(t.name())) return List.of();
        return List.of(type == DbType.MYSQL
                ? "RENAME TABLE " + table(type, t) + " TO " + table(type, sibling(t, newName))
                : alter(type, t) + " RENAME TO " + type.quote(newName.strip()));
    }

    /** A copy of the table with all its rows (columns and rows only: no keys, indexes or defaults). */
    public static List<String> backupTable(DbType type, TableRef t, String backupName) {
        requireName(backupName, "backup table's name");
        if (backupName.strip().equals(t.name())) throw new IllegalArgumentException("Choose another name than the table's own.");
        return List.of("CREATE TABLE " + table(type, sibling(t, backupName)) + " AS SELECT * FROM " + table(type, t));
    }

    /** "orders_backup_20261023": a backup name for today. */
    public static String backupName(TableRef t, java.time.LocalDate day) {
        return t.name() + "_backup_" + day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** A table of the same schema / database, by name. */
    private static TableRef sibling(TableRef t, String name) {
        return new TableRef(t.catalog(), t.schema(), name.strip(), t.type());
    }

    // ---- helpers ----

    /** "a, b" as typed into a form: the names, trimmed, empty ones left out. */
    public static List<String> parseColumns(String text) {
        List<String> result = new ArrayList<>();
        for (String part : text.split(",")) if (!part.isBlank()) result.add(part.strip());
        return result;
    }

    /** The table as written in DDL: schema.table, or db.table on MySQL. */
    public static String table(DbType type, TableRef t) {
        String prefix = type.qualifiesByCatalog() ? t.catalog() : t.schema();
        return (prefix == null || prefix.isEmpty() ? "" : type.quote(prefix) + ".") + type.quote(t.name());
    }

    private static String alter(DbType type, TableRef t) {
        return "ALTER TABLE " + table(type, t);
    }

    /** An index name in the table's schema (PostgreSQL, SQLite: an index belongs to a schema, not to its table). */
    private static String namespaced(DbType type, TableRef t, String name) {
        return (t.schema() == null || t.schema().isEmpty() ? "" : type.quote(t.schema()) + ".") + type.quote(name.strip());
    }

    private static String columns(DbType type, List<String> columns, boolean withOrder) {
        if (columns.isEmpty()) throw new IllegalArgumentException("Name at least one column.");
        List<String> quoted = new ArrayList<>();
        for (String c : columns) {
            String name = c.strip();
            String order = "";
            if (withOrder && name.toUpperCase(Locale.ROOT).matches(".*\\s(ASC|DESC)")) {
                int space = name.lastIndexOf(' ');
                order = name.substring(space).toUpperCase(Locale.ROOT);
                name = name.substring(0, space).strip();
            }
            quoted.add(type.quote(name) + order);
        }
        return String.join(", ", quoted);
    }

    private static String literal(DbType type, String text) {
        String s = type.backslashEscapes() ? text.replace("\\", "\\\\") : text;
        return "'" + s.replace("'", "''") + "'";
    }

    private static void noSqlite(DbType type, String what) {
        if (type == DbType.SQLITE) throw new Unsupported("SQLite cannot " + what + ".");
    }

    private static void requireName(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Enter the " + what + ".");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
