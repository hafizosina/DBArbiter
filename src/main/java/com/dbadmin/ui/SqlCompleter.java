package com.dbadmin.ui;

import com.dbadmin.model.ColumnInfo;
import com.dbadmin.model.DbType;
import com.dbadmin.model.Namespace;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.AppLog;
import com.dbadmin.service.CompletionContext;
import com.dbadmin.service.CompletionContext.TableMention;
import com.dbadmin.service.DatabaseSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Builds autocomplete suggestions from SQL keywords and database metadata. Metadata is
 * loaded lazily on the session's worker thread and cached per session.
 * All methods must be called on the FX thread.
 */
final class SqlCompleter {

    enum Kind { COLUMN, TABLE, VIEW, SCHEMA, KEYWORD, TYPE, FUNCTION }

    /** {@code schema}: set for a table outside the connection's current schema; it is inserted qualified. */
    record Suggestion(String text, Kind kind, String detail, String schema) {
        Suggestion(String text, Kind kind, String detail) {
            this(text, kind, detail, null);
        }
    }

    private static final String[] FUNCTIONS = {
            "count", "sum", "avg", "min", "max", "coalesce", "nullif", "cast", "lower", "upper", "trim", "length",
            "substring", "concat", "replace", "round", "floor", "ceil", "abs", "now", "current_date",
            "current_timestamp", "date_trunc", "extract", "to_char", "to_date", "string_agg", "array_agg",
            "json_agg", "jsonb_build_object", "row_number", "rank", "dense_rank", "lag", "lead", "group_concat",
            "ifnull", "date_format", "strftime", "datetime"
    };
    private static final int MAX_SUGGESTIONS = 200;
    private static final Logger LOG = AppLog.get(SqlCompleter.class);

    private final Map<DatabaseSession, Cache> caches = new WeakHashMap<>();
    private Consumer<DatabaseSession> onInvalidate;

    /** Forgets cached metadata, e.g. after DDL or a refresh. */
    void invalidate(DatabaseSession session) {
        if (session == null) return;
        caches.remove(session);
        if (onInvalidate != null) onInvalidate.accept(session);
    }

