package com.dbadmin.ui;

import com.dbadmin.model.ColumnInfo;
import com.dbadmin.model.DbType;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.CompletionContext;
import com.dbadmin.service.CompletionContext.TableMention;
import com.dbadmin.service.DatabaseSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Builds autocomplete suggestions from SQL keywords and database metadata. Metadata is
 * loaded lazily on the session's worker thread and cached per session.
 * All methods must be called on the FX thread.
 */
final class SqlCompleter {

    enum Kind { COLUMN, TABLE, VIEW, SCHEMA, KEYWORD, TYPE, FUNCTION }

    record Suggestion(String text, Kind kind, String detail) {
    }

    private static final String[] FUNCTIONS = {
            "count", "sum", "avg", "min", "max", "coalesce", "nullif", "cast", "lower", "upper", "trim", "length",
            "substring", "concat", "replace", "round", "floor", "ceil", "abs", "now", "current_date",
            "current_timestamp", "date_trunc", "extract", "to_char", "to_date", "string_agg", "array_agg",
            "json_agg", "jsonb_build_object", "row_number", "rank", "dense_rank", "lag", "lead", "group_concat",
            "ifnull", "date_format", "strftime", "datetime"
    };
    private static final int MAX_SUGGESTIONS = 200;

    private final Map<DatabaseSession, Cache> caches = new WeakHashMap<>();

    /** Forgets cached metadata, e.g. after DDL or a refresh. */
    void invalidate(DatabaseSession session) {
        if (session != null) caches.remove(session);
    }

    /**
     * Calls back with suggestions for {@code ctx}, after loading any metadata it needs.
     * With a null session only keywords are suggested.
     */
    void complete(CompletionContext ctx, DatabaseSession session, Consumer<List<Suggestion>> callback) {
        if (session == null) {
            callback.accept(build(ctx, null));
            return;
        }
        Cache cache = caches.computeIfAbsent(session, s -> new Cache());
        Load load = missing(ctx, cache);
        if (load.isEmpty()) {
            callback.accept(build(ctx, cache));
            return;
        }
        Async.run(session, () -> load.run(session), loaded -> {
            if (caches.get(session) != cache) return; // invalidated meanwhile
            loaded.mergeInto(cache);
            complete(ctx, session, callback);
        }, ex -> callback.accept(build(ctx, cache)));
    }

    // ---- metadata --------------------------------------------------------

    private static final class Cache {
        List<Namespace> namespaces;
        String defaultNamespace;
        final Map<String, List<TableRef>> tables = new HashMap<>();      // by lower-case namespace label
        final Map<TableRef, List<ColumnInfo>> columns = new HashMap<>();
    }

    /** Metadata to fetch in one round trip; also carries the results back. */
    private static final class Load {
        boolean namespaces;
        final List<Namespace> tablesOf = new ArrayList<>();
        final List<TableRef> columnsOf = new ArrayList<>();

        List<Namespace> loadedNamespaces;
        String loadedDefault;
        final Map<String, List<TableRef>> loadedTables = new HashMap<>();
        final Map<TableRef, List<ColumnInfo>> loadedColumns = new HashMap<>();

        boolean isEmpty() {
            return !namespaces && tablesOf.isEmpty() && columnsOf.isEmpty();
        }

        /** Runs on the session worker. A failed item is cached as empty so it is not retried endlessly. */
        Load run(DatabaseSession session) {
            if (namespaces) {
                try {
                    loadedNamespaces = session.listNamespaces();
                    loadedDefault = session.defaultNamespace();
                } catch (Exception e) {
                    loadedNamespaces = List.of();
                }
            }
            for (Namespace ns : tablesOf) {
                try {
                    loadedTables.put(key(ns.label()), session.listTables(ns));
                } catch (Exception e) {
                    loadedTables.put(key(ns.label()), List.of());
                }
            }
            for (TableRef t : columnsOf) {
                try {
                    loadedColumns.put(t, session.columnInfo(t));
                } catch (Exception e) {
                    loadedColumns.put(t, List.of());
                }
            }
            return this;
        }

        void mergeInto(Cache cache) {
            if (loadedNamespaces != null) {
                cache.namespaces = loadedNamespaces;
                cache.defaultNamespace = loadedDefault != null ? loadedDefault
                        : loadedNamespaces.isEmpty() ? null : loadedNamespaces.get(0).label();
            }
            cache.tables.putAll(loadedTables);
            cache.columns.putAll(loadedColumns);
        }
    }

    /** What still has to be fetched before suggestions for {@code ctx} can be built. */
    private static Load missing(CompletionContext ctx, Cache cache) {
        Load load = new Load();
        if (cache.namespaces == null) {
            load.namespaces = true;
            return load;
        }
        List<Namespace> needed = new ArrayList<>();
        addNamespace(needed, cache, cache.defaultNamespace);
        addNamespace(needed, cache, ctx.qualifier());
        for (TableMention m : ctx.mentions()) addNamespace(needed, cache, m.namespace());
        for (Namespace ns : needed) {
            if (!cache.tables.containsKey(key(ns.label()))) load.tablesOf.add(ns);
        }
        if (!load.tablesOf.isEmpty()) return load;

        for (TableRef t : tablesNeedingColumns(ctx, cache)) {
            if (!cache.columns.containsKey(t)) load.columnsOf.add(t);
        }
        return load;
    }

    private static void addNamespace(List<Namespace> out, Cache cache, String label) {
        Namespace ns = namespace(cache, label);
        if (ns != null && !out.contains(ns)) out.add(ns);
    }

