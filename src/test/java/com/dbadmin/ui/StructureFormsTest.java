package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.model.DbType;
import com.dbadmin.model.TableRef;
import com.dbadmin.service.ColumnSpec;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The structure forms on the real JavaFX thread: what SQL each makes as it is filled in (no database). */
class StructureFormsTest {

    private static final TableRef ORDERS = new TableRef("shop", "app", "orders", "TABLE");
    private static final List<String> COLUMNS = List.of("id", "code", "total", "shop_id");

    private static StructureEditing editing(DbType type) {
        ConnectionConfig c = new ConnectionConfig();
        c.setName("prod");
        c.setType(type);
        c.setReadOnly(false);
        return new StructureEditing(null, c, type == DbType.MYSQL ? new TableRef("shop", null, "orders", "TABLE") : ORDERS, () -> { });
    }

    private static <T extends Node> List<T> all(Node root, Class<T> kind) {
        List<T> found = new ArrayList<>();
        if (kind.isInstance(root)) found.add(kind.cast(root));
        if (root instanceof Parent p) p.getChildrenUnmodifiable().forEach(n -> found.addAll(all(n, kind)));
        return found;
    }

    /** The form's text fields in order (the type box's editor excluded). */
    private static List<TextField> fields(StructureDialog d) {
        return all(d.getDialogPane().getContent(), TextField.class).stream()
                .filter(f -> !(f.getParent() instanceof ComboBox)).toList();
    }

    @Test
    void anOpenedColumnChangesNothingUntilEdited() throws Exception {
        Fx.run(() -> {
            ColumnSpec code = new ColumnSpec("code", "character varying(20)", false, "'x'::character varying", "order code", "", "", false);
            StructureDialog d = editing(DbType.POSTGRESQL).columnForm(null, code);
            assertEquals(List.of(), d.current());
            assertEquals("Nothing changed yet.", d.problem());
            fields(d).get(0).setText("order_code");
            all(d.getDialogPane().getContent(), CheckBox.class).get(0).setSelected(false); // NOT NULL off
            assertEquals(List.of("ALTER TABLE \"app\".\"orders\" RENAME COLUMN \"code\" TO \"order_code\"",
                    "ALTER TABLE \"app\".\"orders\" ALTER COLUMN \"order_code\" DROP NOT NULL"), d.current());
        });
    }

    @Test
    void aNewColumnNeedsANameAndAType() throws Exception {
        Fx.run(() -> {
            StructureDialog d = editing(DbType.POSTGRESQL).columnForm(null, null);
            assertEquals("Enter the column.", d.problem());
            fields(d).get(0).setText("note");
            @SuppressWarnings("unchecked")
            ComboBox<String> type = all(d.getDialogPane().getContent(), ComboBox.class).get(0);
            type.getEditor().setText("text");
            assertEquals(List.of("ALTER TABLE \"app\".\"orders\" ADD COLUMN \"note\" text"), d.current());
        });
    }

    @Test
    void constraintAndIndexNamesFollowTheFormUntilTyped() throws Exception {
        Fx.run(() -> {
            StructureDialog uq = editing(DbType.POSTGRESQL).constraintForm(null, COLUMNS, null);
            List<TextField> f = fields(uq);
            TextField name = f.get(0);
            f.get(1).setText("code, shop_id");
            assertEquals("uq_orders_code_shop_id", name.getText());
            assertEquals(List.of("ALTER TABLE \"app\".\"orders\" ADD CONSTRAINT \"uq_orders_code_shop_id\" UNIQUE (\"code\", \"shop_id\")"),
                    uq.current());
            name.setText("my_name");
            f.get(1).setText("code");
            assertEquals("my_name", name.getText(), "a typed name stays");

            StructureDialog ix = editing(DbType.MYSQL).indexForm(null, COLUMNS, null);
            fields(ix).get(0).setText("total desc");
            assertEquals(List.of("CREATE INDEX `ix_orders_total` ON `shop`.`orders` (`total` DESC)"), ix.current());
        });
    }

    /** Editing a constraint drops it and creates it again; unchanged, nothing. */
    @Test
    void editingAConstraintDropsAndAddsIt() throws Exception {
        Fx.run(() -> {
            var check = StructureEditing.constraintOf(List.of("ck_total", "CHECK", "total", "CHECK ((total >= (0)::numeric))"));
            assertEquals("(total >= (0)::numeric)", check.check());
            StructureDialog d = editing(DbType.POSTGRESQL).constraintForm(null, COLUMNS, check);
            assertEquals(List.of(), d.current());
            fields(d).get(2).setText("total > 0");
            assertEquals(List.of("ALTER TABLE \"app\".\"orders\" DROP CONSTRAINT \"ck_total\"",
                    "ALTER TABLE \"app\".\"orders\" ADD CONSTRAINT \"ck_total\" CHECK (total > 0)"), d.current());
        });
    }

    @Test
    void aForeignKeyIsOpenedWithItsTable() throws Exception {
        Fx.run(() -> {
            TableRef shops = new TableRef("shop", "app", "shops", "TABLE");
            StructureDialog d = editing(DbType.POSTGRESQL).foreignKeyForm(null, COLUMNS, List.of(ORDERS, shops),
                    List.of("fk_shop", "shop_id", "app.shops", "id", "NO ACTION", "CASCADE"));
            assertEquals(List.of(), d.current());
            @SuppressWarnings("unchecked")
            ComboBox<String> onUpdate = all(d.getDialogPane().getContent(), ComboBox.class).get(0);
            onUpdate.setValue("CASCADE");
            assertEquals(2, d.current().size());
            assertTrue(d.current().get(1).endsWith("REFERENCES \"app\".\"shops\" (\"id\") ON UPDATE CASCADE ON DELETE CASCADE"), d.current().get(1));
        });
    }

    @Test
    void sqliteSaysWhatItCannotDo() throws Exception {
        Fx.run(() -> {
            StructureDialog d = editing(DbType.SQLITE).constraintForm(null, COLUMNS, null);
            fields(d).get(1).setText("code");
            assertTrue(d.problem().startsWith("SQLite cannot add constraints"), d.problem());
            assertEquals(List.of(), d.current());
        });
    }
}
