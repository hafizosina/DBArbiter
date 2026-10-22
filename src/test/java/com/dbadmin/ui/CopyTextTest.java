package com.dbadmin.ui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopyTextTest {

    @Test
    void oneCellIsCopiedAsItIs() {
        assertEquals("EMAS-1G", CopyText.tsv(List.of(List.of("EMAS-1G")), null));
        assertEquals("line 1\nline 2", CopyText.tsv(List.of(List.of("line 1\nline 2")), null));
        assertEquals("", CopyText.tsv(List.of(Arrays.asList((Object) null)), null));
    }

    @Test
    void severalCellsAreTabSeparatedRows() {
        assertEquals("1\tEMAS-1G\n2\t\n", CopyText.tsv(List.of(List.of(1, "EMAS-1G"), Arrays.asList(2, null)), null));
        assertEquals("id\tproduct_code\n1\tA\n", CopyText.tsv(List.of(List.of(1, "A")), List.of("id", "product_code")));
    }

    @Test
    void valuesThatWouldBreakTheGridAreQuoted() {
        assertEquals("1\t\"a\tb\"\n2\t\"say \"\"hi\"\"\nthere\"\n",
                CopyText.tsv(List.of(List.of(1, "a\tb"), List.of(2, "say \"hi\"\nthere")), null));
    }
}
