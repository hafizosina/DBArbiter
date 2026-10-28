package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.Job;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

/**
 * Everything running in the background, for the tasks panel: queries and scripts, compares, scans, exports and
 * imports. FX thread only. A task is listed from its start to its end, with its progress and a way to stop it.
 */
final class BackgroundTasks {

    /** A running task: what it is, since when, what it is doing now, and how to stop it. */
    static final class Task {
        final String title;
        final long startedMillis = System.currentTimeMillis();
        final StringProperty progress = new SimpleStringProperty("");
        final Runnable stop;

        private Task(String title, Runnable stop) {
            this.title = title;
            this.stop = stop;
        }
    }

    private static final ObservableList<Task> RUNNING = FXCollections.observableArrayList();

    private BackgroundTasks() {
    }

    static ObservableList<Task> running() {
        return RUNNING;
    }

    static Task add(String title, Runnable stop) {
        Task t = new Task(title, stop);
        RUNNING.add(t);
        return t;
    }

    static void remove(Task t) {
        RUNNING.remove(t);
    }

    /**
     * {@link Job#start} on the app's threads, listed while it runs: its progress shows in the panel, Stop there
     * cancels it, and it leaves the list when it ends (before the listener hears how).
     */
    static <T> Job<T> start(String title, List<ConnectionConfig> connections, Job.Work<T> work, Job.Listener<T> listener) {
        Task[] task = new Task[1];
        Job<T> job = Job.start(connections, DatabaseSession::open, Async.background(), Platform::runLater, work, new Job.Listener<>() {
            @Override
            public void progress(String text) {
                if (task[0] != null) task[0].progress.set(text);
                listener.progress(text);
            }

            @Override
            public void done(T result) {
                remove(task[0]);
                listener.done(result);
            }

            @Override
            public void failed(Throwable error) {
                remove(task[0]);
                listener.failed(error);
            }

            @Override
            public void cancelled() {
                remove(task[0]);
                listener.cancelled();
            }
        });
        task[0] = add(title, job::cancel);
        return job;
    }
}
