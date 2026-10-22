package com.dbadmin.service.compare;

import com.dbadmin.service.compare.DataCompare.RowStatus;
import com.dbadmin.service.compare.SchemaCompare.Status;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * A compare report as one self-contained HTML page (no scripts, no external files), for people who
 * do not use the app: summary first, then only the differences. Prints well.
 */
public final class CompareHtml {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    private static final String STYLE = """
            body { font: 14px/1.45 -apple-system, "Segoe UI", Roboto, "Noto Sans", Arial, sans-serif; color: #1f2328;
                   margin: 0; padding: 28px 32px; background: #fff; }
            h1 { font-size: 22px; margin: 0 0 4px; }
            h2 { font-size: 16px; margin: 28px 0 8px; }
            .sub { color: #59636e; margin-bottom: 18px; }
            table.meta td { padding: 2px 16px 2px 0; vertical-align: top; }
            table.meta td:first-child { color: #59636e; white-space: nowrap; }
            .cards { display: flex; flex-wrap: wrap; gap: 10px; margin: 16px 0 4px; }
            .card { border: 1px solid #d1d9e0; border-radius: 8px; padding: 10px 14px; min-width: 120px; }
            .card b { display: block; font-size: 22px; }
            .card span { color: #59636e; font-size: 12px; }
            .warn { background: #fff8e1; border: 1px solid #f0c36d; border-radius: 6px; padding: 8px 12px; margin: 10px 0; }
            table.rows { border-collapse: collapse; width: 100%; font-size: 12.5px; }
            table.rows th { text-align: left; background: #f6f8fa; border-bottom: 2px solid #d1d9e0; padding: 6px 8px; position: sticky; top: 0; }
            table.rows td { border-bottom: 1px solid #e6eaef; padding: 5px 8px; vertical-align: top; word-break: break-word; }
            .st { font-weight: 600; white-space: nowrap; border-radius: 4px; padding: 1px 6px; }
            .s-changed { background: #fff1c2; } .s-only_source { background: #d7f5dd; } .s-only_target { background: #ffd8d3; }
            .s-duplicate_key { background: #e9d8fd; } .s-match, .s-same { background: #eef1f4; }
            .diff { background: #fff1c2; }
            .old { color: #9a3412; text-decoration: line-through; } .new { color: #166534; font-weight: 600; }
            .null { color: #8c959f; font-style: italic; }
            .verdict { font-weight: 600; white-space: nowrap; } .none { color: #8c959f; }
            pre { white-space: pre-wrap; margin: 0; font: 12px/1.4 ui-monospace, Consolas, monospace; }
            footer { margin-top: 28px; color: #8c959f; font-size: 12px; }
            @media print { body { padding: 0; } table.rows th { position: static; } }
            """;

    private CompareHtml() {
    }

