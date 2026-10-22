package com.dbadmin.model;

import com.dbadmin.model.AppSettings.Theme;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppSettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** The file as saved when no status color was changed: it used to be rejected, losing the theme. */
    @Test
    void readsSettingsWithoutStatusColors() throws Exception {
        AppSettings s = MAPPER.readValue("{\"theme\": \"DARK\", \"statusColors\": { }}", AppSettings.class);
        assertEquals(Theme.DARK, s.getTheme());
        assertEquals(AppSettings.defaultStatusColor(Theme.DARK, "CHANGED"), s.statusColor(Theme.DARK, "CHANGED"));
    }

    @Test
    void savedColorsComeBack() throws Exception {
        AppSettings s = new AppSettings();
        s.setTheme(Theme.DARK);
        s.setStatusColor(Theme.DARK, "CHANGED", "#123456");
        AppSettings back = MAPPER.readValue(MAPPER.writeValueAsString(s), AppSettings.class);
        assertEquals(Theme.DARK, back.getTheme());
        assertEquals("#123456", back.statusColor(Theme.DARK, "CHANGED"));
    }
}
