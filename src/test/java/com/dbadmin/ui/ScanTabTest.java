package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.service.DatabaseSession;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Scan tab on the real JavaFX thread against an SQLite file: pick, scan, findings and coverage. */
class ScanTabTest {

    @TempDir
    Path tmp;

    private final List<DatabaseSession> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(DatabaseSession::close);
    }

    /** Waits (off the FX thread) until the condition holds on the FX thread. */
    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (Fx.call(condition::getAsBoolean)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out");
    }

    @Test
    void scansTheTickedTablesAndColumnsAndListsTheRest() throws Exception {
        Path file = tmp.resolve("shop.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("create table template (id integer primary key, body text, title text, price real)");
            st.execute("insert into template (body, title, price) values ('see https://cdn/Facebook.png', 'facebook.png', 1)");
            st.execute("create table logs (id integer primary key, line text)");
            st.execute("insert into logs (line) values ('https://cdn/Facebook.png')");
        }
        ConnectionConfig config = new ConnectionConfig();
        config.setName("shop");
        config.setType(DbType.SQLITE);
        config.setDatabase(file.toString());
        SessionProvider sessions = new SessionProvider() {
            @Override
            public void withSession(ConnectionConfig c, java.util.function.Consumer<DatabaseSession> ok, java.util.function.Consumer<Throwable> err) {
                try {
                    DatabaseSession s = DatabaseSession.open(c);
                    opened.add(s);
                    ok.accept(s);
                } catch (Exception e) {
                    err.accept(e);
                }
            }

            @Override
            public Optional<DatabaseSession> openSession(ConnectionConfig c) {
                return Optional.empty();
            }

            @Override
            public void newScript(ConnectionConfig c, String content) {
            }
        };
        ScanTab[] tab = new ScanTab[1];
        Fx.run(() -> tab[0] = new ScanTab(FXCollections.observableArrayList(config), sessions, config));
        ScanTab t = tab[0];
        await(() -> t.columnList().items().size() == 3); // body, title, line (price is not text)

        Fx.run(() -> {
            t.valuesArea().setText("https://cdn/Facebook.png\n");
            t.columnList().items().stream().filter(c -> c.column().name().equals("title")).findFirst().orElseThrow().on().set(false);
            t.tableList().items().stream().filter(p -> p.item().table().name().equals("logs")).findFirst().orElseThrow().on().set(false);
            assertEquals(2, t.columnList().shown().size(), "the columns of an unticked table are hidden");
            t.scanButton().fire();
        });
        await(() -> t.scanButton().getText().equals("Scan") && !t.coverageGrid().getItems().isEmpty());
        Fx.run(() -> {
            List<List<Object>> found = t.findingsGrid().getItems();
            assertEquals(1, found.size());
            assertEquals(List.of("template", "body", "TEXT", 1L, 1L, 0L), List.of(
                    String.valueOf(found.get(0).get(0)).replace("main.", ""), found.get(0).get(1), found.get(0).get(2),
                    found.get(0).get(3), found.get(0).get(4), found.get(0).get(5)));
            List<String> why = t.coverageGrid().getItems().stream()
                    .map(r -> String.valueOf(r.get(0)).replace("main.", "") + "/" + r.get(1) + ": " + r.get(2)).toList();
            assertTrue(why.contains("template/title: left out by you"), why.toString());
            assertTrue(why.contains("logs/(table): left out by you"), why.toString());
            assertTrue(why.contains("template/price: not searched: REAL is not text"), why.toString());
        });
        Fx.run(() -> t.showRows(t.findingsGridFindings().get(0)));
        await(() -> !t.matchRowsView().shownValues().isEmpty());
        Fx.run(() -> assertEquals(List.of("see https://cdn/Facebook.png"), t.matchRowsView().shownValues()));
    }

    /** A saved scan opens again with its form (values, ticks) and findings, without scanning. */
    @Test
    void aSavedScanOpensWithItsTicksAndFindings() throws Exception {
        Path file = tmp.resolve("shop2.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("create table template (id integer primary key, body text, title text)");
            st.execute("insert into template (body, title) values ('see https://cdn/Facebook.png', 'x')");
            st.execute("create table logs (id integer primary key, line text)");
        }
        ConnectionConfig config = new ConnectionConfig();
        config.setName("shop2");
        config.setType(DbType.SQLITE);
        config.setDatabase(file.toString());
        SessionProvider sessions = new SessionProvider() {
            @Override
            public void withSession(ConnectionConfig c, java.util.function.Consumer<DatabaseSession> ok, java.util.function.Consumer<Throwable> err) {
                try {
                    DatabaseSession s = DatabaseSession.open(c);
                    opened.add(s);
                    ok.accept(s);
                } catch (Exception e) {
                    err.accept(e);
                }
            }

            @Override
            public Optional<DatabaseSession> openSession(ConnectionConfig c) {
                return Optional.empty();
            }

            @Override
            public void newScript(ConnectionConfig c, String content) {
            }
        };
        ScanTab[] tab = new ScanTab[1];
        Fx.run(() -> tab[0] = new ScanTab(FXCollections.observableArrayList(config), sessions, config));
        ScanTab t = tab[0];
        await(() -> t.columnList().items().size() == 3);
        Fx.run(() -> {
            t.valuesArea().setText("Facebook.png");
            t.columnList().items().stream().filter(c -> c.column().name().equals("title")).findFirst().orElseThrow().on().set(false);
            t.tableList().items().stream().filter(p -> p.item().table().name().equals("logs")).findFirst().orElseThrow().on().set(false);
            t.scanButton().fire();
        });
        await(() -> t.scanButton().getText().equals("Scan") && !t.coverageGrid().getItems().isEmpty());
        Path saved = tmp.resolve("facebook.dbscan");
        Fx.run(() -> {
            try {
                com.dbadmin.service.scan.ScanFile.save(saved, t.snapshot());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        List<List<Object>> found = Fx.call(() -> List.copyOf(t.findingsGrid().getItems()));

        ScanTab[] again = new ScanTab[1];
        Fx.run(() -> {
            again[0] = new ScanTab(FXCollections.observableArrayList(config), sessions, null);
            try {
                again[0].showSaved(com.dbadmin.service.scan.ScanFile.open(saved), saved);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        ScanTab r = again[0];
        await(() -> !r.findingsGrid().getItems().isEmpty());
        Fx.run(() -> {
            assertEquals("Facebook.png", r.valuesArea().getText());
            assertEquals(found, r.findingsGrid().getItems());
            assertFalse(r.tableList().items().stream().filter(p -> p.item().table().name().equals("logs")).findFirst().orElseThrow().on().get());
            assertFalse(r.columnList().items().stream().filter(c -> c.column().name().equals("title")).findFirst().orElseThrow().on().get());
            assertTrue(r.columnList().items().stream().filter(c -> c.column().name().equals("body")).findFirst().orElseThrow().on().get());
            assertEquals(saved, r.file());
            assertFalse(r.coverageGrid().getItems().isEmpty());
        });
    }

}
