package com.dbadmin.service.transfer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * CSV as spreadsheets read and write it (RFC 4180): fields separated by a delimiter, a field quoted
 * ("...", with "" for a quote) when it holds the delimiter, a quote or a line break.
 */
public final class Csv {

    private Csv() {
    }

    /** Writes rows as CSV; NULL is an empty field, binary data is hex (\x...). */
    public static RowSink writer(Writer out, char delimiter, boolean header) {
        return new RowSink() {
            @Override
            public void begin(List<String> columns, List<String> types) throws IOException {
                if (header) line(new ArrayList<>(columns));
            }

            @Override
            public void row(List<Object> values) throws IOException {
                line(values);
            }

            @Override
            public void end() throws IOException {
                out.flush();
            }

            private void line(List<?> values) throws IOException {
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) out.write(delimiter);
                    out.write(field(text(values.get(i)), delimiter));
                }
                out.write("\r\n");
            }
        };
    }

    static String text(Object v) {
        if (v == null) return "";
        if (v instanceof byte[] b) return "\\x" + HexFormat.of().formatHex(b);
        if (v instanceof java.math.BigDecimal d) return d.toPlainString();
        return v.toString();
    }

    static String field(String s, char delimiter) {
        boolean quote = s.indexOf(delimiter) >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return quote ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    /** All records of a CSV text (a leading byte order mark is ignored); quoted fields may span lines. */
    public static List<List<String>> read(Reader in, char delimiter) throws IOException {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false; // something was read for the current record
        int c = in.read();
        if (c == '﻿') c = in.read();
        while (c != -1) {
            if (quoted) {
                if (c == '"') {
                    int next = in.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        c = next;
                        continue;
                    }
                } else {
                    field.append((char) c);
                }
            } else if (c == '"' && field.length() == 0) {
                quoted = true;
                any = true;
            } else if (c == delimiter) {
                record.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r') {
                    int next = in.read();
                    if (next != '\n') {
                        endRecord(records, record, field, any);
                        record = new ArrayList<>();
                        any = false;
                        c = next;
                        continue;
                    }
                }
                endRecord(records, record, field, any);
                record = new ArrayList<>();
                any = false;
            } else {
                field.append((char) c);
                any = true;
            }
            c = in.read();
        }
        endRecord(records, record, field, any || field.length() > 0);
        return records;
    }

    private static void endRecord(List<List<String>> records, List<String> record, StringBuilder field, boolean any) {
        if (!any && record.isEmpty() && field.length() == 0) return; // a blank line
        record.add(field.toString());
        field.setLength(0);
        records.add(record);
    }

    /** The likely delimiter of a CSV text: the one of , ; tab | found most often in its first line. */
    public static char guessDelimiter(String firstLine) {
        char best = ',';
        long most = 0;
        for (char d : new char[]{',', ';', '\t', '|'}) {
            long n = firstLine.chars().filter(ch -> ch == d).count();
            if (n > most) {
                most = n;
                best = d;
            }
        }
        return best;
    }
}
