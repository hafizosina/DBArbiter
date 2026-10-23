package com.dbadmin.ui;

import com.dbadmin.model.DbType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A script with a very long quoted value (e.g. a whole JSON dictionary in a generated UPDATE) is coloured without
 * running out of stack: a pattern that repeats once per character of a string overflowed on such text.
 */
class SqlHighlighterLongTextTest {

    /** Runs on a thread with a small stack, like the screen thread's room for one highlight. */
    private static Throwable onSmallStack(Runnable work) throws InterruptedException {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(null, () -> {
            try {
                work.run();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "small-stack", 256 * 1024);
        t.start();
        t.join();
        return failed.get();
    }

    @Test
    void aLongStringAndALongQuotedNameDoNotOverflow() throws Exception {
        String json = "{\n  \"general\": {\n" + "    \"btn_next\": \"Next\",\n".repeat(20_000) + "  }\n}";
        String sql = "UPDATE t SET \"value\" = '" + json + "' WHERE \"" + "x".repeat(50_000) + "\" = 'it''s';";
        assertNull(onSmallStack(() -> SqlHighlighter.highlight(sql, DbType.POSTGRESQL)));
        assertNull(onSmallStack(() -> SqlHighlighter.highlight(sql, DbType.MYSQL)));
        int[] total = {0};
        SqlHighlighter.highlight(sql, DbType.POSTGRESQL).forEach(span -> total[0] += span.getLength());
        assertEquals(sql.length(), total[0], "every character is covered once");
    }

    /** The value panel colours a long JSON value (a whole dictionary) without running out of stack either. */
    @Test
    void theValuePanelColoursALongJsonValue() throws Exception {
        String json = "{\n  \"text\": \"" + "a long sentence \\\"quoted\\\" ".repeat(2_000) + "x".repeat(200_000) + "\"\n}";
        assertNull(onSmallStack(() -> CellInspector.highlight(json, CellInspector.JSON_TOKENS)));
    }
}