    private static List<TableRef> tablesNeedingColumns(CompletionContext ctx, Cache cache) {
        List<TableRef> result = new ArrayList<>();
        if (ctx.qualifier() != null) {
            TableRef t = qualifiedTable(ctx, cache);
            if (t != null) result.add(t);
        } else if (!ctx.tablePosition()) {
            for (TableMention m : ctx.mentions()) {
                TableRef t = table(cache, m.namespace(), m.table());
                if (t != null && !result.contains(t)) result.add(t);
            }
        }
        return result;
    }

    /** The table that "qualifier." refers to: an alias, a table name in the statement, or any table. */
    private static TableRef qualifiedTable(CompletionContext ctx, Cache cache) {
        String q = ctx.qualifier();
        for (TableMention m : ctx.mentions()) {
            if (q.equalsIgnoreCase(m.alias())) return table(cache, m.namespace(), m.table());
        }
        for (TableMention m : ctx.mentions()) {
            if (q.equalsIgnoreCase(m.table())) return table(cache, m.namespace(), m.table());
        }
        return table(cache, null, q);
    }

    private static Namespace namespace(Cache cache, String label) {
        if (label == null || cache.namespaces == null) return null;
        for (Namespace ns : cache.namespaces) {
            if (ns.label().equalsIgnoreCase(label)) return ns;
        }
        return null;
    }

    private static TableRef table(Cache cache, String namespace, String name) {
        String ns = namespace != null ? namespace : cache.defaultNamespace;
        if (ns == null) return null;
        for (TableRef t : cache.tables.getOrDefault(key(ns), List.of())) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        return null;
    }

    private static String key(String label) {
        return label.toLowerCase(Locale.ROOT);
    }

    // ---- suggestions -----------------------------------------------------

    private static List<Suggestion> build(CompletionContext ctx, Cache cache) {
        List<Suggestion> all = new ArrayList<>();
        if (ctx.qualifier() != null) {
            if (cache == null) return List.of();
            TableRef t = qualifiedTable(ctx, cache);
            if (t != null) {
                addColumns(all, cache, t);
            } else {
                Namespace ns = namespace(cache, ctx.qualifier());
                if (ns != null) addTables(all, cache, ns.label());
            }
            return filter(all, ctx.prefix());
        }

        if (cache != null && ctx.tablePosition()) {
            addTables(all, cache, cache.defaultNamespace);
            addSchemas(all, cache);
        }
        if (cache != null && !ctx.tablePosition()) {
            for (TableRef t : tablesNeedingColumns(ctx, cache)) addColumns(all, cache, t);
        }
        for (String k : SqlHighlighter.KEYWORDS) all.add(new Suggestion(k.toUpperCase(Locale.ROOT), Kind.KEYWORD, null));
        for (String f : FUNCTIONS) all.add(new Suggestion(f, Kind.FUNCTION, "function"));
        for (String t : SqlHighlighter.TYPES) all.add(new Suggestion(t.toUpperCase(Locale.ROOT), Kind.TYPE, "type"));
        if (cache != null && !ctx.tablePosition()) {
            addTables(all, cache, cache.defaultNamespace);
            addSchemas(all, cache);
        }
        return filter(all, ctx.prefix());
    }

    private static void addColumns(List<Suggestion> out, Cache cache, TableRef t) {
        for (ColumnInfo c : cache.columns.getOrDefault(t, List.of())) {
            out.add(new Suggestion(c.name(), Kind.COLUMN, c.type() + " · " + t.name()));
        }
    }

    private static void addTables(List<Suggestion> out, Cache cache, String namespace) {
        if (namespace == null) return;
        for (TableRef t : cache.tables.getOrDefault(key(namespace), List.of())) {
            out.add(new Suggestion(t.name(), t.isView() ? Kind.VIEW : Kind.TABLE, t.type().toLowerCase(Locale.ROOT)));
        }
    }

    private static void addSchemas(List<Suggestion> out, Cache cache) {
        for (Namespace ns : cache.namespaces) out.add(new Suggestion(ns.label(), Kind.SCHEMA, "schema"));
    }

    /** Prefix matches first, then (for 2+ characters) substring matches; duplicates removed. */
    private static List<Suggestion> filter(List<Suggestion> all, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        Map<String, Suggestion> starts = new LinkedHashMap<>();
        Map<String, Suggestion> contains = new LinkedHashMap<>();
        for (Suggestion s : all) {
            String text = s.text().toLowerCase(Locale.ROOT);
            String id = s.kind() + ":" + text;
            if (text.startsWith(p)) starts.putIfAbsent(id, s);
            else if (p.length() >= 2 && text.contains(p)) contains.putIfAbsent(id, s);
        }
        List<Suggestion> result = new ArrayList<>(starts.values());
        result.addAll(contains.values());
        return result.size() > MAX_SUGGESTIONS ? result.subList(0, MAX_SUGGESTIONS) : result;
    }

    /** The text to insert: keywords follow the case being typed, identifiers are quoted when needed. */
    static String insertText(Suggestion s, String typed, DbType type) {
        return switch (s.kind()) {
            case KEYWORD, TYPE, FUNCTION -> !typed.isEmpty() && typed.equals(typed.toLowerCase(Locale.ROOT))
                    ? s.text().toLowerCase(Locale.ROOT)
                    : s.kind() == Kind.FUNCTION ? s.text() : s.text().toUpperCase(Locale.ROOT);
            default -> quoteIfNeeded(s.text(), type);
        };
    }

    static String quoteIfNeeded(String name, DbType type) {
        if (type == DbType.MYSQL) {
            return name.matches("[A-Za-z0-9_$]+") ? name : "`" + name.replace("`", "``") + "`";
        }
        // PostgreSQL folds unquoted names to lower case; SQLite is case-insensitive
        String plain = type == DbType.POSTGRESQL ? "[a-z_][a-z0-9_$]*" : "[A-Za-z_][A-Za-z0-9_]*";
        return name.matches(plain) ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
