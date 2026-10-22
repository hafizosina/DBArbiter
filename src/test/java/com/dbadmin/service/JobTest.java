package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobTest {

    /** Runs the job and its callbacks right away, on the test thread. */
    private static final Executor NOW = Runnable::run;

    @TempDir
    Path tmp;

    private final List<DatabaseSession> opened = new ArrayList<>();
    private final List<String> heard = new ArrayList<>();

    private ConnectionConfig sqlite(String name) throws SQLException {
        Path file = tmp.resolve(name + ".db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("create table t (id integer primary key, v text)");
            st.execute("insert into t (v) values ('" + name + "')");
        }
        ConnectionConfig c = new ConnectionConfig();
        c.setName(name);
        c.setType(DbType.SQLITE);
        c.setDatabase(file.toString());
        return c;
    }

    private DatabaseSession open(ConnectionConfig c) throws SQLException {
        DatabaseSession s = DatabaseSession.open(c);
        opened.add(s);
        return s;
    }

    private Job.Listener<String> listener() {
        return new Job.Listener<>() {
            @Override
            public void progress(String text) {
                heard.add("progress " + text);
            }

            @Override
            public void done(String result) {
                heard.add("done " + result);
            }

            @Override
            public void failed(Throwable error) {
                heard.add("failed " + error.getMessage());
            }

            @Override
            public void cancelled() {
                heard.add("cancelled");
            }
        };
    }

    private void assertAllClosed() {
        for (DatabaseSession s : opened) assertThrows(Exception.class, () -> s.execute("select 1", 1), "closed after the job");
    }

    /** One session per connection, of its own; the result, then closed. */
    @Test
    void runsOnItsOwnSessionsAndClosesThem() throws Exception {
        ConnectionConfig a = sqlite("uat");
        ConnectionConfig b = sqlite("prod");
        Job.start(List.of(a, b), this::open, NOW, NOW, (sessions, job) -> {
            job.progress("reading");
            return sessions.get(0).execute("select v from t", 10).data().rows().get(0).get(0) + "+"
                    + sessions.get(1).execute("select v from t", 10).data().rows().get(0).get(0);
        }, listener());
        assertEquals(List.of("progress reading", "done uat+prod"), heard);
        assertEquals(2, opened.size());
        assertAllClosed();
    }

    @Test
    void reportsAFailureAndStillCloses() throws Exception {
        Job.start(List.of(sqlite("uat")), this::open, NOW, NOW, (sessions, job) -> sessions.get(0).execute("select nope from t", 1).toString(),
                listener());
        assertEquals(1, heard.size());
        assertTrue(heard.get(0).startsWith("failed"), heard.toString());
        assertAllClosed();
    }

    /** Cancelled: the listener hears "cancelled", not the error the stopped work ended with. */
    @Test
    void cancelledIsNotAFailure() throws Exception {
        Job.start(List.of(sqlite("uat")), this::open, NOW, NOW, (sessions, job) -> {
            job.cancel();
            job.progress("not shown after cancel");
            job.checkCancelled();
            return "never";
        }, listener());
        assertEquals(List.of("cancelled"), heard);
        assertAllClosed();
    }

    /** The second connection cannot open: the first one, already open, is closed again. */
    @Test
    void closesWhatOpenedWhenAConnectionFails() throws Exception {
        ConnectionConfig ok = sqlite("uat");
        ConnectionConfig broken = new ConnectionConfig();
        broken.setName("broken");
        broken.setType(DbType.SQLITE);
        broken.setDatabase(tmp.resolve("missing/none.db").toString()); // read-only SQLite never creates a file
        Job.start(List.of(ok, broken), this::open, NOW, NOW, (sessions, job) -> "never", listener());
        assertEquals(1, heard.size());
        assertTrue(heard.get(0).startsWith("failed"), heard.toString());
        assertEquals(1, opened.size());
        assertAllClosed();
    }
}
