package com.dbadmin.service.transfer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Rows as an Excel workbook (.xlsx) with one sheet, written as it streams (an .xlsx is a zip of XML
 * files). Numbers and booleans are real cells, everything else text; the header row is bold.
 */
public final class Xlsx {

    /** Excel's limits: rows per sheet, characters per cell. */
    public static final int MAX_ROWS = 1_048_576;
    private static final int MAX_CELL_CHARS = 32_767;

    private Xlsx() {
    }

    public static RowSink writer(OutputStream out, String sheetName) {
        return new RowSink() {
            private ZipOutputStream zip;
            private Writer sheet;
            private int rowNumber;

            @Override
            public void begin(List<String> columns, List<String> types) throws IOException {
                zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
                entry("[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                        <Default Extension="xml" ContentType="application/xml"/>\
                        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                        <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>\
                        </Types>""");
                entry("_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                        </Relationships>""");
                entry("xl/workbook.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                        <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets></workbook>""".formatted(xml(sheetTitle(sheetName))));
                entry("xl/_rels/workbook.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>\
                        </Relationships>""");
                entry("xl/styles.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                        <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>\
                        <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>\
                        <borders count="1"><border/></borders>\
                        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
                        <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
                        <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>\
                        <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>\
                        </styleSheet>""");
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
                sheet = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
                sheet.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                        + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                        + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
                        + "</sheetView></sheetViews><sheetData>");
                rowNumber = 0;
                writeRow(List.copyOf(columns), true);
            }

            @Override
            public void row(List<Object> values) throws IOException {
                if (rowNumber >= MAX_ROWS) throw new IOException("Excel holds at most " + MAX_ROWS + " rows per sheet; export to CSV instead.");
                writeRow(values, false);
            }

            @Override
            public void end() throws IOException {
                sheet.write("</sheetData></worksheet>");
                sheet.flush();
                zip.closeEntry();
                zip.finish();
            }

            private void writeRow(List<?> values, boolean header) throws IOException {
                rowNumber++;
                StringBuilder sb = new StringBuilder("<row r=\"").append(rowNumber).append("\">");
                for (int c = 0; c < values.size(); c++) {
                    Object v = values.get(c);
                    if (v == null) continue;
                    String ref = column(c) + rowNumber;
                    String style = header ? " s=\"1\"" : "";
                    if (!header && v instanceof Number n && isFinite(n)) {
                        sb.append("<c r=\"").append(ref).append("\"><v>").append(number(n)).append("</v></c>");
                    } else if (!header && v instanceof Boolean b) {
                        sb.append("<c r=\"").append(ref).append("\" t=\"b\"><v>").append(b ? 1 : 0).append("</v></c>");
                    } else {
                        String text = v instanceof byte[] bytes ? "\\x" + HexFormat.of().formatHex(bytes) : v.toString();
                        if (text.length() > MAX_CELL_CHARS) text = text.substring(0, MAX_CELL_CHARS);
                        sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style)
                                .append("><is><t xml:space=\"preserve\">").append(xml(text)).append("</t></is></c>");
                    }
                }
                sheet.write(sb.append("</row>").toString());
            }

            private void entry(String name, String content) throws IOException {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(content.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        };
    }

    /** 0 -> A, 25 -> Z, 26 -> AA. */
    static String column(int index) {
        StringBuilder sb = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) sb.insert(0, (char) ('A' + (n - 1) % 26));
        return sb.toString();
    }

    private static boolean isFinite(Number n) {
        return !(n instanceof Double d && !Double.isFinite(d)) && !(n instanceof Float f && !Float.isFinite(f));
    }

    private static String number(Number n) {
        return n instanceof BigDecimal d ? d.toPlainString() : n.toString();
    }

    /** Sheet names: at most 31 characters, none of : \ / ? * [ ]. */
    private static String sheetTitle(String name) {
        String s = name == null || name.isBlank() ? "Sheet1" : name.replaceAll("[:\\\\/?*\\[\\]]", "_");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    /** Escapes XML and drops characters XML cannot hold (control characters other than tab and line breaks). */
    static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') sb.append(ch);
                }
            }
        }
        return sb.toString();
    }
}
