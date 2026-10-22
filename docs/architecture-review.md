# DBArbiter Architecture Review

_First review: 2026-10-21 at commit `70ef05a` (the app was called DBAdmin then). Updated after four rounds of follow-up fixes, and re-checked on 2026-10-22 at commit `fd7de02`: the open items below were confirmed in the code, and issues 12–14 cover what was added since (compare verdicts and migration SQL, licensing, the rename, CI releases, the update check)._

**Scope:** every service and model class was read in full, along with the main UI classes (MainWindow, DataView, CompareTab, ScriptRunner, TableEditing, Themes, Async). The fixes are covered by unit tests, and the UI changes were tried in the running app against a SQLite test database: cell editing, the "Not editable" messages, connecting and reconnecting, the log, fetching more rows, and both compare tabs.

**Size:** about 16.4k lines of Java 17 (9.5k at the first review) in a JavaFX 21 desktop app, with 183 JUnit tests in 24 test classes. It supports PostgreSQL, MySQL and SQLite over JDBC and builds into a fat jar, plus a launch4j `.exe` for Windows.

## Status

| # | Issue | Status |
|---|---|---|
| 1 | Edits mapped to table columns by result label | **Fixed**: only `*` or plain column lists are editable |
| 2 | No tests | **Mostly done**: 206 tests; every class without a screen, and screen pieces without dialogs (`ScreenPiecesTest`) |
| 3 | SQL scanner written three times | **Fixed**: one `SqlLexer`; splitting and autocomplete share statement boundaries |
| 4 | Fetching more rows re-runs the query | **Fixed**: the rest is read from one kept-open cursor on a connection of its own |
| 5 | `DatabaseSession` does too much | **Mostly fixed**: structure → `Metadata`, its catalog queries → one `Dialect` per database (session 750 lines) |
| 6 | `MainWindow` and `CompareTab` do too much | **Fixed**: sessions moved to a tested `SessionManager`; `CompareTab` split into two tabs |
| 7 | Global static state | Open |
| 8 | Saved files can be corrupted | **Mostly fixed**: atomic writes; script bindings relative to `Script/`; a password can be left unsaved. Saved passwords are still plain text |
| 9 | Errors swallowed silently | **Fixed**: non-editable results say why; errors are logged to `~/.dbadmin/logs` |
| 10 | Small tidy-ups | **Fixed** |
| 11 | `DataView` growing; side effects in `hasEdits()` / `show()` | **Fixed**: pending edits in a tested class; `hasEdits()` and `show()` change nothing. Open again: row add/delete made it 680 lines (see #11) |
| 12 | `DataCompareTab` is the largest class | **Done**: verdicts → `Verdicts`, columns → `ColumnPicker`, joins → `JoinEditor`, file state → `CompareFile` (956 lines left) |
| 13 | `MainWindow` growing again | **Partly fixed**: licensing → `LicenseGate`, tabs → `TabStrip` (954 lines) |
| 14 | Shipping to other people | **Partly fixed**: "Save password" option. OS keyring, license check, release build remain |

## How it is organised

```
model/            records and plain classes: ConnectionConfig, TableRef, TableData, StatementResult…
service/          JDBC and plain SQL-text logic, no JavaFX
  DatabaseSession     one connection, one worker thread, reconnect/retry, metadata for all 3 databases
  SqlLexer            comments, quotes, dollar quotes: the one place that knows where SQL code is
  SqlSplitter / NamedParams / CompletionContext / EditableQuery   built on SqlLexer
  ConnectionStore / SettingsStore / ScriptBindings                JSON files in ~/.dbadmin, written via JsonFiles
  AppLog              the log file in ~/.dbadmin/logs
  SessionManager      the open sessions, one per connection, opened in the background
  compare/            SchemaCompare, DataCompare (pure functions over snapshots); KeyJoins (keys from joined tables);
                      DataCompareReport / SchemaCompareReport + CompareReportFiles (.dbcompare files), CompareHtml;
                      Verdict; MigrationSql (verdicts -> a PostgreSQL migration script)
  UpdateCheck         asks GitHub for the newest release
license/          offline license keys (verify with the embedded public key) and the 1-month trial
licensetool/      the key generator: signer's machine only, never in the app jar
ui/               about 35 JavaFX classes: MainWindow (the SessionProvider, backed by SessionManager), tabs, grid, editor, dialogs
  PendingEdits        unsaved cell edits, without JavaFX
src/test/         JUnit 5 tests (mvn test)
```

## What works well

- **Dependencies only point one way: ui → service → model.** Nothing in `service` uses JavaFX, and the compare logic is pure functions that take snapshots.
- **The threading model is simple and correct.** Each session gets its own single-thread executor, because JDBC connections aren't thread-safe. `Async` delivers results back on the FX thread. Cancel goes through a separate pool and a `volatile` statement reference.
- **Read-only mode is enforced by each driver, not just by the UI:**
  - PostgreSQL: `readOnlyMode=always`
  - MySQL: `setReadOnly`
  - SQLite: `open_mode=1`, which also never creates the file
- **Reconnect is careful.** A retry is skipped once a user statement has been sent, so a statement never runs twice.
- **Comments explain why the code does something**, and the style is consistent throughout.

## Issues

### 1. Edits mapped to table columns by result label — fixed

`EditableQuery` accepted any simple `SELECT … FROM t`, and every result column whose label matched a table column became editable. `select id, name as email from users` sent edits under "email" to `users.email`, and `select id + 1 as id …` could update a different row.

**Fixed:** a result is editable only when its select list is `*`, `t.*`, or plain column names with no aliases, expressions, repeats, or `*` mixed with columns. Also fixed along the way: a query with `FROM` at the start of a line (`select *\nfrom users`) was never recognised as editable.

### 2. There were no tests — mostly done

`mvn test` now runs 115 tests: `EditableQuery`, `SqlSplitter`, `NamedParams`, `CompletionContext`, `DataCompare`, `SchemaCompare`, `JsonFiles`, `AppLog`, `SessionManager` and `PendingEdits`. `SessionManager`'s tests open real sessions on empty SQLite files, so they need no database server. A failing test stops `mvn package`.

**Still untested:** the screens themselves: how the grid, the value panel and the edit bar react to clicks, focus and typing. Testing those needs a UI test tool such as TestFX, or trying them in the running app.

**Started (2026-10-23), without a new library:** `Fx` (test helper) starts JavaFX once and runs code on its thread,
so screen pieces that need no database and show no dialog get plain JUnit tests: `ScreenPiecesTest` covers the tab
menu (close left / all, stopping at a tab that stays open), asking every tab before the app closes, the compare file's
`*` and Save button, and the column picker's ticks. CI already runs the tests under a virtual display (`xvfb-run`).
The tests count 206 now. **Still by hand:** anything with a dialog or a database behind it (the checks in this round
were run in the app against PostgreSQL); TestFX would be the next step if those should run in CI too.

### 3. The SQL scanner was written three times — fixed

`SqlSplitter`, `NamedParams` and `CompletionContext` each had their own copy of the comment, quote and `$tag$` scanning. The copies had already drifted: a blank line ended a statement when running, but not for autocomplete.

**Fixed:** `SqlLexer` cuts SQL into code and non-code parts once, and all three use it. Autocomplete now uses `SqlSplitter`'s statement boundaries (`;` and blank lines outside parentheses), so it sees the same statement that would run.

**Still heuristic:** `EditableQuery`, `isReadOnlyQuery` (a blocklist of words) and the `DDL` pattern in `ScriptRunner` are regex checks. `isReadOnlyQuery` gates re-running a query to fetch more rows and the compare tool's Where filter, and `select f()` passes even if `f` writes. The read-only connection flag is the real safeguard.

### 4. Fetching more rows re-runs the query and skips rows — partly fixed

Where: `DataView.fetchMore`, `DatabaseSession.execute(..., skip)`.

- Every chunk re-runs the query and reads past everything already fetched, so cost grows with the square of the rows fetched.
- Without an ORDER BY, rows can repeat or go missing between chunks.

**Fixed:** when more rows are available and the query has no ORDER BY of its own, the bar under the grid says so: "No ORDER BY: the query is re-run for each chunk, so rows may repeat or be missed". `SqlSplitter.hasOrderBy` looks for ORDER BY outside parentheses only, so one that sorts a subquery or a window does not count. It ignores quotes and comments and has 10 tests.

**Not done, on purpose:** keeping a cursor open between chunks. Each session has one connection, and the scripts run on it with autocommit on.
- PostgreSQL only streams rows with autocommit off, so the user's next statements would run inside an open transaction.
- MySQL's streaming mode blocks the connection for every other statement until the rows are read to the end.

A second connection just for paging would avoid both. That is worth doing only if fetching tens of thousands of rows turns out to be common.

**Fixed (2026-10-23), with that second connection:** at the first "more", the query runs once more on a connection of
its own (`DatabaseSession.openCursor`: autocommit off on PostgreSQL, row streaming on MySQL), passes over the rows
already shown and is kept open; every later chunk is read from it (`RowCursor.next`, reading one row ahead to know
whether more are left), with no re-run. The connection closes when the rows run out, another result is shown, or the
view leaves the screen. 1 test on SQLite; tried in the app on PostgreSQL: 2,500 rows in 13 chunks, in order without
repeats, the paging connection open while reading and gone after. **What is left:** without ORDER BY, the second run
may order rows differently from the first chunk's run, so the boundary between them can still overlap or skip; the
bar says so ("the rest may not line up"). Running the very first chunk on the cursor too would remove that, at the
cost of a connection per result.

### 5. `DatabaseSession` (now 1,108 lines) does too much — partly fixed

Re-checked 2026-10-22: 43 public methods and 29 branches on the database type. It handles connection lifecycle and retry, metadata, editing, and running statements. Its `switch (config.getType())` blocks are spread across about 8 methods. Before adding a 4th database (SQL Server, Oracle), pull the per-database SQL out into a `Dialect` interface, one implementation per `DbType`.

**Done, first step (2026-10-23):** everything that reads the structure (schemas, tables, columns, keys, indexes,
constraints, foreign keys, triggers, view definitions, DDL: 517 lines) moved unchanged into `service/Metadata`;
`DatabaseSession` keeps the same public methods as one-line delegations, so no caller changed, and is down to 757
lines: connection lifecycle and retry, running statements, streaming and cursors, editing and inserting. The 25
remaining per-database branches are now all in `Metadata` (catalog queries), which is where a `Dialect` per database
plugs in next. **Checked by comparison:** every metadata answer for every table and view, printed by the installed
app (before) and by this branch (after), is identical on PostgreSQL (with a materialized view, FK, check, index and
trigger), MySQL 8 and SQLite. **Bug found by that comparison and fixed:** `Metadata` was first created in a field
initializer, before the session had its config, so every type-dependent call failed; no unit test called one.
`MetadataTest` (SQLite) now does, and was checked to fail with the bug back in.

**Done, second step: `Dialect`.** The catalog queries that differ per database (namespaces, default namespace, view
definition, DDL, constraints, triggers) are in `PostgresDialect`, `MySqlDialect` and `SqliteDialect` behind a small
`Dialect` interface, each with its own helpers (PostgreSQL's DDL builder, SQLite's check and trigger parsing).
`Metadata` (323 lines) keeps what the databases share through JDBC and has no database branch left; a fourth
database is one more class. Checked by the same comparison: identical on PostgreSQL, MySQL 8 and SQLite.
**Left in `DatabaseSession`:** 6 small branches about connecting and writing (driver properties for read-only, the
PostgreSQL `CAST(? AS type)` and the booleans on import); they could become `Dialect` methods when a fourth database
needs them.

### 6. Some UI classes have absorbed non-UI work — partly fixed

- **Fixed:** the open sessions and the queue of callers waiting for a connection to open moved from `MainWindow` into `SessionManager`, which has no JavaFX dependency and 6 tests. They cover callers sharing one session while it opens, failures reaching every caller, a connection edited or deleted while opening, and closing. `MainWindow` (776 lines) keeps the window, connection create/edit/delete and tree loading, and only updates the status bar and tree when told a session opened or failed.
- **Fixed:** `CompareTab` (848 lines, two tools behind a `Mode` flag) is now `SchemaCompareTab` (319 lines) and `DataCompareTab` (388 lines). They share `CompareSide` (192 lines), one side's connection, schema and table pickers, with the swap button and the target following the source. The code moved without changing behaviour, with one exception: each tab now shows a side's errors in its own status line. Before, a data compare's schema-loading errors went to the hidden schema page.

### 7. Global static state — open

`Themes` (the settings, the theme and the previewed settings), `DataView`'s `SHOW_VALUE_PANEL`, `RECORD_MODE` and `LINE_DIFF`, the `Async` pool, `InAppDialogs.host` and `TableTransfer.lastDir` are all static. That is fine for a single-window app, but it is the main obstacle to testing the UI.

### 8. Saved files could be corrupted — partly fixed

- **Fixed:** the three stores write through `JsonFiles`, which writes a temporary file and then moves it over the old one in one step. A crash or full disk leaves the old file intact. `connections.json` is created owner-only (POSIX) from the start, instead of having its permissions changed after writing.
- **Partly fixed:** passwords are stored in plain text, with no file protection on Windows. Each connection now has
  **Save password**: off, the password is never written to `connections.json`; it is asked when connecting (an in-app
  prompt), kept in memory for the run, and asked again if it was wrong. Older files mean "on". 2 store tests; the
  prompt was tried in the app against PostgreSQL (wrong password, right one, reuse).
- **Fixed (was new, small):** two tools opening the same unsaved-password connection at the same moment (e.g. a
  reopened data compare whose sides share one connection) each showed the prompt. Now a second caller waits for the
  prompt that is open and goes on with its answer (or its Cancel). Tried in the app: two callers at once, one prompt,
  both connected, one shared session.
- **Fixed (was open):** `ScriptBindings` keyed scripts by absolute path. Since `Script/` lives next to the jar, moving the app folder orphans every binding. This happened with the move from `~/App/DBAdmin` to `~/App/DBArbiter`: `deploy-linux.sh` had to rewrite the paths in `scripts.json` with `sed`. A user who moved or re-extracted a download hit it with no such help.
  Now a script in `Script/` is recorded as `Script/<path>` (others keep their absolute path); old files are read as
  before, and an absolute key into a moved app folder's `Script/` is matched to the same file in today's. 2 tests.

### 9. Errors swallowed silently — fixed

- When query results or a table are not editable, the edit bar says why: the alias or expression, JOIN/DISTINCT/GROUP BY, several tables, a view, no primary key, or the error that stopped the lookup. `TableEditing` no longer has empty error handlers.
- `AppLog` writes `~/.dbadmin/logs/dbadmin.0.log` with `java.util.logging` (no new dependency), rotated at 1 MB with 3 files kept, in an owner-only folder. It records:
  - every background task that fails: database errors as one line with their SQLState, anything else with its stack trace
  - uncaught exceptions on any thread
  - connections opened, closed, lost and reopened
  - settings or script-binding files that cannot be read (these used to fall back to defaults silently)
  - metadata that autocomplete could not load

  It never logs the SQL that is run or passwords. A database's error message may still quote part of a statement (MySQL's "near '…'").

