package com.dbadmin.service;

import com.dbadmin.model.DbType;
import com.dbadmin.service.CompletionContext.TableMention;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionContextTest {

    /** Analyzes {@code text} with the caret at '|' (removed). */
    private static CompletionContext at(String text) {
        return at(text, DbType.POSTGRESQL);
    }

    private static CompletionContext at(String text, DbType type) {
        int caret = text.indexOf('|');
        return CompletionContext.analyze(text.replace("|", ""), caret, type);
    }

    @Test
    void tableNameExpected() {
        CompletionContext c = at("select * from us|");
        assertEquals("us", c.prefix());
        assertEquals(14, c.start());
        assertTrue(c.tablePosition());
        assertTrue(c.aliasAllowed());
        assertEquals(List.of(), c.mentions()); // the word being typed is not a table yet
    }

    @Test
    void qualifiedColumnAfterAnAlias() {
        CompletionContext c = at("select * from shop.users u where u.na|");
        assertEquals("na", c.prefix());
        assertEquals("u", c.qualifier());
        assertFalse(c.tablePosition());
        assertEquals(List.of(new TableMention("shop", "users", "u")), c.mentions());
    }

    @Test
    void mentionsFromJoinsAndFromLists() {
        CompletionContext c = at("select | from a x, b as y join c z on z.id = x.id");
        assertEquals(List.of(new TableMention(null, "c", "z"), new TableMention(null, "a", "x"),
                new TableMention(null, "b", "y")), c.mentions());
    }

    @Test
    void nothingInsideStringsOrComments() {
        assertNull(at("select 'ab|c' from t"));
        assertNull(at("select 1 -- note|"));
        assertNull(at("select /* x| */ 1"));
        assertNull(at("select \"Col|"));
        assertNull(at("do $$ begin | end $$"));
        assertNotNull(at("select /* x */ |"));
        assertNotNull(at("select 'abc' |"));
        assertNull(at("select 1 # note|", DbType.MYSQL));
    }

    @Test
    void onlyTheStatementAtTheCaretCounts() {
        assertEquals(List.of(new TableMention(null, "b", "y")), at("select * from a x; select * from b y where |").mentions());
        assertEquals(List.of(new TableMention(null, "a", "x")), at("select * from a x where |; select * from b y").mentions());
    }

    @Test
    void aBlankLineEndsTheStatementAsWhenRunning() {
        assertEquals(List.of(new TableMention(null, "b", "y")),
                at("select * from a x where 1 = 1\n\nselect * from b y where |").mentions());
        assertEquals(List.of(new TableMention(null, "a", "x")),
                at("select * from a x where |\n\nselect * from b y").mentions());
        // inside parentheses it does not, like SqlSplitter
        assertEquals(List.of(new TableMention(null, "a", "x")),
                at("select * from (\n  select 1\n\n) q join a x on true where |").mentions());
    }
}
