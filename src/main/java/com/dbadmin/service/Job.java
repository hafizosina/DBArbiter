package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A long task (a compare, later a scan) on connections of its own: it opens a separate session for each connection
 * it needs, so the navigator, table tabs and scripts on those connections keep working meanwhile, and closes them
 * when it ends. It reports progress, and Cancel stops it: the statements running on its sessions are cancelled and
 * the work sees {@link #isCancelled()}. Every callback goes through {@code callbacks} (the FX thread in the app).
 * No JavaFX here.
 */
public final class Job<T> {

    /** The work itself, given one session per connection (in order), running on a background thread. */
    public interface Work<T> {
        T run(List<DatabaseSession> sessions, Job<T> job) throws Exception;
    }

    /** Told how the job goes. Exactly one of done / failed / cancelled is called, after the sessions are closed. */
    public interface Listener<T> {
        default void progress(String text) {
        }

        void done(T result);

        void failed(Throwable error);

        default void cancelled() {
        }
    }

    private static final java.util.logging.Logger LOG = AppLog.get(Job.class);

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<DatabaseSession> sessions = new CopyOnWriteArrayList<>();
    private final Executor callbacks;
    private final Listener<T> listener;

    private Job(Executor callbacks, Listener<T> listener) {
        this.callbacks = callbacks;
        this.listener = listener;
    }

    /**
     * Starts {@code work} on {@code background}: opens a session for each of {@code connections} with
     * {@code opener}, runs the work, closes the sessions, then tells the listener.
     */
    public static <T> Job<T> start(List<ConnectionConfig> connections, SessionManager.Opener opener, Executor background,
                                   Executor callbacks, Work<T> work, Listener<T> listener) {
        Job<T> job = new Job<>(callbacks, listener);
        background.execute(() -> job.run(connections, opener, work));
        return job;
    }

    private void run(List<ConnectionConfig> connections, SessionManager.Opener opener, Work<T> work) {
        T result = null;
        Throwable error = null;
        try {
            List<DatabaseSession> opened = new ArrayList<>();
            for (ConnectionConfig c : connections) {
                checkCancelled();
                DatabaseSession s = opener.open(c);
                sessions.add(s);
                opened.add(s);
            }
            checkCancelled();
            result = work.run(List.copyOf(opened), this);
        } catch (Throwable e) {
            error = e;
        } finally {
            sessions.forEach(DatabaseSession::close);
        }
        if (cancelled.get()) {
            callbacks.execute(listener::cancelled);
        } else if (error != null) {
            log(error);
            Throwable cause = error;
            callbacks.execute(() -> listener.failed(cause));
        } else {
            T done = result;
            callbacks.execute(() -> listener.done(done));
        }
    }

    /** Like {@code Async}: database errors (often just bad SQL) in one line; anything else with its stack trace. */
    private static void log(Throwable e) {
        Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof java.sql.SQLException sql) {
            LOG.info("Database error in a job (SQLState " + sql.getSQLState() + "): " + sql.getMessage());
        } else {
            LOG.log(java.util.logging.Level.WARNING, "A job failed", cause);
        }
    }

    /** From the work: shows how far it got (e.g. "Reading orders: 3/12 tables"). */
    public void progress(String text) {
        if (!cancelled.get()) callbacks.execute(() -> listener.progress(text));
    }

    /** Stops the job: running statements are cancelled, and the listener hears {@code cancelled} instead of an error. */
    public void cancel() {
        if (cancelled.getAndSet(true)) return;
        sessions.forEach(DatabaseSession::cancelRunning);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** From the work, between steps: stops it if the job was cancelled. */
    public void checkCancelled() {
        if (cancelled.get()) throw new CancellationException();
    }
}
