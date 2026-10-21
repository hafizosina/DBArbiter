# DBAdmin Architecture Review

_First review: 2026-10-21 at commit `70ef05a`. Updated the same day after the follow-up fixes (see [Status](#status))._

**Scope:** every service and model class was read in full, along with the main UI classes (MainWindow, DataView, CompareTab, ScriptRunner, TableEditing, Themes, Async). The app was not run for this review; the fixes are covered by unit tests, but the UI changes have not been tried in the running app.

**Size:** about 9.5k lines of Java 17 in a JavaFX 21 desktop app. It supports PostgreSQL, MySQL and SQLite over JDBC and builds into a fat jar, plus a launch4j `.exe` for Windows.

## Status

| # | Issue | Status |
|---|---|---|
| 1 | Edits mapped to table columns by result label | **Fixed**: only `*` or plain column lists are editable |
| 2 | No tests | **Partly done**: 92 JUnit tests for the pure service classes; UI and `SchemaCompare` untested |
| 3 | SQL scanner written three times | **Fixed**: one `SqlLexer`; splitting and autocomplete share statement boundaries |
| 4 | Fetching more rows re-runs the query | Open |
| 5 | `DatabaseSession` does too much | Open |
| 6 | `MainWindow` and `CompareTab` do too much | Open; `DataView` is now growing too (see 11) |
| 7 | Global static state | Open |
| 8 | Saved files can be corrupted | **Partly fixed**: atomic writes. Plain-text passwords and path-keyed script bindings remain |
| 9 | Errors swallowed silently | **Partly fixed**: the reason a result is not editable is now shown. No logging yet |
| 10 | Small tidy-ups | Open |
| 11 | `DataView` growing; side effects in `hasEdits()` / `show()` | New, open |

## How it is organised

```
model/            records and plain classes: ConnectionConfig, TableRef, TableData, StatementResult…
service/          JDBC and plain SQL-text logic, no JavaFX
  DatabaseSession     one connection, one worker thread, reconnect/retry, metadata for all 3 databases
  SqlLexer            comments, quotes, dollar quotes: the one place that knows where SQL code is
  SqlSplitter / NamedParams / CompletionContext / EditableQuery   built on SqlLexer
  ConnectionStore / SettingsStore / ScriptBindings                JSON files in ~/.dbadmin, written via JsonFiles
  compare/            SchemaCompare, DataCompare (pure functions over snapshots)
ui/               about 35 JavaFX classes: MainWindow (also the SessionProvider), tabs, grid, editor, dialogs
src/test/         JUnit 5 tests for the service classes (mvn test)
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

### 2. There were no tests — partly done

`mvn test` now runs 92 tests: `EditableQuery`, `SqlSplitter`, `NamedParams`, `CompletionContext`, `DataCompare` and `JsonFiles`. A failing test stops `mvn package`.

**Still untested:** `SchemaCompare.compareMaps`, and all UI behaviour, most importantly cell editing in `DataView` (see 11).

### 3. The SQL scanner was written three times — fixed

`SqlSplitter`, `NamedParams` and `CompletionContext` each had their own copy of the comment, quote and `$tag$` scanning. The copies had already drifted: a blank line ended a statement when running, but not for autocomplete.

**Fixed:** `SqlLexer` cuts SQL into code and non-code parts once, and all three use it. Autocomplete now uses `SqlSplitter`'s statement boundaries (`;` and blank lines outside parentheses), so it sees the same statement that would run.

**Still heuristic:** `EditableQuery`, `isReadOnlyQuery` (a blocklist of words) and the `DDL` pattern in `ScriptRunner` are regex checks. `isReadOnlyQuery` gates re-running a query to fetch more rows and the compare tool's Where filter, and `select f()` passes even if `f` writes. The read-only connection flag is the real safeguard.

### 4. Fetching more rows re-runs the query and skips rows — open

Where: `DataView.fetchMore`, `DatabaseSession.execute(..., skip)`.

- Every chunk re-runs the query and reads past everything already fetched, so cost grows with the square of the rows fetched.
- Without an ORDER BY, rows can repeat or go missing between chunks.

Keeping a cursor open (autocommit off plus `setFetchSize`) avoids both, but ties up the session's single worker thread, so it is a design decision. At minimum, the UI could warn when the query has no ORDER BY.

### 5. `DatabaseSession` (about 800 lines) does too much — open

It handles connection lifecycle and retry, metadata, editing, and running statements. Its `switch (config.getType())` blocks are spread across about 8 methods. Before adding a 4th database (SQL Server, Oracle), pull the per-database SQL out into a `Dialect` interface, one implementation per `DbType`.

### 6. Some UI classes have absorbed non-UI work — open

- `MainWindow` (793 lines) owns the session registry, the "connecting" waiter queue, connection create/edit/delete, and tree loading. The session map and waiters could move into a `SessionManager` with no JavaFX dependency, which would then be testable.
- `CompareTab` (848 lines) holds two separate tools, schema compare and data compare, and could be split in two.

### 7. Global static state — open

`Themes` (settings), `DataView.SHOW_VALUE_PANEL`, the `Async` pool and `InAppDialogs` are all static. That is fine for a single-window app, but it is the main obstacle to testing the UI.

### 8. Saved files could be corrupted — partly fixed

- **Fixed:** the three stores write through `JsonFiles`, which writes a temporary file and then moves it over the old one in one step. A crash or full disk leaves the old file intact. `connections.json` is created owner-only (POSIX) from the start, instead of having its permissions changed after writing.
- **Open:** passwords are stored in plain text, with no file protection on Windows.
- **Open:** `ScriptBindings` keys scripts by absolute path. Since `Script/` lives next to the jar, moving the app folder orphans every binding.

### 9. Errors swallowed silently — partly fixed

- **Fixed:** when query results or a table are not editable, the edit bar says why: the alias or expression, JOIN/DISTINCT/GROUP BY, several tables, a view, no primary key, or the error that stopped the lookup. `TableEditing` no longer has empty error handlers.
- **Open:** there is no logging, so problems in the field can't be diagnosed after the fact. Even `java.util.logging` would help.

### 10. Small tidy-ups — open

- `service/DatabaseSession.java:266` has an orphaned `/** One row per index… */` comment above `ColumnDef`.
- `service/compare/SchemaCompare.java:40-41` has two comments stacked on one record.

### 11. `DataView` is growing, with side effects in query-like methods — new

`DataView` (about 450 lines) now holds pending edits, saving, fetching more rows, and the value panel's link to the grid. The pending-edit part (the edited-cell map, apply, revert, save) could move into its own class.

`DataView.hasEdits()` and `CellInspector.show()` apply text typed in the value panel but not yet applied. That is deliberate, so typed text is never lost, but a method named like a check now changes state, and focus changes also apply text, so the order of events matters. Trying editing in the running app is the main safeguard until the UI has tests.

## Suggested next steps

1. Try cell editing in the running app: the grid, the value panel, HTML values, and the new "Not editable" messages.
2. Add logging (#9), so failures in the field leave a trace.
3. Extract the pending-edit state out of `DataView` (#11) and give it tests.
4. Then as needed: `SessionManager` (#6), `Dialect` (#5), fetching with a cursor (#4).
