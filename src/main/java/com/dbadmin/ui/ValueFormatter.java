package com.dbadmin.ui;

import com.dbadmin.model.BlobValue;
import com.dbadmin.model.ColumnType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects and pretty-prints cell values for the value panel. */
final class ValueFormatter {

    enum Format {
        TEXT("Text"), JSON("JSON"), XML("XML"), HTML("HTML"), HEX("Hex");

        private final String label;

        Format(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Pattern FIRST_TAG = Pattern.compile("^\\s*(?:<!--(?s:.*?)-->\\s*)*<([a-zA-Z][a-zA-Z0-9]*)");
    private static final Set<String> HTML_TAGS = Set.of(
            "html", "head", "body", "div", "span", "p", "a", "img", "br", "hr", "table", "thead", "tbody", "tr",
            "td", "th", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "b", "i", "u", "em", "strong",
            "small", "section", "article", "header", "footer", "nav", "main", "form", "input", "button",
            "label", "pre", "code", "blockquote", "style", "font", "center", "meta", "title");

    private ValueFormatter() {
    }

    /** Starts with an HTML doctype or with a tag that is an HTML element. */
    static boolean looksLikeHtml(String s) {
        String t = s.stripLeading();
        if (t.regionMatches(true, 0, "<!doctype html", 0, 14)) return true;
        Matcher m = FIRST_TAG.matcher(t);
        return m.find() && HTML_TAGS.contains(m.group(1).toLowerCase(Locale.ROOT));
    }

    /** The best format for a value: binary as hex, valid JSON/XML pretty-printed, else plain text. */
    static Format detect(Object value, ColumnType type) {
        if (value instanceof BlobValue) return Format.HEX;
        if (value == null) return Format.TEXT;
        String typeName = type == null || type.name() == null ? "" : type.name().toLowerCase(Locale.ROOT);
        String s = value.toString().strip();
        if (typeName.startsWith("json") || s.startsWith("{") || s.startsWith("[")) {
            if (isJson(s)) return Format.JSON;
        }
        if (!typeName.equals("xml") && looksLikeHtml(s)) return Format.HTML;
        if (typeName.equals("xml") || s.startsWith("<") && s.endsWith(">")) {
            try {
                formatXml(s);
                return Format.XML;
            } catch (Exception ignored) {
                // not XML
            }
        }
        return Format.TEXT;
    }

    /** Formats the value; throws if it cannot be shown in that format (e.g. invalid JSON). */
    static String format(Object value, Format format) throws Exception {
        if (value == null) return "";
        return switch (format) {
            case TEXT -> value instanceof BlobValue b ? new String(b.head(), StandardCharsets.UTF_8) : value.toString();
            case JSON -> JSON.writeValueAsString(JSON.readTree(value.toString()));
            case XML -> formatXml(value.toString());
            case HTML -> formatHtml(value.toString());
            case HEX -> hexDump(value instanceof BlobValue b ? b.head() : value.toString().getBytes(StandardCharsets.UTF_8));
        };
    }

    private static boolean isJson(String s) {
        try {
            JSON.readTree(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Indents HTML that is also well-formed XML (XHTML-style); other HTML is shown as it is. */
    private static String formatHtml(String html) {
        if (html.stripLeading().regionMatches(true, 0, "<!doctype", 0, 9)) return html;
        try {
            return formatXml(html);
        } catch (Exception e) {
            return html;
        }
    }

    private static String formatXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // values come from the database: never resolve DTDs or external entities
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        builder.setErrorHandler(null); // report problems as exceptions only, not on stderr
        Document doc = builder.parse(new InputSource(new StringReader(xml)));
        removeWhitespaceText(doc.getDocumentElement());

        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, xml.strip().startsWith("<?xml") ? "no" : "yes");
        StringWriter out = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString().strip();
    }

    /** Drops existing indentation so the transformer can indent consistently. */
    private static void removeWhitespaceText(Node node) {
        for (Node child = node.getFirstChild(); child != null; ) {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) node.removeChild(child);
            else removeWhitespaceText(child);
            child = next;
        }
    }

    /** Classic hex dump: offset, 16 bytes in hex, printable ASCII. */
    static String hexDump(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 4 + 64);
        for (int offset = 0; offset < bytes.length; offset += 16) {
            sb.append(String.format("%08x  ", offset));
            StringBuilder ascii = new StringBuilder(16);
            for (int i = 0; i < 16; i++) {
                if (offset + i < bytes.length) {
                    int b = bytes[offset + i] & 0xff;
                    sb.append(String.format("%02x ", b));
                    ascii.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
                if (i == 7) sb.append(' ');
            }
            sb.append(" |").append(ascii).append("|\n");
        }
        return sb.toString();
    }
}
