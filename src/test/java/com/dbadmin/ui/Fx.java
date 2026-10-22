package com.dbadmin.ui;

import javafx.application.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Runs test code on the JavaFX thread, starting JavaFX once for all tests. Needs a display (CI runs the tests under
 * xvfb-run). For screen pieces that need no database and show no dialog.
 */
final class Fx {

    private static boolean started;

    private Fx() {
    }

    static synchronized void start() {
        if (started) return;
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            // started by another test class
        }
        Platform.setImplicitExit(false);
        started = true;
    }

    /** Runs {@code work} on the JavaFX thread and returns its result (its exception, if it threw). */
    static <T> T call(Callable<T> work) throws Exception {
        start();
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    static void run(Runnable work) throws Exception {
        call(() -> {
            work.run();
            return null;
        });
    }
}
