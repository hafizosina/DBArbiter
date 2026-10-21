package com.dbadmin.ui;

import com.dbadmin.service.AppLog;
import com.dbadmin.service.DatabaseSession;
import javafx.application.Platform;

import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Runs blocking work off the FX thread and delivers the result back on it. */
final class Async {

    private static final Logger LOG = AppLog.get(Async.class);
    private static final ExecutorService GENERAL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dbadmin-bg");
        t.setDaemon(true);
        return t;
    });

    private Async() {
    }

    /** Work that has no session yet, such as connecting. */
    static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        GENERAL.submit(wrap(work, onSuccess, onError));
    }

    /** Work that uses a session's connection; runs on that session's worker thread. */
    static <T> void run(DatabaseSession session, Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        session.submit(wrap(work, onSuccess, onError));
    }

    private static <T> Callable<Void> wrap(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        return () -> {
            try {
                T result = work.call();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Throwable e) {
                log(e);
                Platform.runLater(() -> onError.accept(e));
            }
            return null;
        };
    }

    /** Database errors (often just bad SQL) in one line; anything else is unexpected, with its stack trace. */
    private static void log(Throwable e) {
        if (e instanceof SQLException sql) {
            LOG.info("Database error (SQLState " + sql.getSQLState() + "): " + sql.getMessage());
        } else {
            LOG.log(Level.WARNING, "Background task failed", e);
        }
    }
}