    public static String data(DataCompareReport r) {
        String table = r.source.table();
        StringBuilder h = page("Data Compare – " + table);
        h.append("<h1>Data Compare: ").append(esc(table)).append("</h1>");
        h.append("<div class=sub>").append(esc(r.source.label())).append(" (source) → ").append(esc(r.target.label()))
                .append(" (target) · ").append(esc(when(r.created))).append("</div>");

        h.append("<table class=meta>");
        meta(h, "Source", r.source.connection() + " · " + r.source.schema() + "." + r.source.table());
        meta(h, "Target", r.target.connection() + " · " + r.target.schema() + "." + r.target.table());
        meta(h, "Key", String.join(", ", r.keyColumns));
        if (r.joins != null && !r.joins.isEmpty()) {
            meta(h, "Key joins", r.source.table() + " " + r.mainAlias + " " + String.join(" ",
                    r.joins.stream().map(KeyJoins::describe).toList()));
        }
        meta(h, "Compared columns", r.comparedColumns.isEmpty() ? "none (only which keys exist)" : String.join(", ", r.comparedColumns));
        if (r.oneToMany) meta(h, "Rows per key", "several (one-to-many): matched by the compared columns");
        if (r.where != null && !r.where.isBlank()) meta(h, "Where", r.where);
        meta(h, "Rows", r.sourceRows + " in source · " + r.targetRows + " in target");
        h.append("</table>");
        if (r.truncated) {
            h.append("<div class=warn>Only the first ").append(r.rowsPerSide)
                    .append(" rows of each side (in key order) were compared; the counts may be incomplete.</div>");
        }
        if (r.duplicateKeys > 0) {
            h.append("<div class=warn>").append(r.duplicateKeys).append(" key value(s) match more than one row: ")
                    .append(esc(String.join(", ", r.keyColumns))).append(" does not identify rows uniquely.</div>");
        }

        h.append("<div class=cards>");
        card(h, r.same, "identical");
        card(h, r.changed, "changed");
        card(h, r.onlySource, "only in " + r.source.label());
        card(h, r.onlyTarget, "only in " + r.target.label());
        if (r.duplicateKeys > 0) card(h, r.duplicateKeys, "duplicate keys");
        h.append("</div>");
        h.append("<div class=cards>");
        for (Verdict v : Verdict.values()) card(h, r.count(v), v.name());
        card(h, r.undecided(), "not decided yet");
        h.append("</div>");

        List<DataCompareReport.Row> listed = r.rows.stream().filter(x -> x.status != RowStatus.MATCH).toList();
        h.append("<h2>Differences (").append(listed.size()).append(")</h2>");
        if (listed.isEmpty()) {
            h.append("<p>No differences.</p>");
        } else {
            h.append("<table class=rows><tr><th>Status</th><th>Verdict</th>");
            for (String c : r.shownColumns) h.append("<th>").append(esc(c)).append("</th>");
            h.append("<th>Note</th></tr>");
            for (DataCompareReport.Row row : listed) {
                h.append("<tr><td><span class=\"st s-").append(row.status.name().toLowerCase()).append("\">")
                        .append(statusText(row.status, r.source.label(), r.target.label())).append("</span></td>");
                h.append("<td class=verdict>").append(row.verdict == null
                        ? (Verdict.allowed(row.status).isEmpty() ? "" : "<span class=none>–</span>")
                        : "<span class=st style=\"background:" + row.verdict.color() + "\">" + row.verdict.name() + "</span>").append("</td>");
                for (int c = 0; c < r.shownColumns.size(); c++) {
                    String a = row.source == null ? null : row.source.get(c);
                    String b = row.target == null ? null : row.target.get(c);
                    boolean compared = r.comparedColumns.contains(r.shownColumns.get(c));
                    if (row.source != null && row.target != null && compared && !Objects.equals(a, b)) {
                        h.append("<td class=diff><span class=old>").append(value(a)).append("</span><br><span class=new>")
                                .append(value(b)).append("</span></td>");
                    } else {
                        h.append("<td>").append(value(row.source != null ? a : b)).append("</td>");
                    }
                }
                h.append("<td>").append(esc(row.status == RowStatus.CHANGED ? "" : row.note)).append("</td></tr>");
            }
            h.append("</table>");
        }
        if (r.same > 0) h.append("<p class=sub>").append(r.same).append(" identical rows are not listed.</p>");
        return footer(h, r.appVersion);
    }

