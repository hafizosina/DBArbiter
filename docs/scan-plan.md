# Plan: Scan Data and Replace (todo #1)

_Written 2026-10-23._ A tool that finds a value anywhere in a database (every table, every text column), shows where
it is, and writes the SQL that replaces it, like the handwritten script for the social-media icon URLs in
`brankas_mobile.m_notification_template`. The tool **never changes data by itself**: it writes a script, which opens
in a script tab to review and run, where the connection's warning level (todo #4) applies.

## What the user does

1. **Tools → Scan Data…** opens a Scan tab for the selected connection.
2. **What to find:** one or more values, one per line (e.g. the five old URLs). Options:
   - match: *contains* (default) / *whole value*;
   - case: *ignore case* (default) / *exact*;
   - where: schemas (all but system schemas by default), then two lists side by side:
     - **Tables**, each with a checkbox, all ticked by default. **Big tables** (more than 1,000,000 rows, estimated
       from the catalog; a setting) carry a ⚠ with their size and are left unticked, by default and by *All*: they
       are scanned only when ticked by hand, so production is not read end to end by accident.
     - **Columns** of the ticked tables, as `table.column  type`, grouped by table: every text column (char,
       varchar, text, enum, json, jsonb) **ticked by default**; the user unticks the ones not to search (e.g. a
       large `audit_log.payload`). Unticking a table hides its columns; ticking it again brings them back as they
       were.
     - Both lists: a click ticks or unticks a row, **shift-click ticks or unticks every row from the last one clicked**
       (to the state the clicked row gets), *All* / *None*, a filter box, and a count ("182 of 190 columns").
       Unticked tables and columns appear in the coverage report as "left out by you".
3. **Scan.** Runs as a `Job` on a connection of its own, with progress ("table 34 of 210 · m_notification_template")
   and Cancel. Findings appear while it runs.
4. **Findings:** one row per table and column that holds a value: schema, table, column, type, rows matching,
   and per value how many rows hold it. Double-click shows the matching rows (first 100), the value with each match
   highlighted. Export findings as CSV.