### 10. Small tidy-ups — fixed

- The orphaned `/** One row per index… */` comment in `DatabaseSession` now sits on `listIndexes`, where it belongs.
- The two comments stacked on `SchemaCompare.TableSnapshot` are merged into one.

### 11. `DataView` was growing, with side effects in query-like methods — partly fixed

- **Fixed:** the bookkeeping of unsaved edits (original values per edited row, revert, the row updates to save) moved from `DataView` into `PendingEdits`, which has no JavaFX dependency and 9 tests. `DataView` is down to about 420 lines and keeps the screen work: the edit bar, the save dialog, fetching more rows and the value panel.
- **Fixed (was open):** `DataView.hasEdits()` and `CellInspector.show()` applied text typed in the value panel but not yet applied. That is deliberate, so typed text is never lost, but a method named like a check now changes state, and focus changes also apply text, so the order of events matters. Trying editing in the running app is the main safeguard until the screens have tests.
  Now `hasEdits()` only looks (it counts typed text through `CellInspector.hasPendingText()`), `show()` only shows,
  and the three places that need typed text applied do it themselves, in plain sight: moving to another cell
  (`DataView.showCell`, like a grid cell editor), the discard question, and Save. Tried in the app: typed text counts
  as unsaved without being applied, moving to another cell applies it to the cell it was typed for, closing asks.
