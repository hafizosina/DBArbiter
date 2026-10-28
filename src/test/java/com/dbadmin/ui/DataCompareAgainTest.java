package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.service.DatabaseSession;
import com.dbadmin.service.compare.DataCompare.RowStatus;
import com.dbadmin.service.compare.DataCompareReport;
import com.dbadmin.service.compare.Verdict;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/** Data Compare on two SQLite files: compared again, the verdicts stay on their rows, with a warning where needed. */
class DataCompareAgainTest {

    @TempDir
    Path tmp;

    private final List<DatabaseSession> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(DatabaseSession::close);
    }

    private ConnectionConfig sqlite(String name, String... inserts) throws Exception {
        Path file = tmp.resolve(name + ".db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("create table params (code text primary key, value text)");
            for (String i : inserts) st.execute(i);
        }
        ConnectionConfig c = new ConnectionConfig();
        c.setName(name);
        c.setType(DbType.SQLITE);
        c.setDatabase(file.toString());
        c.setReadOnly(false);
        return c;
    }

    private static void sql(ConnectionConfig c, String statement) throws Exception {
        try (var con = DriverManager.getConnection("jdbc:sqlite:" + c.getDatabase()); var st = con.createStatement()) {
            st.execute(statement);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (Fx.call(condition::getAsBoolean)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out");
    }

    private static DataCompareReport.Row row(DataCompareReport r, String code) {
        return r.rows.stream().filter(x -> x.key.equals(code)).findFirst().orElseThrow();
    }

    /** Opens each connection when asked (the sessions are closed after the test). */
    private SessionProvider sessions() {
        return new SessionProvider() {
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
    }

    @Test
    void verdictsStayWhenComparingAgain() throws Exception {
        ConnectionConfig uat = sqlite("uat", "insert into params values ('a', '1'), ('b', '2'), ('c', '3')");
        ConnectionConfig prod = sqlite("prod", "insert into params values ('a', '1'), ('b', '9')");
        SessionProvider sessions = sessions();
        // a settings file: compares as soon as both sides are ready
        DataCompareReport settings = new DataCompareReport();
        settings.settingsOnly = true;
        settings.source = new DataCompareReport.Side("uat", "main", "params", "uat");
        settings.target = new DataCompareReport.Side("prod", "main", "params", "prod");
        settings.keyColumns = List.of("code");
        settings.comparedColumns = List.of("value");
        settings.shownColumns = List.of("code", "value");
        settings.rowsPerSide = 1000;
        DataCompareTab[] tab = new DataCompareTab[1];
        Fx.run(() -> {
            tab[0] = new DataCompareTab(FXCollections.observableArrayList(uat, prod), sessions, null);
            new javafx.scene.control.TabPane(tab[0]);
            tab[0].showSaved(settings, tmp.resolve("params.dbcompare"));
        });
        await(() -> tab[0].report() != null && !tab[0].report().settingsOnly);
        Fx.run(() -> {
            DataCompareReport r = tab[0].report();
            assertEquals(RowStatus.CHANGED, row(r, "b").status);
            row(r, "b").verdict = Verdict.UPDATE;
            row(r, "c").verdict = Verdict.INSERT;
        });

        sql(uat, "update params set value = '5' where code = 'b'"); // b's values change: UPDATE kept, to check
        sql(prod, "insert into params values ('c', 'x')");          // c is now CHANGED: INSERT no longer fits
        DataCompareReport first = Fx.call(() -> tab[0].report());
        Fx.run(() -> tab[0].compareNow());
        await(() -> tab[0].report() != first);
        Fx.run(() -> {
            DataCompareReport r = tab[0].report();
            assertEquals(Verdict.UPDATE, row(r, "b").verdict);
            assertEquals("values changed since UPDATE was set: check it", row(r, "b").warning);
            assertNull(row(r, "c").verdict);
            assertEquals("was INSERT when ONLY_SOURCE; now CHANGED: decide again", row(r, "c").warning);
            assertNull(row(r, "a").warning);
        });
    }

    /** Compared columns where no row differs are hidden by default; the box shows them again. */
    @Test
    void columnsWithoutDifferencesAreHidden() throws Exception {
        ConnectionConfig uat = sqlite("uat2", "alter table params add column note text", "alter table params add column price int",
                "insert into params values ('a', '1', 'x', 10), ('b', '2', 'y', 20), ('c', '3', 'z', 30)");
        ConnectionConfig prod = sqlite("prod2", "alter table params add column note text", "alter table params add column price int",
                "insert into params values ('a', '1', 'x', 10), ('b', '2', 'y', 25)");
        DataCompareReport settings = new DataCompareReport();
        settings.settingsOnly = true;
        settings.source = new DataCompareReport.Side("uat2", "main", "params", "uat2");
        settings.target = new DataCompareReport.Side("prod2", "main", "params", "prod2");
        settings.keyColumns = List.of("code");
        settings.comparedColumns = List.of("value", "note", "price");
        settings.shownColumns = List.of("code", "value", "note", "price");
        settings.rowsPerSide = 1000;
        DataCompareTab[] tab = new DataCompareTab[1];
        Fx.run(() -> {
            tab[0] = new DataCompareTab(FXCollections.observableArrayList(uat, prod), sessions(), null);
            new javafx.scene.Scene(new javafx.scene.control.TabPane(tab[0]), 1200, 700); // laid out, so the report's parts can be found
            tab[0].showSaved(settings, tmp.resolve("params2.dbcompare"));
        });
        await(() -> tab[0].report() != null && !tab[0].report().settingsOnly);
        Fx.run(() -> {
            javafx.scene.Node content = tab[0].getContent();
            tab[0].getTabPane().applyCss();
            tab[0].getTabPane().layout();
            DataGrid grid = (DataGrid) content.lookupAll(".table-view").stream().filter(n -> n instanceof DataGrid).findFirst().orElseThrow();
            javafx.scene.control.CheckBox hide = (javafx.scene.control.CheckBox) content.lookupAll(".check-box").stream()
                    .filter(n -> ((javafx.scene.control.CheckBox) n).getText().startsWith("Hide")).findFirst().orElseThrow();
            assertEquals("Hide 2 unchanged columns", hide.getText());
            assertEquals(List.of("Status", "Verdict", "code", "price", "Note"), visible(grid));
            hide.setSelected(false);
            assertEquals(List.of("Status", "Verdict", "code", "value", "note", "price", "Note"), visible(grid));
        });
    }

    private static List<String> visible(DataGrid grid) {
        return grid.getColumns().stream().filter(javafx.scene.control.TableColumn::isVisible).map(c -> c.getText()).toList();
    }

}
