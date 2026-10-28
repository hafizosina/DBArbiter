package com.dbadmin.service.scan;

import com.dbadmin.model.TableRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScanFileTest {

    @TempDir
    Path tmp;

    @Test
    void aScanIsReadBackAsSaved() throws Exception {
        TableRef t = new TableRef(null, "public", "m_banner", "TABLE");
        ScanColumn url = new ScanColumn("image_url", "varchar", ScanColumn.Kind.TEXT);
        ScanFile scan = new ScanFile();
        scan.connectionId = "c1";
        scan.connectionName = "UAT";
        scan.values = "Facebook.png\nTwitter.png";
        scan.tables = List.of("public.m_banner");
        scan.columns = List.of("public.m_banner|image_url");
        scan.request = new ScanRequest(List.of("Facebook.png", "Twitter.png"), false, true, List.of(new ScanRequest.Target(t, List.of(url))), 60);
        scan.results = List.of(new TableResult(t, List.of(new ScanFinding(t, url, 3, List.of(3L, 0L), List.of(1L, 0L))), null),
                new TableResult(new TableRef(null, "public", "big", "TABLE"), List.of(), "timed out after 60 s"));
        Path file = tmp.resolve("banner" + ScanFile.EXTENSION);
        ScanFile.save(file, scan);

        ScanFile read = ScanFile.open(file);
        assertEquals(scan.values, read.values);
        assertEquals(scan.columns, read.columns);
        assertEquals(scan.request, read.request);
        assertEquals(scan.results, read.results);
    }

    @Test
    void aNewerFormatOrAnotherFileIsRefused() throws Exception {
        Path newer = tmp.resolve("newer.dbscan");
        Files.writeString(newer, "{\"kind\": \"scan\", \"version\": 99}");
        IOException e = assertThrows(IOException.class, () -> ScanFile.open(newer));
        assertEquals(true, e.getMessage().contains("newer version"));
        Path compare = tmp.resolve("x.dbscan");
        Files.writeString(compare, "{\"kind\": \"data\"}");
        assertThrows(IOException.class, () -> ScanFile.open(compare));
    }
}
