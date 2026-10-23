package com.dbadmin.service.compare;

import com.dbadmin.service.compare.DataCompare.RowStatus;
import com.dbadmin.service.compare.SchemaCompare.Status;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * A compare report as one self-contained HTML page (no external files), for people who do not use the app: summary
 * first, then only the differences. A small inline script lets the reader sort any table by a column, and search and
 * filter the main one (by status, verdict, kind); without scripts the page still reads and prints in full.
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
            .tools { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; margin: 8px 0; font-size: 12.5px; }
            .tools input[type=search] { font: inherit; padding: 4px 8px; border: 1px solid #d1d9e0; border-radius: 6px; min-width: 240px; }
            .tools .group { display: flex; flex-wrap: wrap; gap: 4px 10px; }
            .tools label { white-space: nowrap; cursor: pointer; }
            .tools .count { color: #59636e; }
            th.sortable { cursor: pointer; user-select: none; }
            th.sortable:hover { background: #eaeef2; }
            th[data-sort=asc]::after { content: " ▲"; } th[data-sort=desc]::after { content: " ▼"; }
            @media print { body { padding: 0; } table.rows th { position: static; } .tools { display: none; } }
            """;

    /**
     * Sorting (every table.rows: click a header) and, for a table with data-filters="status verdict", a search box and
     * a checkbox per value of each data-* attribute of its rows, with how many rows have it.
     */
    private static final String SCRIPT = """
            (function () {
              function text(cell) { return cell ? (cell.textContent || '').trim() : ''; }
              function num(s) { var t = s.replace(/,/g, ''); return /^-?[0-9]+([.][0-9]+)?$/.test(t) ? parseFloat(t) : null; }
              var list = function (x) { return Array.prototype.slice.call(x); };
              list(document.querySelectorAll('table.rows')).forEach(function (table) {
                var body = table.tBodies[0];
                if (!body || !table.tHead) return;
                var heads = list(table.tHead.rows[0].cells);
                heads.forEach(function (th, col) {
                  th.classList.add('sortable');
                  th.title = 'Sort by this column';
                  th.addEventListener('click', function () {
                    var asc = th.getAttribute('data-sort') !== 'asc';
                    heads.forEach(function (h) { h.removeAttribute('data-sort'); });
                    th.setAttribute('data-sort', asc ? 'asc' : 'desc');
                    list(body.rows).sort(function (a, b) {
                      var x = text(a.cells[col]), y = text(b.cells[col]), nx = num(x), ny = num(y);
                      var c = nx !== null && ny !== null ? nx - ny : x.localeCompare(y, undefined, {numeric: true, sensitivity: 'base'});
                      return asc ? c : -c;
                    }).forEach(function (r) { body.appendChild(r); });
                  });
                });
                var filters = (table.getAttribute('data-filters') || '').split(' ').filter(Boolean);
                if (!filters.length) return;
                var rows = list(body.rows);
                var bar = document.createElement('div');
                bar.className = 'tools';
                var search = document.createElement('input');
                search.type = 'search';
                search.placeholder = 'Search the rows…';
                bar.appendChild(search);
                var boxes = {};
                filters.forEach(function (f) {
                  boxes[f] = {};
                  var group = document.createElement('span');
                  group.className = 'group';
                  var values = [];
                  rows.forEach(function (r) { var v = r.getAttribute('data-' + f); if (values.indexOf(v) < 0) values.push(v); });
                  values.forEach(function (v) {
                    var n = rows.filter(function (r) { return r.getAttribute('data-' + f) === v; }).length;
                    var label = document.createElement('label');
                    var box = document.createElement('input');
                    box.type = 'checkbox';
                    box.checked = true;
                    box.addEventListener('change', apply);
                    boxes[f][v] = box;
                    label.appendChild(box);
                    label.appendChild(document.createTextNode(' ' + v + ' (' + n + ')'));
                    group.appendChild(label);
                  });
                  bar.appendChild(group);
                });
                var count = document.createElement('span');
                count.className = 'count';
                bar.appendChild(count);
                table.parentNode.insertBefore(bar, table);
                function apply() {
                  var q = search.value.trim().toLowerCase(), shown = 0;
                  rows.forEach(function (r) {
                    var ok = (!q || r.textContent.toLowerCase().indexOf(q) >= 0)
                        && filters.every(function (f) { return boxes[f][r.getAttribute('data-' + f)].checked; });
                    r.style.display = ok ? '' : 'none';
                    if (ok) shown++;
                  });
                  count.textContent = shown + ' of ' + rows.length + ' rows';
                }
                search.addEventListener('input', apply);
                apply();
              });
            })();
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
            h.append("<table class=rows data-filters=\"status verdict\"><thead><tr><th>Status</th><th>Verdict</th>");
            for (String c : r.shownColumns) h.append("<th>").append(esc(c)).append("</th>");
            h.append("<th>Note</th></tr></thead><tbody>");
            for (DataCompareReport.Row row : listed) {
                String verdict = row.verdict != null ? row.verdict.name() : Verdict.allowed(row.status).isEmpty() ? "none needed" : "undecided";
                h.append("<tr data-status=\"").append(statusText(row.status, r.source.label(), r.target.label()))
                        .append("\" data-verdict=\"").append(esc(verdict)).append("\"><td><span class=\"st s-")
                        .append(row.status.name().toLowerCase()).append("\">")
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
            h.append("</tbody></table>");
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
            h.append("<table class=rows data-filters=\"status kind\"><thead><tr><th>Name</th><th>Kind</th><th>Status</th>"
                    + "<th>Differences</th></tr></thead><tbody>");
            for (SchemaCompare.TableDiff t : differing) {
                h.append("<tr data-status=\"").append(esc(tableStatusText(t.status(), r))).append("\" data-kind=\"")
                        .append(esc(t.kind().toLowerCase())).append("\"><td><a href=\"#t-").append(esc(t.name())).append("\">").append(esc(t.name())).append("</a></td><td>")
                        .append(esc(t.kind().toLowerCase())).append("</td><td>").append(tableStatus(t.status(), r))
                        .append("</td><td>").append(t.status() == Status.CHANGED ? t.differences() : "").append("</td></tr>");
            }
            h.append("</tbody></table>");
            for (SchemaCompare.TableDiff t : differing) {
                h.append("<h2 id=\"t-").append(esc(t.name())).append("\">").append(esc(t.name())).append(" · ")
                        .append(tableStatus(t.status(), r)).append("</h2>");
                if (t.items().isEmpty()) {
                    h.append("<p>The ").append(esc(t.kind().toLowerCase())).append(" exists only in ")
                            .append(esc(t.status() == Status.ONLY_SOURCE ? r.sourceLabel : r.targetLabel)).append(".</p>");
                    continue;
                }
                h.append("<table class=rows><thead><tr><th>Category</th><th>Name</th><th>Status</th><th>")
                        .append(esc(r.sourceLabel)).append("</th><th>").append(esc(r.targetLabel)).append("</th></tr></thead><tbody>");
                for (SchemaCompare.Item i : t.items()) {
                    if (i.status() == Status.SAME) continue;
                    boolean sql = i.category().equals("Definition");
                    h.append("<tr><td>").append(esc(i.category())).append("</td><td>").append(esc(i.name())).append("</td><td>")
                            .append(tableStatus(i.status(), r)).append("</td><td>").append(sql ? "<pre>" + esc(i.source()) + "</pre>" : esc(i.source()))
                            .append("</td><td>").append(sql ? "<pre>" + esc(i.target()) + "</pre>" : esc(i.target())).append("</td></tr>");
                }
                h.append("</tbody></table>");
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
        h.append("<footer>Generated by DBArbiter").append(appVersion == null ? "" : " " + esc(appVersion)).append("</footer>")
                .append("<script>").append(SCRIPT).append("</script></body></html>");
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
        return "<span class=\"st s-" + css + "\">" + esc(tableStatusText(s, r)) + "</span>";
    }

    private static String tableStatusText(Status s, SchemaCompareReport r) {
        return switch (s) {
            case SAME -> "Same";
            case CHANGED -> "Changed";
            case ONLY_SOURCE -> "Only in " + r.sourceLabel;
            case ONLY_TARGET -> "Only in " + r.targetLabel;
        };
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
