package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * The open sessions, one per connection (by id). Opening one runs in the background; whoever asks
 * for a connection while it is being opened waits for that same session instead of opening another.
 * Not thread-safe: call it, and get called back, on one thread only (the FX thread in the app).
 */
public final class SessionManager {

    /** Opens a session; {@link DatabaseSession#open} in the app. */
    public interface Opener {
        DatabaseSession open(ConnectionConfig config) throws Exception;
    }

    /** Told about opening sessions, before the callers waiting for them. */
    public interface Listener {
        default void connecting(ConnectionConfig config) {
        }

        default void opened(ConnectionConfig config, DatabaseSession session) {
        }

        default void failed(ConnectionConfig config, Throwable error) {
        }
    }

    private record Waiter(Consumer<DatabaseSession> onReady, Consumer<Throwable> onError) {
    }

    private static final Logger LOG = AppLog.get(SessionManager.class);

    private final Map<String, DatabaseSession> open = new HashMap<>();
    /** Callbacks waiting for a session that is being opened, by connection id. */
    private final Map<String, List<Waiter>> connecting = new HashMap<>();
    private final Opener opener;
    private final Executor background;
    private final Executor callbacks;
    private final Predicate<ConnectionConfig> isCurrent;
    private final Listener listener;

    /**
     * @param background where sessions are opened
     * @param callbacks  the thread this manager is used on; results are handed back there
     * @param isCurrent  false for a connection that was edited or deleted while it was being opened:
     *                   its new session is closed again and the waiting callers get an error
     */
    public SessionManager(Opener opener, Executor background, Executor callbacks, Predicate<ConnectionConfig> isCurrent,
                          Listener listener) {
        this.opener = opener;
        this.background = background;
        this.callbacks = callbacks;
        this.isCurrent = isCurrent;
        this.listener = listener;
    }

    /** Calls back with the connection's session, opening it first if needed. */
    public void withSession(ConnectionConfig config, Consumer<DatabaseSession> onReady, Consumer<Throwable> onError) {
        DatabaseSession existing = open.get(config.getId());
        if (existing != null) {
            onReady.accept(existing);
            return;
        }
        List<Waiter> waiters = connecting.get(config.getId());
        if (waiters != null) {
            waiters.add(new Waiter(onReady, onError));
            return;
        }
        connecting.put(config.getId(), new ArrayList<>(List.of(new Waiter(onReady, onError))));
        listener.connecting(config);
        background.execute(() -> {
            try {
                DatabaseSession session = opener.open(config);
                callbacks.execute(() -> opened(config, session));
            } catch (Throwable e) {
                LOG.info("Could not connect to \"" + config.getName() + "\": " + e.getMessage());
                callbacks.execute(() -> failed(config, e));
            }
        });
    }

    private void opened(ConnectionConfig config, DatabaseSession session) {
        List<Waiter> waiters = connecting.remove(config.getId());
        if (!isCurrent.test(config)) {
            session.close();
            waiters.forEach(w -> w.onError().accept(new IllegalStateException("Connection was changed")));
            return;
        }
        open.put(config.getId(), session);
        listener.opened(config, session);
        waiters.forEach(w -> w.onReady().accept(session));
    }

    private void failed(ConnectionConfig config, Throwable error) {
        List<Waiter> waiters = connecting.remove(config.getId());
        listener.failed(config, error);
        waiters.forEach(w -> w.onError().accept(error));
    }

    /** The session if the connection is open; never opens one. */
    public Optional<DatabaseSession> get(String connectionId) {
        return Optional.ofNullable(open.get(connectionId));
    }

    public boolean isOpen(String connectionId) {
        return open.containsKey(connectionId);
    }

    /** The open sessions (a copy). */
    public List<DatabaseSession> all() {
        return List.copyOf(open.values());
    }

    /** Closes the connection's session; returns it, so callers can forget what they kept about it. */
    public Optional<DatabaseSession> close(String connectionId) {
        DatabaseSession session = open.remove(connectionId);
        if (session != null) session.close();
        return Optional.ofNullable(session);
    }

    public void closeAll() {
        open.values().forEach(DatabaseSession::close);
        open.clear();
    }
}
