package com.dbadmin.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SQL text differences between the databases, kept in DbType. */
class DbTypeTest {

    @Test
    void quotesNamesTheWayEachDatabaseReadsThem() {
        assertEquals("orders", DbType.POSTGRESQL.quoteIfNeeded("orders"));
        assertEquals("\"Orders\"", DbType.POSTGRESQL.quoteIfNeeded("Orders"), "PostgreSQL folds bare names to lower case");
        assertEquals("Orders", DbType.SQLITE.quoteIfNeeded("Orders"));
        assertEquals("Orders", DbType.MYSQL.quoteIfNeeded("Orders"));
        assertEquals("`order items`", DbType.MYSQL.quoteIfNeeded("order items"));
        assertEquals("\"a\"\"b\"", DbType.POSTGRESQL.quote("a\"b"));
        assertEquals("`a``b`", DbType.MYSQL.quote("a`b"));
        assertEquals("\"mpt.code\"", DbType.SQLITE.quote("mpt.code"));
    }

    @Test
    void literalsAndSyntax() {
        assertEquals("TRUE", DbType.POSTGRESQL.booleanLiteral(true));
        assertEquals("0", DbType.SQLITE.booleanLiteral(false));
        assertEquals("'\\xff00'::bytea", DbType.POSTGRESQL.binaryLiteral("ff00"));
        assertEquals("X'ff00'", DbType.MYSQL.binaryLiteral("ff00"));
        assertTrue(DbType.MYSQL.backslashEscapes() && DbType.MYSQL.hashComments() && DbType.MYSQL.qualifiesByCatalog());
        assertFalse(DbType.POSTGRESQL.backslashEscapes() || DbType.POSTGRESQL.hashComments());
        assertTrue(DbType.POSTGRESQL.dollarQuotes() && DbType.POSTGRESQL.streamsInTransactionOnly());
        assertEquals(Integer.MIN_VALUE, DbType.MYSQL.streamingFetchSize(500));
        assertEquals(500, DbType.SQLITE.streamingFetchSize(500));
        assertFalse(DbType.SQLITE.hasSystemSchemas());
    }
}
