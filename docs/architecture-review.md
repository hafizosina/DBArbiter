# DBAdmin Architecture Review

_Date: 2026-10-21 · Commit reviewed: `70ef05a` (plus uncommitted changes to `DataGrid` and `DatabaseSession`)_

**Scope:** every service and model class was read in full, along with the main UI classes (MainWindow, DataView, CompareTab, ScriptRunner, TableEditing, Themes, Async). The app was not run, and the findings were not checked against a live database.

**Size:** about 9.5k lines of Java 17 in a JavaFX 21 desktop app. It supports PostgreSQL, MySQL and SQLite over JDBC and builds into a fat jar, plus a launch4j `.exe` for Windows.

## How it is organised

```
model/            records and plain classes: ConnectionConfig, TableRef, TableData, StatementResult…
service/          JDBC and plain SQL-text logic, no JavaFX
  DatabaseSession     one connection, one worker thread, reconnect/retry, metadata for all 3 databases
  SqlSplitter / NamedParams / CompletionContext / EditableQuery   hand-written SQL parsing
  ConnectionStore / SettingsStore / ScriptBindings                JSON files in ~/.dbadmin
  compare/            SchemaCompare, DataCompare (pure functions over snapshots)
ui/               about 35 JavaFX classes: MainWindow (also the SessionProvider), tabs, grid, editor, dialogs
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

## Problems, most important first

### 1. Correctness: editing maps grid columns to table columns by result label

Where: `ui/DataView.java:141`, `service/DatabaseSession.java:746`.

`EditableQuery` accepts any simple `SELECT … FROM t`, and every result column whose label matches a table column becomes editable. Two examples:

- `select id, name as email from users`: an edit under "email" is written to `users.email`.
- `select id + 1 as id, name from users`: the primary key value is wrong, so the UPDATE targets the wrong row. The "exactly 1 row" check still passes if that row exists.

**Fix:** only enable editing when the select list is `*` or plain column names, or use each driver's base-column metadata.

### 2. There are no tests

`src/test` doesn't exist. The riskiest code is also the easiest to test, because it is all pure functions:

- `SqlSplitter`, `NamedParams` and `CompletionContext` (quote, comment and dollar-quote handling)
- `EditableQuery`
- `DataCompare` (duplicate keys, number normalisation)
- `SchemaCompare.compareMaps`

A JUnit module for these is the change that would pay off most.

### 3. The SQL scanner is written three times

`SqlSplitter.statements`, `NamedParams.parse` and `CompletionContext.analyze` each re-implement the same comment, quote and `$tag$` scanning. On top of that, several checks are regex guesses:

- `EditableQuery`
- `isReadOnlyQuery`, which works from a blocklist of words
- the `DDL` pattern in `ScriptRunner`

One shared tokenizer (a stream of tokens that skips comments and strings) would remove this drift.

`isReadOnlyQuery` gates two things: re-running a query to fetch more rows, and the compare tool's Where filter (`ui/CompareTab.java:563`). For example, `select f()` passes even if `f` writes. So the read-only connection flag is the real safeguard and should be described that way.

### 4. Fetching more rows re-runs the query and skips rows

Where: `DataView.fetchMore`, `DatabaseSession.execute(..., skip)`.

- Every chunk re-runs the query and reads past everything already fetched, so cost grows with the square of the rows fetched.
- Without an ORDER BY, rows can repeat or go missing between chunks.

Keeping a cursor open (autocommit off plus `setFetchSize`) avoids both. The catch is that it ties up the session's single worker thread, so this is a design decision rather than a quick fix. At minimum, the UI could warn when the query has no ORDER BY.

### 5. `DatabaseSession` (800 lines) does too much

It handles connection lifecycle and retry, metadata, editing, and running statements. Its `switch (config.getType())` blocks are spread across about 8 methods. With 3 databases that is still manageable. Before adding a 4th (SQL Server, Oracle), pull the per-database SQL out into a `Dialect` interface, one implementation per `DbType`.

### 6. Some UI classes have absorbed non-UI work

- `MainWindow` (793 lines) owns the session registry, the "connecting" waiter queue, connection create/edit/delete, and tree loading. The session map and waiters could move into a `SessionManager` with no JavaFX dependency, which would then be testable.
- `CompareTab` (848 lines) holds two separate tools, schema compare and data compare, and could be split in two.

### 7. Global static state

`Themes` (settings), `DataView.SHOW_VALUE_PANEL`, the `Async` pool and `InAppDialogs` are all static. That is fine for a single-window app, but it is the main obstacle to testing the UI.

### 8. Saved files can be corrupted

- All three stores write `~/.dbadmin/*.json` directly. If the app crashes mid-write, `connections.json` is truncated, and `ConnectionStore.load` throws on it. Writing to a temp file and then doing an atomic move fixes this.
- Passwords are stored in plain text, and owner-only file permissions are only set on POSIX systems, so there is no protection on Windows.
- `ScriptBindings` keys scripts by absolute path. Since `Script/` lives next to the jar, moving the app folder orphans every binding.

### 9. Errors are swallowed silently

For example, `TableEditing` uses `ex -> { }` in two places, so a failed metadata lookup just means "not editable" with no reason given. There is no logging at all; even `java.util.logging` would help diagnose problems in the field.

### 10. Small tidy-ups

- `service/DatabaseSession.java:267` has an orphaned `/** One row per index… */` comment above `ColumnDef`.
- `service/compare/SchemaCompare.java:41-42` has two comments stacked on one record.

## Uncommitted changes at review time

The boolean checkbox editing in `DataGrid` and `setBoolean` in `applyUpdates` looks fine. No issues were found in it.

## Suggested order

1. Fix the column mapping in #1.
2. Add tests for the pure service classes (#2).
3. Make the JSON writes atomic (#8).
4. Then refactor as needed: shared tokenizer (#3), `SessionManager` (#6), `Dialect` (#5).
