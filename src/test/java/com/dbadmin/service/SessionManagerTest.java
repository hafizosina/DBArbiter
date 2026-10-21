package com.dbadmin.service;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    @TempDir
    Path dir;

    /** Work handed to the background, run when the test says so. */
    private final List<Runnable> background = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Set<ConnectionConfig> current = new HashSet<>();
    /** Every session the opener opened. */
    private final List<DatabaseSession> opened = new ArrayList<>();
    private Exception openFails;

    private final SessionManager manager = new SessionManager(config -> {
        if (openFails != null) throw openFails;
        DatabaseSession s = DatabaseSession.open(config);
        opened.add(s);
        return s;
    }, background::add, Runnable::run, current::contains, new SessionManager.Listener() {
        @Override
        public void connecting(ConnectionConfig c) {
            events.add("connecting " + c.getName());
        }

        @Override
        public void opened(ConnectionConfig c, DatabaseSession s) {
            events.add("opened " + c.getName());
        }

        @Override
        public void failed(ConnectionConfig c, Throwable error) {
            events.add("failed " + c.getName());
        }
    });

    @AfterEach
    void closeSessions() {
        manager.closeAll();
    }

    /** A read-only connection to an empty SQLite file. */
    private ConnectionConfig sqlite(String name) throws IOException {
        ConnectionConfig c = new ConnectionConfig();
        c.setName(name);
        c.setType(DbType.SQLITE);
        c.setDatabase(Files.createFile(dir.resolve(name + ".db")).toString());
        current.add(c);
        return c;
    }

    private void runBackground() {
        while (!background.isEmpty()) background.remove(0).run();
    }

    private static boolean isClosed(DatabaseSession s) {
        try {
            s.submit(() -> null);
            return false;
        } catch (RejectedExecutionException e) {
            return true;
        }
    }

    @Test
    void callersWhileOpeningShareOneSession() throws IOException {
        ConnectionConfig c = sqlite("a");
        List<DatabaseSession> got = new ArrayList<>();
        manager.withSession(c, got::add, e -> events.add("error"));
        manager.withSession(c, got::add, e -> events.add("error"));
        assertFalse(manager.isOpen(c.getId())); // still opening
        runBackground();

        assertEquals(1, opened.size());
        assertEquals(2, got.size());
        assertSame(got.get(0), got.get(1));
        assertTrue(manager.isOpen(c.getId()));
        assertEquals(List.of("connecting a", "opened a"), events);
    }

    @Test
    void anOpenSessionIsHandedOutRightAway() throws IOException {
        ConnectionConfig c = sqlite("a");
        manager.withSession(c, s -> { }, e -> { });
        runBackground();
        List<DatabaseSession> got = new ArrayList<>();
        manager.withSession(c, got::add, e -> { });
        assertEquals(1, got.size());
        assertTrue(background.isEmpty());
        assertEquals(1, opened.size());
        assertSame(got.get(0), manager.get(c.getId()).orElseThrow());
    }

    @Test
    void aFailureReachesEveryCallerAndCanBeRetried() throws IOException {
        ConnectionConfig c = sqlite("a");
        openFails = new IOException("no route to host");
        List<Throwable> errors = new ArrayList<>();
        manager.withSession(c, s -> { }, errors::add);
        manager.withSession(c, s -> { }, errors::add);
        runBackground();

        assertEquals(2, errors.size());
        assertSame(openFails, errors.get(0));
        assertFalse(manager.isOpen(c.getId()));
        assertEquals(List.of("connecting a", "failed a"), events);

        openFails = null;
        manager.withSession(c, s -> { }, errors::add);
        runBackground();
        assertTrue(manager.isOpen(c.getId()));
    }

    @Test
    void aConnectionChangedWhileOpeningIsClosedAgain() throws IOException {
        ConnectionConfig c = sqlite("a");
        List<Throwable> errors = new ArrayList<>();
        manager.withSession(c, s -> { }, errors::add);
        current.remove(c); // edited or deleted meanwhile
        runBackground();

        assertFalse(manager.isOpen(c.getId()));
        assertTrue(isClosed(opened.get(0)));
        assertInstanceOf(IllegalStateException.class, errors.get(0));
        assertEquals(List.of("connecting a"), events);
    }

    @Test
    void closeForgetsAndClosesTheSession() throws IOException {
        ConnectionConfig a = sqlite("a");
        ConnectionConfig b = sqlite("b");
        manager.withSession(a, s -> { }, e -> { });
        manager.withSession(b, s -> { }, e -> { });
        runBackground();
        assertEquals(2, manager.all().size());

        DatabaseSession closed = manager.close(a.getId()).orElseThrow();
        assertTrue(isClosed(closed));
        assertFalse(manager.isOpen(a.getId()));
        assertTrue(manager.close(a.getId()).isEmpty());

        DatabaseSession other = manager.get(b.getId()).orElseThrow();
        manager.closeAll();
        assertTrue(isClosed(other));
        assertTrue(manager.all().isEmpty());
    }

    @Test
    void sessionsAreSeparatePerConnection() throws IOException {
        ConnectionConfig a = sqlite("a");
        ConnectionConfig b = sqlite("b");
        List<DatabaseSession> got = new ArrayList<>();
        manager.withSession(a, got::add, e -> { });
        manager.withSession(b, got::add, e -> { });
        runBackground();
        assertEquals(2, opened.size());
        assertNotSame(got.get(0), got.get(1));
    }
}
