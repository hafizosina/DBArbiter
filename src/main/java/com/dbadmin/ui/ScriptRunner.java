package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.StatementResult;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.NamedParams;
import com.dbadmin.service.NamedParams.ParsedSql;
import com.dbadmin.service.SqlSplitter;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runs SQL the same way for script tabs and table query boxes: splits it into statements, asks for
 * ":name" parameter values, runs the statements one by one and stops at the first error.
 * The caller decides how to show what happens through a {@link Listener}. FX thread only.
 */
final class ScriptRunner {

    static final int MAX_ROWS = 1000;
    /** Statements that change the schema, after which cached autocomplete metadata is stale. */
    private static final Pattern DDL = Pattern.compile("\\b(create|alter|drop|rename|truncate)\\b", Pattern.CASE_INSENSITIVE);

    interface Listener {
        /** Statements are about to run; {@code values} are the parameter values used. */
        void started(ConnectionConfig config, int statements, Map<String, String> values);

        void result(int number, StatementResult result);

        void failed(int number, String sql, Throwable error);

        /** Always called last, also after an error or Stop. */
        void finished(String summary);
    }

    enum Outcome { STARTED, NOTHING_TO_RUN, CANCELLED }

    private final SessionProvider sessions;
    private final SqlCompleter completer;
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper();
    /** Last values entered for each parameter name (null = SQL NULL), pre-filled next time. */
    private final Map<String, String> paramValues = new HashMap<>();
    private Listener listener;
    private DatabaseSession runningSession;
    private boolean cancelRequested;
    private boolean schemaChanged;

    ScriptRunner(SessionProvider sessions, SqlCompleter completer) {
        this.sessions = sessions;
        this.completer = completer;
    }

    ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    Outcome run(String sql, ConnectionConfig config, Window owner, Listener listener) {
        if (running.get()) return Outcome.CANCELLED;
        List<ParsedSql> statements = SqlSplitter.split(sql, config.getType()).stream()
                .map(s -> NamedParams.parse(s, config.getType()))
                .toList();
        if (statements.isEmpty()) return Outcome.NOTHING_TO_RUN;
        Set<String> names = new LinkedHashSet<>();
        statements.forEach(s -> names.addAll(s.names()));
        Map<String, String> values = Map.of();
        if (!names.isEmpty()) {
            values = InAppDialogs.showAndWait(new ParamDialog(owner, names, paramValues)).orElse(null);
            if (values == null) return Outcome.CANCELLED;
            paramValues.putAll(values);
        }
        Map<String, String> bound = values;

        this.listener = listener;
        running.set(true);
        cancelRequested = false;
        schemaChanged = DDL.matcher(sql).find();
        listener.started(config, statements.size(), bound);
        sessions.withSession(config, session -> {
            runningSession = session;
            runNext(session, statements, bound, 0, System.nanoTime());
        }, ex -> finish("Could not connect: " + ex.getMessage()));
        return Outcome.STARTED;
    }

    private void runNext(DatabaseSession session, List<ParsedSql> statements, Map<String, String> values,
                         int index, long startNanos) {
        if (cancelRequested) {
            finish("Stopped after " + index + " of " + statements.size() + " statement(s).");
            return;
        }
        if (index == statements.size()) {
            finish("Done: " + statements.size() + " statement(s) in " + (System.nanoTime() - startNanos) / 1_000_000 + " ms.");
            return;
        }
        String sql = statements.get(index).sql();
        // ArrayList, not toList(): values may be null (SQL NULL)
        List<String> params = new ArrayList<>();
        statements.get(index).names().forEach(name -> params.add(values.get(name)));
        Async.run(session, () -> session.execute(sql, params, MAX_ROWS), result -> {
            if (runningSession != session) return;
            listener.result(index + 1, result);
            runNext(session, statements, values, index + 1, startNanos);
        }, ex -> {
            if (runningSession != session) return;
            listener.failed(index + 1, sql, ex);
            finish(cancelRequested ? "Stopped." : "Stopped at the first error.");
        });
    }

    void stop() {
        if (!running.get()) return;
        cancelRequested = true;
        DatabaseSession session = runningSession;
        if (session != null) {
            // cancel from another thread: the session's own worker is busy running the statement
            Async.run(() -> {
                session.cancelRunning();
                return null;
            }, x -> { }, x -> { });
        }
    }

    /** Called when a session is closed (disconnect), so a run on it does not hang. */
    void sessionClosed(DatabaseSession session) {
        if (running.get() && runningSession == session) {
            cancelRequested = true;
            finish("Connection was closed.");
        }
    }

    private void finish(String summary) {
        if (schemaChanged) completer.invalidate(runningSession);
        running.set(false);
        runningSession = null;
        listener.finished(summary);
    }
}
