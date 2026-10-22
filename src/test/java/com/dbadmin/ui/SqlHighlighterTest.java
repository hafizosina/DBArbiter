package com.dbadmin.ui;

import com.dbadmin.model.DbType;
import org.fxmisc.richtext.model.StyleSpan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlHighlighterTest {

    /** Each styled token as "style:text", skipping unstyled gaps. */
    private static List<String> tokens(String sql) {
        List<String> out = new ArrayList<>();
        int pos = 0;
        for (StyleSpan<Collection<String>> span : SqlHighlighter.highlight(sql, DbType.POSTGRESQL)) {
            if (!span.getStyle().isEmpty()) {
                out.add(span.getStyle().iterator().next().substring(4) + ":" + sql.substring(pos, pos + span.getLength()));
            }
            pos += span.getLength();
        }
        assertEquals(sql.length(), pos);
        return out;
    }

    @Test
    void dollarQuotedBodyIsHighlightedAsSql() {
        assertEquals(List.of("keyword:DO", "dollar:$$", "keyword:BEGIN", "keyword:UPDATE", "keyword:SET",
                        "string:'x'", "keyword:END", "dollar:$$", "keyword:select"),
                tokens("DO $$ BEGIN UPDATE t SET a = 'x'; END $$; select"));
    }

    @Test
    void taggedAndUnterminatedDollarQuotes() {
        assertEquals(List.of("dollar:$body$", "keyword:select", "number:1", "dollar:$body$"),
                tokens("$body$ select 1 $body$"));
        assertEquals(List.of("dollar:$$", "keyword:from"), tokens("$$ from"));
    }

    @Test
    void declaredNamesAndRoutineArgsAreVariables() {
        assertEquals(List.of("keyword:DO", "dollar:$$", "keyword:DECLARE", "variable:v_rows", "type:INT", "keyword:BEGIN",
                        "variable:v_rows", "number:1", "keyword:END", "dollar:$$", "keyword:select"),
                tokens("DO $$ DECLARE v_rows INT; BEGIN v_rows := 1; END $$; select v_rows"));
        assertEquals(List.of("keyword:create", "keyword:function", "keyword:in", "type:int", "type:text",
                        "keyword:returns", "type:int", "keyword:as", "dollar:$f$", "keyword:select",
                        "variable:p_id", "dollar:$f$"),
                tokens("create function f(in p_id int, text) returns int as $f$ select p_id $f$"));
    }
}