5. **Replace…** on the findings ticked (default: all):
   - a "replace with" field next to each value found (old → new, as in the example's `old_x` / `new_x`);
   - **Also set** (audit columns): none by default, because every project names them differently, and some tables
     have none. The user adds rows of *column = value* (`modified_date = NOW()`, `modified_by = 'SYSTEM'`); the
     column box suggests the columns of the tables being changed. Each is set only in the tables that have that
     column, and the dialog lists the tables that do not. The list is saved **per connection** (a project is
     usually one set of connections), so it is filled in next time;
   - a preview: for a few matching rows of each column, the value before and after, as a diff (the compare's
     `DiffView`);
   - **Create script** opens the SQL in a new script tab bound to the connection. Nothing runs until the user runs it.

## The script it writes

PostgreSQL, the shape of the example: one `DO $$` block with the values as variables, one `UPDATE` per table that
covers every matching column of it with nested `REPLACE`, `WHERE … LIKE ANY (ARRAY[…])` so only rows that hold a
value are touched, and a count per table.

```sql
DO $$
DECLARE
    -- OLD
    old_1 TEXT := 'https://…/BannerEmail/Facebook/file/Facebook.png';
    old_2 TEXT := 'https://…/BannerEmail/Instagram/file/Instagram.png';
    -- NEW
    new_1 TEXT := 'https://…/Home_Social_Media/New Facebook/file/new_facebook.png';
    new_2 TEXT := 'https://…/Home_Social_Media/New_Instagram/file/new_instagram.png';
    v_modified_by TEXT := 'SYSTEM';
    v_rows INT;
BEGIN
    UPDATE brankas_mobile.m_notification_template
    SET message_content     = REPLACE(REPLACE(message_content, old_1, new_1), old_2, new_2),
        message_content_eng = REPLACE(REPLACE(message_content_eng, old_1, new_1), old_2, new_2),
        modified_date = NOW(),
        modified_by   = v_modified_by
    WHERE message_content     LIKE ANY (ARRAY['%' || old_1 || '%', '%' || old_2 || '%'])
       OR message_content_eng LIKE ANY (ARRAY['%' || old_1 || '%', '%' || old_2 || '%']);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RAISE NOTICE 'brankas_mobile.m_notification_template: updated % row(s)', v_rows;

    -- … one block per table …
END $$;
```

- **The audit lines** (`modified_date`, `modified_by`, `v_modified_by`) are there only because they were added under
  *Also set*; with nothing there, the `UPDATE` sets the replaced columns only.
- **Variable names** come from the user ("facebook" next to the value) or are numbered (`old_1`, `new_1`).
- **MySQL:** the same per table, with `SET @old_1 := '…';` variables, `OR` chains instead of `LIKE ANY`, and
  `SELECT ROW_COUNT()` after each `UPDATE` (MySQL has no anonymous blocks).
- **SQLite:** plain `UPDATE`s with the values written out.
- **Run again:** the `WHERE` matches only rows that still hold an old value, so a second run updates nothing
  (unless a new value contains an old one; see the checks below).
- **Typed characters match only themselves:** a `%`, `_` or `\` in a value is escaped in the LIKE pattern
  (`ESCAPE '\'`), and in MySQL string literals a backslash is doubled, so `https:\/\/…` finds exactly `https:\/\/…`.
- **Ignore case:** the scan finds values ignoring case by default (`ILIKE` on PostgreSQL, `LOWER(…) LIKE LOWER(…)`
  elsewhere), but `REPLACE` is exact, so the replace step matches exactly. The findings count both, and the Replace
  dialog says "N rows hold a value only in another case: not replaced" (the user can add that spelling as a value of
  its own).

**Checks before the script is made**, shown in the Replace dialog:
- one old value inside another (`…/X.png` and `…/X.png?v=2`): the longer one is replaced first, and the dialog says so;
- a new value that contains an old one: running the script twice would change it again; warn;
- a column too short for the new values (`varchar(100)` with longer URLs): warn with the longest result;
- views, generated columns and columns of a primary or foreign key are never updated.

## How it is built

The review (`architecture-review.md`) already says what a scan needs; most of it is in place:

| Needed | Status |
|---|---|
| Its own connection, progress, Cancel (G1, G2) | done: `Job` |
| Never hold all the rows (G3) | by design here: the scan asks the database to count (below), and only matching rows are read, 100 at a time, when asked |
| Exact column types (`varchar(100)` for the length check) | done: `ColumnSpec` (todo #6) |
| Per-database SQL | a `switch` per database, as `SchemaChange`; moved onto `Dialect` with it later (G5) |
| Tool menu, license gate, open a saved file by kind (G4) | still by hand: one more menu item and gate, as the compares |

**The scan query**, one per table, one pass over it:

```sql
-- PostgreSQL
SELECT count(*) FILTER (WHERE message_content LIKE '%…Facebook.png%') AS c1_1,
       count(*) FILTER (WHERE message_content LIKE '%…Instagram.png%') AS c1_2,
       count(*) FILTER (WHERE message_content_eng LIKE '%…Facebook.png%') AS c2_1, …
FROM brankas_mobile.m_notification_template
WHERE message_content LIKE ANY (…) OR message_content_eng LIKE ANY (…)
```

MySQL and SQLite use `SUM(CASE WHEN … THEN 1 ELSE 0 END)`. Text columns are scanned (char, varchar, text, enum),
and json / jsonb / MySQL JSON read as text (`col::text`).

**Values are searched and replaced exactly as typed** (decided 2026-10-23). The database holds what the app wrote, and
the table view shows exactly that; if an app stored a URL JSON-escaped (`https:\/\/s3…\/Facebook.png`) or
HTML-escaped (`&amp;`), the user types it that way, to find it and to replace it. The scan does not search other
spellings or decode anything, so what it finds is what the table view shows. (Searching escaped forms automatically was
considered and left out: it is only needed for data written by apps that escape, and it makes the tool harder to
predict.)

**Nothing is skipped silently.** A scan ends with its coverage: the tables and columns scanned, and every one that was
not, with the reason (a big table not ticked, a timeout, no permission, a column type that is not text: numbers,
binary). A finding of "not found" is only as complete as that list says.

**Values inside JSON** (a json / jsonb column, or a text column that holds JSON, like a template) are found and
replaced like any text, as typed:
- **jsonb / json / MySQL JSON:** `col = REPLACE(col::text, old, new)::jsonb` (or `::json`, `CAST(… AS JSON)`). The
  cast back makes the database refuse a result that is not valid JSON (e.g. a new value with a bare `"`), so the
  script stops and nothing is changed (the `DO $$` block is one transaction).
- **Text that holds JSON:** a plain `REPLACE`. There is no database check here, so the Replace preview marks a column
  "holds JSON" when its values parse as JSON, and warns when an after value no longer does.
- **Keys too:** a text replace inside JSON also changes a key spelled the same as the old value. Unlikely for URLs; the
  Replace dialog says so for JSON columns.

A statement timeout per table (setting, default 60 s) skips a table that
takes too long and lists it as "not scanned".

**New classes**

- `service/scan/ScanRequest`: the values, match mode, case, schemas, tables, size limit.
- `service/scan/DataScan`: lists tables and text columns, builds and runs the count query per table inside a `Job`,
  reports each table's findings as it goes. Tested on SQLite, and on throwaway PostgreSQL / MySQL as before.
- `service/scan/ReplaceSql`: findings + old → new pairs + audit columns → the script, per database. Pure, tested
  like `MigrationSql`; then run against a throwaway PostgreSQL: rows replaced, audit columns set, second run
  updates 0 rows.
- `ui/ScanTab`: the form, progress, findings grid, matching-rows panel.
- `ui/CheckList`: a list of checkbox rows with click, shift-click ranges, All / None, filter and count. The tables
  picker (`TablePickerDialog`) and the compare's `ColumnPicker` each have their own copy of the shift-click range
  today; the scan's two lists would make a third and fourth, so the scan starts by moving it into one tested piece
  that all of them use.
- `ui/ReplaceDialog`: pairs, audit columns, checks, preview, Create script (`SessionProvider.newScript`).

**Phases** (each one usable and committed on its own):

_Status:_ phase 1 done (2026-10-23): `service/scan` (`DataScan`, `ScanRequest`, `ScanTable`, `ScanColumn`,
`ScanFinding`, `TableResult`), row estimates per `Dialect`, `DatabaseSession.countRow`. Tried on PostgreSQL 16 and
MySQL 8.4 (text, varchar, char, enum, json, jsonb; ignore case and exact; whole value; `%`, `_`, `\` typed; a 3-million
row table: the timeout leaves it out after 1 s, Cancel stops it in milliseconds) and tested on SQLite.
Phase 2 done (2026-10-23): **Tools → Scan Data…** (`ScanTab`): connection, values, match / case, timeout, big-table
limit; Schemas, Tables and Columns lists (`CheckList`; big tables ⚠ and left out of All); Scan / Cancel with progress;
Findings (rows per value, "other case only") and Coverage (not scanned, left out, not text); Export CSV. Shift-click
lives in `RangeTicks`, now used by the tables picker, the compare's `ColumnPicker` and the scan's lists. Tried against
PostgreSQL with real mouse events (click, shift-click range tick and untick).
Phase 3 done (2026-10-23): double-click a finding → **Rows** tab (`MatchRowsView`): the first 100 rows by primary key,
one line around the first match with the match highlighted and the number of matches, the selected row's whole value
below with every match highlighted, and "values are JSON" when the column holds JSON (`DataScan.matchingRows`,
`matches`, `isJson`).

1. Scan engine and tests (no UI).
2. Scan tab: `CheckList` first (the tables picker and `ColumnPicker` moved onto it, their shift-click tried again),
   then the form with the table and column lists, progress, Cancel, findings, CSV export.
3. Matching rows panel with highlights.
4. Replace: generator, dialog, checks, preview, script tab. PostgreSQL first (the example), then MySQL and SQLite;
   plain text first, then JSON columns (the cast back, the preview's JSON check).
5. Save and reopen a scan (`.dbscan`, with a format version as the compare files have).

## Decided (2026-10-23)

1. Finding ignores case by default; replacing is exact.
2. JSON is found and replaced too, in json / jsonb columns and in text columns that hold JSON, as typed, with the
   checks above.
5. Values are searched and replaced exactly as typed: no escaped or encoded spellings are searched for the user.
3. Big tables are not skipped silently: a ⚠ with their size, and *Select all* leaves them out.
4. Audit columns are not assumed: none by default, the user adds *column = value* rows, saved per connection,
   applied only to tables that have the column.

**Related debt (new):** Generate SQL (`MigrationSql`) assumes the audit columns are `created_date`, `created_by`,
`modified_date` and `modified_by`, the names of one project. When the per-connection audit columns exist, Generate SQL
should use them too (insert: the "created" ones; update: the "modified" ones), so both tools agree.