    public static String table(SchemaCompareReport r) {
        StringBuilder h = page("Table Compare – " + r.sourceLabel + " vs " + r.targetLabel);
        h.append("<h1>Table Compare</h1>");
        h.append("<div class=sub>").append(esc(r.sourceLabel)).append(" (source) → ").append(esc(r.targetLabel))
                .append(" (target) · ").append(esc(when(r.created))).append("</div>");
        h.append("<div class=cards>");
        card(h, r.count(Status.SAME), "identical");
        card(h, r.count(Status.CHANGED), "changed");
        card(h, r.count(Status.ONLY_SOURCE), "only in " + r.sourceLabel);
        card(h, r.count(Status.ONLY_TARGET), "only in " + r.targetLabel);
        h.append("</div>");

        List<SchemaCompare.TableDiff> differing = r.tables.stream().filter(t -> t.status() != Status.SAME).toList();
        h.append("<h2>Tables and views with differences (").append(differing.size()).append(")</h2>");
        if (differing.isEmpty()) {
            h.append("<p>No differences.</p>");
        } else {
            h.append("<table class=rows><tr><th>Name</th><th>Kind</th><th>Status</th><th>Differences</th></tr>");
            for (SchemaCompare.TableDiff t : differing) {
                h.append("<tr><td><a href=\"#t-").append(esc(t.name())).append("\">").append(esc(t.name())).append("</a></td><td>")
                        .append(esc(t.kind().toLowerCase())).append("</td><td>").append(tableStatus(t.status(), r))
                        .append("</td><td>").append(t.status() == Status.CHANGED ? t.differences() : "").append("</td></tr>");
            }
            h.append("</table>");
            for (SchemaCompare.TableDiff t : differing) {
                h.append("<h2 id=\"t-").append(esc(t.name())).append("\">").append(esc(t.name())).append(" · ")
                        .append(tableStatus(t.status(), r)).append("</h2>");
                if (t.items().isEmpty()) {
                    h.append("<p>The ").append(esc(t.kind().toLowerCase())).append(" exists only in ")
                            .append(esc(t.status() == Status.ONLY_SOURCE ? r.sourceLabel : r.targetLabel)).append(".</p>");
                    continue;
                }
                h.append("<table class=rows><tr><th>Category</th><th>Name</th><th>Status</th><th>")
                        .append(esc(r.sourceLabel)).append("</th><th>").append(esc(r.targetLabel)).append("</th></tr>");
                for (SchemaCompare.Item i : t.items()) {
                    if (i.status() == Status.SAME) continue;
                    boolean sql = i.category().equals("Definition");
                    h.append("<tr><td>").append(esc(i.category())).append("</td><td>").append(esc(i.name())).append("</td><td>")
                            .append(tableStatus(i.status(), r)).append("</td><td>").append(sql ? "<pre>" + esc(i.source()) + "</pre>" : esc(i.source()))
                            .append("</td><td>").append(sql ? "<pre>" + esc(i.target()) + "</pre>" : esc(i.target())).append("</td></tr>");
                }
                h.append("</table>");
            }
        }
        long same = r.count(Status.SAME);
        if (same > 0) h.append("<p class=sub>").append(same).append(" identical tables and views are not listed.</p>");
        return footer(h, r.appVersion);
    }

    private static StringBuilder page(String title) {
        return new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>").append(esc(title))
                .append("</title><style>").append(STYLE).append("</style></head><body>");
    }

    private static String footer(StringBuilder h, String appVersion) {
        h.append("<footer>Generated by DBAdmin").append(appVersion == null ? "" : " " + esc(appVersion)).append("</footer></body></html>");
        return h.toString();
    }

    private static void meta(StringBuilder h, String name, String value) {
        h.append("<tr><td>").append(esc(name)).append("</td><td>").append(esc(value)).append("</td></tr>");
    }

    private static void card(StringBuilder h, long n, String label) {
        h.append("<div class=card><b>").append(n).append("</b><span>").append(esc(label)).append("</span></div>");
    }

    private static String statusText(RowStatus s, String source, String target) {
        return esc(switch (s) {
            case MATCH -> "Identical";
            case CHANGED -> "Changed";
            case ONLY_SOURCE -> "Only in " + source;
            case ONLY_TARGET -> "Only in " + target;
            case DUPLICATE_KEY -> "Duplicate key";
        });
    }

    private static String tableStatus(Status s, SchemaCompareReport r) {
        String css = switch (s) {
            case SAME -> "same";
            case CHANGED -> "changed";
            case ONLY_SOURCE -> "only_source";
            case ONLY_TARGET -> "only_target";
        };
        String text = switch (s) {
            case SAME -> "Same";
            case CHANGED -> "Changed";
            case ONLY_SOURCE -> "Only in " + r.sourceLabel;
            case ONLY_TARGET -> "Only in " + r.targetLabel;
        };
        return "<span class=\"st s-" + css + "\">" + esc(text) + "</span>";
    }

    private static String value(String v) {
        return v == null ? "<span class=null>NULL</span>" : esc(v);
    }

    private static String when(String created) {
        try {
            return WHEN.format(LocalDateTime.parse(created));
        } catch (RuntimeException e) {
            return created == null ? "" : created;
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