    /** Also told when metadata is invalidated (e.g. so the table search reloads too). */
    void setOnInvalidate(Consumer<DatabaseSession> listener) {
        this.onInvalidate = listener;
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
        Cache cache = caches.computeIfAbsent(session, s -> new Cache(s.getConfig().getType()));
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
        final DbType type;
        List<Namespace> namespaces;
        String defaultNamespace;
        final Map<String, List<TableRef>> tables = new HashMap<>();      // by lower-case namespace label
        final Map<TableRef, List<ColumnInfo>> columns = new HashMap<>();

        Cache(DbType type) {
            this.type = type;
        }

        /** The schemas other than the current one whose tables are offered too (system schemas left out). */
        List<Namespace> otherSchemas() {
            if (namespaces == null) return List.of();
            return namespaces.stream().filter(ns -> !ns.isSystem(type) && !ns.label().equalsIgnoreCase(String.valueOf(defaultNamespace))).toList();
        }
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
                    LOG.info("Autocomplete could not load the schemas: " + e.getMessage());
                    loadedNamespaces = List.of();
                }
            }
            for (Namespace ns : tablesOf) {
                try {
                    loadedTables.put(key(ns.label()), session.listTables(ns));
                } catch (Exception e) {
                    LOG.info("Autocomplete could not load the tables of " + ns.label() + ": " + e.getMessage());
                    loadedTables.put(key(ns.label()), List.of());
                }
            }
            for (TableRef t : columnsOf) {
                try {
                    loadedColumns.put(t, session.columnInfo(t));
                } catch (Exception e) {
                    LOG.info("Autocomplete could not load the columns of " + t.displayName() + ": " + e.getMessage());
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
        // after FROM / JOIN: tables of every schema, e.g. when the current one is "public" but the tables are elsewhere
        if (ctx.qualifier() == null && ctx.tablePosition()) cache.otherSchemas().forEach(ns -> addNamespace(needed, cache, ns.label()));
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
            for (Namespace ns : cache.otherSchemas()) addTables(all, cache, ns.label(), true);
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
        addTables(out, cache, namespace, false);
    }

    /** With {@code qualified}, the schema is shown and inserted with the name (a table outside the current schema). */
    private static void addTables(List<Suggestion> out, Cache cache, String namespace, boolean qualified) {
        if (namespace == null) return;
        for (TableRef t : cache.tables.getOrDefault(key(namespace), List.of())) {
            String kind = t.type().toLowerCase(Locale.ROOT);
            out.add(new Suggestion(t.name(), t.isView() ? Kind.VIEW : Kind.TABLE, qualified ? kind + " · " + namespace : kind,
                    qualified ? namespace : null));
        }
    }

    private static void addSchemas(List<Suggestion> out, Cache cache) {
        for (Namespace ns : cache.namespaces) out.add(new Suggestion(ns.label(), Kind.SCHEMA, "schema"));
    }

    /**
     * Prefix matches first, then (for 2+ characters) tables whose initials start with it ("mpv" finds
     * m_product_variant, "bup" finds bo_user_portal and bo_user_path), then substring matches; duplicates removed.
     */
    static List<Suggestion> filter(List<Suggestion> all, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        Map<String, Suggestion> starts = new LinkedHashMap<>();
        Map<String, Suggestion> initials = new LinkedHashMap<>();
        Map<String, Suggestion> contains = new LinkedHashMap<>();
        for (Suggestion s : all) {
            String text = s.text().toLowerCase(Locale.ROOT);
            String id = s.kind() + ":" + s.schema() + ":" + text; // the same name in two schemas: both offered
            boolean table = s.kind() == Kind.TABLE || s.kind() == Kind.VIEW;
            if (text.startsWith(p)) starts.putIfAbsent(id, s);
            else if (p.length() >= 2 && table && initials(s.text()).startsWith(p)) initials.putIfAbsent(id, s);
            else if (p.length() >= 2 && text.contains(p)) contains.putIfAbsent(id, s);
        }
        List<Suggestion> result = new ArrayList<>(starts.values());
        result.addAll(initials.values());
        result.addAll(contains.values());
        return result.size() > MAX_SUGGESTIONS ? result.subList(0, MAX_SUGGESTIONS) : result;
    }

    /** The text to insert: keywords follow the case being typed, identifiers are quoted when needed. */
    static String insertText(Suggestion s, String typed, DbType type) {
        return switch (s.kind()) {
            case KEYWORD, TYPE, FUNCTION -> !typed.isEmpty() && typed.equals(typed.toLowerCase(Locale.ROOT))
                    ? s.text().toLowerCase(Locale.ROOT)
                    : s.kind() == Kind.FUNCTION ? s.text() : s.text().toUpperCase(Locale.ROOT);
            default -> (s.schema() == null ? "" : quoteIfNeeded(s.schema(), type) + ".") + quoteIfNeeded(s.text(), type);
        };
    }

    /**
     * DBeaver-style table alias: the first letter of each word, "m_product_variant" -> "mpv",
     * "TransactionOrder" -> "to". Never an alias already in use or a SQL keyword: then a number is added.
     */
    static String aliasFor(String table, Set<String> taken) {
        String letters = initials(table);
        String base = letters.isEmpty() || !Character.isLetter(letters.charAt(0)) ? "t" + letters : letters;
        Set<String> used = new HashSet<>();
        taken.forEach(a -> used.add(a.toLowerCase(Locale.ROOT)));
        String alias = base;
        for (int n = 2; used.contains(alias) || RESERVED.contains(alias); n++) alias = base + n;
        return alias;
    }

    /** The first letter of each word, lower case: "m_product_variant" -> "mpv", "TransactionOrder" -> "to". */
    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        boolean wordStart = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                wordStart = true;
            } else {
                boolean camelStart = i > 0 && Character.isUpperCase(c) && Character.isLowerCase(name.charAt(i - 1));
                if (wordStart || camelStart) sb.append(Character.toLowerCase(c));
                wordStart = false;
            }
        }
        return sb.toString();
    }

    private static final Set<String> RESERVED = new HashSet<>();

    static {
        for (String k : SqlHighlighter.KEYWORDS) RESERVED.add(k);
        for (String t : SqlHighlighter.TYPES) RESERVED.add(t);
    }

    /** A name as written in SQL for this database (see {@link DbType#quoteIfNeeded}); no type: plain SQL rules. */
    static String quoteIfNeeded(String name, DbType type) {
        return (type == null ? DbType.SQLITE : type).quoteIfNeeded(name);
    }
}
