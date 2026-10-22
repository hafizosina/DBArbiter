package com.dbadmin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DdlTest {

    private static final String OWN = "brankas_mobile.m_product_variant_id_seq";

    @Test
    void nextvalOfTheColumnsOwnSequenceIsSerial() {
        assertEquals("bigserial", DatabaseSession.serialType("bigint", "nextval('brankas_mobile.m_product_variant_id_seq'::regclass)", OWN));
        assertEquals("serial", DatabaseSession.serialType("integer", "nextval('m_product_variant_id_seq'::regclass)", OWN));
        assertEquals("smallserial", DatabaseSession.serialType("smallint", "nextval('m_product_variant_id_seq'::regclass)", OWN));
    }

    @Test
    void otherDefaultsStayAsTheyAre() {
        assertNull(DatabaseSession.serialType("bigint", "nextval('brankas_mobile.shared_seq'::regclass)", OWN)); // not its own
        assertNull(DatabaseSession.serialType("bigint", "nextval('brankas_mobile.shared_seq'::regclass)", ""));  // owns none
        assertNull(DatabaseSession.serialType("bigint", "0", OWN));
        assertNull(DatabaseSession.serialType("numeric", "nextval('m_product_variant_id_seq'::regclass)", OWN));
    }
}