- **Open again (new, 2026-10-23):** adding, duplicating and deleting rows (todo #3) brought `DataView` back to about
  680 lines. The row bookkeeping went into `PendingEdits` (new and deleted rows, the DEFAULT cell) and the saving into
  `DatabaseSession.applyChanges`, both tested, but `DataView` still holds the `DataGrid.Editor` it builds in
  `enableEditing`, the edit bar and the save dialog next to paging and the value panel. Next step when it grows
  again: move the editing part (the Editor, the edit bar, Save / Revert all and the save dialog) into a class of its
  own, e.g. `RowEditing`, that `DataView` hands the grid to.

### 12. `DataCompareTab` is the largest class — open (new)

At 1,349 lines it holds six jobs: the form (sides, Where, rows, one-to-many), the joins editor, the column picker
with shift-click ranges, running the compare, verdicts (cells, menus, keys, paste, suggest) and the compare file
(save, save as, dirty marker, close prompt), plus Export HTML and Generate SQL. The logic it calls is in good shape
(`DataCompare`, `KeyJoins`, `MigrationSql` and `DataCompareReport` are pure and tested), so this is about the screen
code only. Each new compare feature has landed here, so it keeps growing.

**Suggested split**, moving code without changing behaviour:
- ~~`JoinEditor`~~ **done**: the join rows, the main alias (filled from the table name), the ON suggestion from foreign
  keys, and restoring a reopened compare's joins; the tab asks it for the joins, their tables and the ones ready to
  list columns. Moved without changing behaviour; tried in the app with a reopened two-join compare.
- ~~`ColumnPicker`~~ **done**: the Key / Compare / Show table, its shift-click cells, and the rule that keeps ticks by
  name when the columns are listed again (`merged`, 2 tests); the tab asks it for keys / compared / shown. Tried in
  the app: a reopened compare with joins gets its ticks back and compares.
- ~~`VerdictActions`~~ **done** as `service/compare/Verdicts`: set, paste, suggest and summarise verdicts, no JavaFX,
  4 tests; the tab keeps only the messages and refreshes (and lost an orphaned doc comment on the way). Tried in the
  app: key U on a changed row, key I refused there with the reason, Suggest, the `*` on the tab.
- ~~`CompareFile`~~ **done**: the file, settings-only or results, results newer than the file, unsaved verdicts, the
  tab's `*`, the Save button and the Save / Don't Save / Cancel question, in one class both compare tabs use (each had
  its own copy). Tried in the app: open, verdict (`*`), save (no `*`), compare again (`*`), close (asked why).

What stays in `DataCompareTab` (956 lines) is the form, running the compare, the results grid and Save / Save As /
Export / Generate SQL wiring: one screen's worth. A further split is not needed until it grows again.

### 13. `MainWindow` is growing again — partly fixed

985 lines (776 after #6): licensing, the export/import host, the tab menu and the update check came in since. Not
urgent, but licensing (activate, show, re-check the date) would fit its own small class next to `LicenseDialog`.

**Done:** `LicenseGate` holds the footer's license line, the License dialog and the check before a licensed tool
opens (`allows("Compare Table and Compare Data")`), so a new licensed tool calls one method. Tried in the app without a
license: Tools → Compare Data shows "… need a license" and opens nothing. `MainWindow` is 987 lines: what left with
the license came back with this round's fixes (password prompt, asking before disconnect). **Next candidates:** the
connection create / edit / delete / disconnect code (about 150 lines) and the script open/new code.

**Also done:** `TabStrip` holds the tabs' right-click menu, closing a tab the way its ✕ does, and asking every tab with
unsaved work before the window closes (it also dropped a stale doc comment left from the `UnsavedWork` change).
Tried in the app: Close Others, Ctrl+W, and closing the window with an unsaved script (asked, Cancel keeps it open).
**Left as it is, on purpose:** the connection create / edit / delete code works on the tree, the tabs and the sessions
together; moving it out would hand most of the window's state to another class without making either clearer.

### 14. Shipping the app to other people — open (new)

The app is now downloaded from GitHub Releases, so a few things matter more than when it ran only on one machine:
- **Passwords (#8):** other people's database passwords sit in plain text in `~/.dbadmin/connections.json`. On
  Windows nothing protects the file. **Done:** a "Save password" option per connection (off: asked when connecting).
  **Still open:** the OS keyring (Windows Credential Manager / DPAPI, Linux Secret Service), which needs a native
  bridge such as JNA.
- **License check:** the jar can be decompiled and the check patched out. That is true of any offline Java license
  and is acceptable for a trial-then-key product sold to companies; obfuscation (e.g. ProGuard) would only raise the
  effort. The signing key never leaves the signer's machine, which is the part that matters.
- **Release build:** the Windows profile names the JavaFX jars itself (`javafx-base`, `-graphics`, `-controls`,
  `-media`, `-web` with classifier `win`), because GitHub's Maven could not read JavaFX's pom for another platform.
  A JavaFX module added to the app must be added there too; the CI step "Check both downloads contain JavaFX"
  catches a missing one.
- **Unsigned exe:** Windows SmartScreen warns on first run until the exe is code-signed (see `docs/release-plan.md`).

## Growth and tech debt: if more tools and databases are added

_Added 2026-10-22._ The plan is more release tools (a scan for test data, a release checklist, migration checks)
and maybe more databases. Each item below is cheap today and gets more expensive with every tool added on top of it.
They are ordered by how soon they would hurt.

### G1. One connection per database is shared by everything — mostly fixed

Each connection has one JDBC connection and one worker thread (`DatabaseSession.submit`). The navigator, table tabs,
scripts, autocomplete metadata and both compares queue on it. A long script blocks a compare on the same
connection, and a compare blocks expanding the tree. The planned full scan (every table, every text column) would
hold the connection for minutes.

**Before the scan tool:** give long jobs their own connection: a second session from `SessionManager`
("work" next to "interactive"), opened on demand and closed when the job ends. It also solves paging (#4), since a
job connection can keep a cursor open.

**Done for both compares** (with G2): a compare opens its own session per side, reads both sides at the same time and
closes them at the end. Tried in the app: during a data compare PostgreSQL shows the 2 interactive connections plus
the job's 2; after it, or after Cancel, only the 2 interactive ones. **Also done:** export (streaming the rows into the file), and import (reading the existing keys, inserting the
rows) run as a `Job` on a connection of their own; fetching more rows has its own connection too (#4). `Job` now logs
failures to the app log like `Async` does, so moving work to it loses nothing. Tried in the app: an export of 40 rows
and an import of 2, each on its own session, the interactive connection alone afterwards. **What stays on the shared
connection** is short, interactive work: the navigator, table pages, scripts the user runs, autocomplete metadata.
**Cancel for export and import: done**: the window's bottom bar shows **Cancel** next to the status while
one runs (`TableTransfer.Host.running` / `stopped`); a cancelled export deletes its half-written file and says
"Export cancelled", an import's transaction ends with its connection ("nothing was imported"). Tried in the app: a
20-second export cancelled after 1.5 s, Cancel gone, its connection closed.

### G2. No shared idea of a long job — partly fixed

Each tool hand-rolls its own progress text, cancellation and error line: the table compare counts tables in a
label, the data compare has no cancel at all, import has its own progress. There are 26 `Async.run` calls,
nested inside each other in places (read side A, then side B, then compare). Every new tool adds another copy.

**Add a small `Job` type:** a title, progress (done / total), cancel, and a result or error, run on G1's connection,
with one progress bar and Cancel button that every tool reuses. New tools then describe *what* to do, not how to
show it.

**Done:** `service/Job` (no JavaFX): opens a session per connection it needs, runs the work on a background thread,
reports progress, and on Cancel cancels the running statements; exactly one of done / failed / cancelled is called,
after its sessions are closed. 4 tests on SQLite (result, failure, cancel, a connection that cannot open). Both
compares use it, and their Compare button turns into **Cancel** while they run (the data compare could not be
stopped before); closing the tab cancels too. Tried in the app: a data compare slowed with `pg_sleep(8)` stopped
within 0.8 s of Cancel. **Not yet:** one shared progress widget: the compares show progress and Cancel in their own
tab, export and import in the window's bottom bar (G1); a third kind of tool should pick one of those two places.

### G3. Whole result sets in memory — medium, high for the scan

The data compare reads both sides fully (`Rows per side` goes up to 1,000,000) as lists of objects, then a report
of up to 50,000 rows held as text. Fine for one master table, but a scan of a whole database will not fit this way.

**For the scan and other bulk tools:** stream rows (`streamRows` already exists) and keep only findings, never the
rows themselves.

### G4. Adding a tool means editing several places by hand — partly fixed

A new tool needs its menu item in `MainWindow`, its own license gate, its own save / dirty / close-prompt code,
which exists four times (`ScriptTab`, `SchemaCompareTab`, `DataCompareTab`, `MainWindow`, 11 `instanceof`
checks on tab types), and a new `case` in `CompareReportFiles.open`.

**Add a `ToolTab` interface** (title, dirty, save, confirm close, the file kind it opens) that `MainWindow` loops
over. Opening a file and closing the app then work for any tool, and a fifth tool costs one class instead of
four edits.

**Done, first part:** `UnsavedWork` (`confirmClose()`), implemented by the script, table and both compare tabs;
closing the app asks every tab that implements it, with no list of tab types. **This fixed a bug found on the way:**
table tabs were not in the old list, so closing the app dropped unsaved cell edits without asking, and
disconnecting, reconnecting, editing or deleting a connection closed its table tabs the same way. Now each asks
first, and Cancel keeps the connection and the tab. Tried in the app against PostgreSQL (close app: keep / discard;
disconnect: keep).

**Still open:** the menu item and license gate per tool, and opening a file by its kind through the tool instead of a
`switch`. (The shared save / dirty state of the compare tabs is done: `CompareFile`, #12.)

**Fixed (found here):** a script tab's result grid can hold cell edits too, and disconnecting dropped them without
asking. `ScriptTab.confirmConnectionClose` now asks first, like the table tabs. Tried in the app: run a query, edit
a cell, disconnect (keep / discard).

### G5. Database differences are spread across the code — partly fixed

29 branches on the database type in `DatabaseSession`, 19 more in 10 other classes (`SqlLexer`, `SqlInserts`,
`SqlCompleter`, `DataCompareTab`, `GenerateSqlDialog`, …), and `MigrationSql` writes PostgreSQL only. SQL Server
or Oracle would mean finding all of them.

**A `Dialect` per database** (quoting, casts, identity columns, paging, metadata queries, upsert syntax), and
`MigrationSql` taking one. This is #5 with a deadline: before the fourth database, or before Generate SQL for MySQL.

**Done, first step (the SQL text):** what differs in SQL *text* is now asked of `DbType` in one place: quoting
(`quote`, `quoteIfNeeded`), string escapes, `$$` quotes, `#` comments, boolean and binary literals, catalog vs schema
as the table's qualifier, system schemas, materialized views, and how to stream rows (fetch size, transaction).
`SqlLexer`, `SqlInserts`, `SqlCompleter`, `SqlHighlighter`, `Namespace`, `TableTab`, `MainWindow`, `DataCompareTab`
and five spots in `DatabaseSession` use them instead of their own `if (type == …)`. Outside `DatabaseSession` only
defaults and the PostgreSQL-only Generate SQL remain; inside it, 25 branches (from 29). 2 tests pin the facts per
database. **Also done:** the metadata queries (views, DDL, constraints, triggers, schemas) are one `Dialect` per
database (#5). Generate SQL (`MigrationSql`) still writes PostgreSQL only; giving it a dialect is the next step if
MySQL migrations are needed.

**Fixed (was new, small, found here):** a null `DbType` meant "plain SQL, no database" in the lexer, highlighter,
completer and `Namespace` by convention only; the tests caught a crash when the first version of this change forgot
it. Now it is a written contract on each method that takes it ("null: plain SQL, with only what every database
shares"), every one of them handles it, and the Data Compare's Where check passes the source database's type instead
of null (so a MySQL `#` comment there is read as one). A `DbType.GENERIC` constant was not added: it would also show up
in the connection form's list of types.

**New (2026-10-23, structure editing, todo #6):** the table tab can now add, change and drop columns, constraints,
indexes and foreign keys. Reading a column exactly (`ColumnSpec`) is one more `Dialect` method per database, as it
should be. Writing the DDL (`SchemaChange`) is one class with a `switch` on the database per kind of change, about a
dozen branches; a fourth database means filling in each of them. Move them onto the `Dialect` when that day comes, or
when `MigrationSql` gets its dialect, so both write SQL the same way. Two smaller gaps found on the way:
- SQLite's JDBC driver does not say which index columns are DESC, so the Indexes section never showed it, and editing
  such an index on SQLite (drop + create) would create it ascending. `pragma index_xinfo` has the order; read it in
  `SqliteDialect` if this matters.
- MariaDB lists column defaults differently from MySQL (quoted, NULL as text); `MySqlDialect.columns` handles it as
  documented, but it was tried on MySQL 8.4 only.

### G6. Saved files have no format versions — partly fixed

`.dbcompare` files carry `"version": 1`, but nothing reads it; `settings.json`, `connections.json` and
`scripts.json` have none. Today a change is handled by Jackson ignoring unknown fields and defaulting missing
ones, which works until a field changes meaning or moves. Users now keep compare files for weeks and update
the app when it tells them a new version is out, so old files meet new versions.

**Read the version and upgrade old files in one place per format**, and refuse with a clear message a file from a
newer version, instead of misreading it.

**Done for compare files** (the ones people keep and share): `CompareReportFiles.open` reads `version` (missing
means 1), refuses a newer one with "saved by a newer version of DBArbiter … Update the app to open it", and is the
one place where older formats will be upgraded. Each report class has its `VERSION`. 1 test; the 13 saved reports
in `Reports/` still open.

**Still open:** `settings.json`, `connections.json` (a JSON array, so a version needs a wrapping object, read in both
forms) and `scripts.json`. Missing fields are filled with defaults, so nothing breaks today; add the version the
first time one of their fields changes meaning.

### G7. Screen code holds logic that cannot be tested — medium

Screens are built in code in long methods (`DataCompareTab.dataPage` is 147 lines), and the decisions sit next to
the widgets: what Save does, which verdicts fit, when Compare is allowed. There are no UI tests; the screenshot
checks done so far reach into private fields by reflection, which breaks on every rename. The tested parts are
exactly the ones that moved out of the screens (`PendingEdits`, `SessionManager`, `MigrationSql`).

**For each new tool, keep the screen thin:** a plain class with the state and rules (tested), and a view that only
binds widgets to it. Existing screens move over as they are touched (#12).

### G8. The download is heavy for what it uses — low

The jar is 74 MB, and the WebKit library inside JavaFX's `javafx-web` is 112 MB unpacked, about half of the download.
It is used only for the HTML preview in the value panel. Dropping it (show HTML as highlighted text, or open it in the
browser) would roughly halve the download and the memory used.

### G9. English only, text in code — low, decide early

About 400 UI texts are written directly in the Java code. If the app should ever offer Bahasa Indonesia for Antam
users, moving the texts into a resource bundle gets more expensive with every screen. Decide before the next big UI.

### G10. Build and release logic is duplicated — mostly fixed

The jlink module list and the launcher script exist in both `deploy-linux.sh` and `build-linux.sh`, and the version
number is worked out in the deploy script and again in CI. `deploy-linux.sh` could call `build-linux.sh` and only
install what it builds.

**Done:** `deploy-linux.sh` now runs `build-linux.sh` (the same build as the Linux download, tests included) and
installs its jar, launcher, icon and, the first time, its Java runtime; the jlink module list and the launcher exist
once. It also no longer rewrites `scripts.json` with `sed` after moving `Script/` (#8 makes that unneeded). Tried by
deploying into a fresh home. **Still duplicated:** the version number (local tags in the deploy script, the tag name
in CI); they serve different purposes, so that stays. Also, the internal names are still the old one (`com.dbadmin` packages, `~/.dbadmin`); that
is fine, but a public API or plugin later would carry it.

### Order for a growing app

| When | Do | Items |
|---|---|---|
| Now (small) | ~~Script bindings by relative path~~, ~~"Save password" option~~ (both done) | #8, #14 |
| Before the scan tool | A job connection per tool run, a shared `Job` with progress and cancel, streaming | G1, G2, G3 |
| Before the next tool after that | `ToolTab` (menu, license gate, opening files by kind); ~~shared compare file, split `DataCompareTab`~~ (done) | G4, #12 |
| Before a fourth database or MySQL migrations | ~~`Dialect`~~ (done); a dialect for Generate SQL | G5, #5 |
| Whenever a file format changes next | Format versions with upgrades (compare files done) | G6 |
| Ongoing | Logic out of screens for new code | G7 |
| Optional | Drop WebView, resource bundle (single build script: done) | G8, G9, G10 |

## Suggested next steps

In order, now that other people use the app:
1. **Passwords (#14, #8):** a "Save password" option per connection; the OS keyring later.
2. **Script bindings by relative path (#8):** keys relative to `Script/` (absolute only for scripts elsewhere), read
   both forms so existing `scripts.json` files keep working. Small, and it already caused a problem.
3. **Split `DataCompareTab` (#12)** before the next compare feature, starting with `VerdictActions` (testable) and the
   shared `CompareFile`.
4. ~~**`Dialect` (#5)**~~ done: SQL text on `DbType` (G5), catalog queries in one `Dialect` per database.
5. As needed: less static state (#7) once the screens get tests. (Paging, #4, and the side effects in
   `hasEdits()` / `show()`, #11, are fixed.)
