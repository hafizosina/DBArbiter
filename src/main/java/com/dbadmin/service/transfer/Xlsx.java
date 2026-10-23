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

    /**
     * A drop-down list on some cells: {@code cells} as Excel writes ranges ("B2:B9 B12"), {@code options} the values
     * offered; anything else typed is refused with {@code error}.
     */
    public record DropDown(String cells, List<String> options, String error) {
    }

    public static RowSink writer(OutputStream out, String sheetName) {
        return writer(out, sheetName, List.of(), List.of());
    }

    /**
     * As {@link #writer(OutputStream, String)}, with column widths (in characters, by column; fewer than the columns is
     * fine) and drop-down lists.
     */
    public static RowSink writer(OutputStream out, String sheetName, List<Integer> widths, List<DropDown> dropDowns) {
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
                        + "</sheetView></sheetViews>" + cols(widths) + "<sheetData>");
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
                sheet.write("</sheetData>" + dataValidations(dropDowns) + "</worksheet>");
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

    private static String cols(List<Integer> widths) {
        if (widths.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<cols>");
        for (int i = 0; i < widths.size(); i++) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(widths.get(i))
                    .append("\" customWidth=\"1\"/>");
        }
        return sb.append("</cols>").toString();
    }

    private static String dataValidations(List<DropDown> dropDowns) {
        List<DropDown> used = dropDowns.stream().filter(d -> !d.cells().isBlank() && !d.options().isEmpty()).toList();
        if (used.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<dataValidations count=\"").append(used.size()).append("\">");
        for (DropDown d : used) {
            sb.append("<dataValidation type=\"list\" allowBlank=\"1\" showErrorMessage=\"1\" errorTitle=\"Not allowed\" error=\"")
                    .append(xml(d.error())).append("\" sqref=\"").append(d.cells()).append("\"><formula1>&quot;")
                    .append(xml(String.join(",", d.options()))).append("&quot;</formula1></dataValidation>");
        }
        return sb.append("</dataValidations>").toString();
    }

    // ---- reading ----

    /**
     * The first sheet of a workbook, as text: rows by row number (row 1 at index 0, rows Excel left out are empty), each
     * cell by column (A at 0, empty cells ""). Shared strings, inline strings, numbers, booleans and formula results;
     * works on files Excel or LibreOffice saved again.
     */
    public static List<List<String>> read(java.io.InputStream in) throws IOException {
        java.util.Map<String, byte[]> parts = new java.util.HashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName().startsWith("/") ? e.getName().substring(1) : e.getName();
                if (name.endsWith(".xml") || name.endsWith(".rels")) parts.put(name, zip.readAllBytes());
            }
        }
        if (!parts.containsKey("xl/workbook.xml")) throw new IOException("Not an Excel workbook (.xlsx).");
        String sheetPath = firstSheet(parts);
        byte[] sheet = parts.get(sheetPath);
        if (sheet == null) throw new IOException("The workbook's first sheet is missing (" + sheetPath + ").");
        List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
        return cells(sheet, shared);
    }

    /** The path of the first sheet listed in the workbook, through its relationship. */
    private static String firstSheet(java.util.Map<String, byte[]> parts) throws IOException {
        String id = null;
        for (var x : elements(parts.get("xl/workbook.xml"), "sheet")) {
            id = x.get("r:id");
            if (id == null) id = x.entrySet().stream().filter(a -> a.getKey().endsWith(":id")).map(java.util.Map.Entry::getValue).findFirst().orElse(null);
            break;
        }
        byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
        if (id != null && rels != null) {
            for (var r : elements(rels, "Relationship")) {
                if (id.equals(r.get("Id"))) {
                    String target = r.get("Target");
                    return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                }
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    /** The attributes (by qualified name) of every element with this local name. */
    private static List<java.util.Map<String, String>> elements(byte[] xmlBytes, String localName) throws IOException {
        List<java.util.Map<String, String>> found = new java.util.ArrayList<>();
        try {
            var r = reader(xmlBytes);
            while (r.hasNext()) {
                if (r.next() == javax.xml.stream.XMLStreamConstants.START_ELEMENT && r.getLocalName().equals(localName)) {
                    java.util.Map<String, String> a = new java.util.HashMap<>();
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        String prefix = r.getAttributePrefix(i);
                        a.put((prefix == null || prefix.isEmpty() ? "" : prefix + ":") + r.getAttributeLocalName(i), r.getAttributeValue(i));
                    }
                    found.add(a);
                }
            }
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new IOException("The workbook could not be read: " + e.getMessage(), e);
        }
        return found;
    }

    private static List<String> sharedStrings(byte[] xmlBytes) throws IOException {
        List<String> strings = new java.util.ArrayList<>();
        try {
            var r = reader(xmlBytes);
            StringBuilder current = null;
            boolean inText = false;
            boolean inPhonetic = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "si" -> current = new StringBuilder();
                        case "rPh" -> inPhonetic = true;
                        case "t" -> inText = !inPhonetic;
                        default -> { }
                    }
                } else if (ev == javax.xml.stream.XMLStreamConstants.CHARACTERS && inText && current != null) {
                    current.append(r.getText());
                } else if (ev == javax.xml.stream.XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "t" -> inText = false;
                        case "rPh" -> inPhonetic = false;
                        case "si" -> {
                            strings.add(current == null ? "" : current.toString());
                            current = null;
                        }
                        default -> { }
                    }
                }
            }
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new IOException("The workbook's text could not be read: " + e.getMessage(), e);
        }
        return strings;
    }

    private static List<List<String>> cells(byte[] xmlBytes, List<String> shared) throws IOException {
        List<List<String>> rows = new java.util.ArrayList<>();
        try {
            var r = reader(xmlBytes);
            List<String> row = null;
            String type = null;
            int column = -1;
            int nextColumn = 0;
            StringBuilder value = null;
            boolean inValue = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> {
                            String n = r.getAttributeValue(null, "r");
                            int number = n == null ? rows.size() + 1 : Integer.parseInt(n);
                            while (rows.size() < number - 1) rows.add(new java.util.ArrayList<>());
                            row = new java.util.ArrayList<>();
                            rows.add(row);
                            nextColumn = 0;
                        }
                        case "c" -> {
                            String ref = r.getAttributeValue(null, "r");
                            column = ref == null ? nextColumn : columnIndex(ref);
                            type = r.getAttributeValue(null, "t");
                            value = new StringBuilder();
                        }
                        case "v", "t" -> inValue = value != null;
                        default -> { }
                    }
                } else if (ev == javax.xml.stream.XMLStreamConstants.CHARACTERS && inValue) {
                    value.append(r.getText());
                } else if (ev == javax.xml.stream.XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "v", "t" -> inValue = false;
                        case "c" -> {
                            if (row != null && value != null) {
                                String text = value.toString();
                                if ("s".equals(type)) {
                                    int i = text.isBlank() ? -1 : Integer.parseInt(text.strip());
                                    text = i >= 0 && i < shared.size() ? shared.get(i) : "";
                                } else if ("b".equals(type)) {
                                    text = "1".equals(text.strip()) ? "TRUE" : "FALSE";
                                } else if (type == null || "n".equals(type)) {
                                    text = plainNumber(text);
                                }
                                while (row.size() < column) row.add("");
                                if (row.size() == column) row.add(text);
                                else row.set(column, text);
                            }
                            nextColumn = column + 1;
                            value = null;
                        }
                        default -> { }
                    }
                }
            }
        } catch (javax.xml.stream.XMLStreamException | NumberFormatException e) {
            throw new IOException("The sheet could not be read: " + e.getMessage(), e);
        }
        return rows;
    }

    /** "1.5E3" and "12.0" as Excel shows them: 1500, 12. */
    private static String plainNumber(String text) {
        try {
            return new BigDecimal(text.strip()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /** "B12" -> 1. */
    static int columnIndex(String ref) {
        int n = 0;
        for (char ch : ref.toCharArray()) {
            if (!Character.isLetter(ch)) break;
            n = n * 26 + (Character.toUpperCase(ch) - 'A' + 1);
        }
        return n - 1;
    }

    private static javax.xml.stream.XMLStreamReader reader(byte[] xmlBytes) throws javax.xml.stream.XMLStreamException {
        javax.xml.stream.XMLInputFactory f = javax.xml.stream.XMLInputFactory.newInstance();
        f.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false); // no external entities from a file someone sent
        f.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new java.io.ByteArrayInputStream(xmlBytes));
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
