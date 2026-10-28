# New features (branch todo-cleanup, todo items 7–23)

What each item does, how it was checked, and what it changes for existing users. Re-checked on 2026-10-28:
`mvn test` green (all tests), each feature exercised in the real tabs / window, with PostgreSQL 16, MySQL 8.4 and
SQLite where it touches a database.

**Impact at a glance**

- Nothing changes the database by itself. The only new writing action is **Reset Sequence…**, offered only where
  Truncate / Reset Table already are (writable connection, warning level 0), and it always asks with the SQL.
- Default behaviour that is different now: **#16** (typing a quote or bracket over a selection wraps it),
  **#22** (Data Compare hides columns without differences), **#11 / #14** (tab title and header look).
- New background reads: **#20** (one SELECT per focused foreign key cell), **#23** (sequences, only when the folder
  or the Properties section is opened), **#8** (the table's columns when the Export dialog opens).
- New files: `.dbscan` (#10), `~/.dbadmin/open-tabs.json` (#9, only while that setting is on).
  `.dbcompare` files get one more field (`booleanColumns`); older app versions ignore it.
- One review fix after the first round (6a2a2e8): the navigator read the sequences together with the tables, so a
  database where sequences cannot be read (PostgreSQL before 10, missing rights) would have listed no tables; and on
  MySQL it left the catalog cache off for the session. Now the Sequences folder loads only when opened, an error
  stays inside that folder, and MySQL's setting is put back.

---

## #13 Open .dbcompare by drag and drop
- **Done:** dropping one or more `.dbcompare` files (and `.dbscan`, see #10) on the window opens each in its tab, with
  the same license check as the menu. `.sql` drops work as before.
- **Checked:** real main window, drop events with `.txt` (refused), `.dbcompare` (upper-case name too), `.sql`, and
  all three at once.
- **Impact:** none on other drags. Dropping a report already open opens a second tab (as Open Compare Report… does).

## #14 Connection name in the table tab
- **Done:** the connection (icon, bold) and read-only / read-write (orange) on a line above the table name.
- **Impact:** look only. Edited connection names show after the tab is opened again.

## #11 Script tab title shows its connection
- **Done:** `*Untitled-4 · 03 A`: the first 4 characters of the connection's name; the tooltip has the full name.
  Follows a change of connection and a rename.
- **Impact:** look only; tabs are a little wider.

## #18 Row of the selected cell tinted
- **Done:** in table tabs and script results the focused cell's row is tinted (both themes); new / deleted rows keep
  their green / red, a shade darker.
- **Impact:** look only. Not seen on screen: the darker green / red of a focused new / deleted row.

## #16 Wrap a selection in brackets
- **Done:** in the SQL editor, typing `( [ { ' " \`` over selected text wraps it; the text stays selected; one undo.
- **Impact (behaviour change):** before, typing a quote over a selection replaced it. Closing brackets still replace.

## #15 Ctrl+F in every text view
- **Done:** value panel, diff view, Scan's matching rows, and the SQL of Generate SQL, Replace and Compare queries
  have a find bar; matches are marked over the view's own colours. In a script tab, Ctrl+F in the value panel
  searches the panel; elsewhere in the results it still searches the SQL. In Data Compare, Ctrl+F in the value panel
  searches the panel, elsewhere the grid.
- **Checked:** unit tests (keys, marks, routing) and the real views; Generate SQL and Replace dialogs not opened
  (same code).
- **Impact:** in an editable value panel, a find then Undo can undo the marks before the text (as in the SQL editor).

## #19 Copy rows as SQL
- **Done:** right-click → Copy as SQL → INSERT / UPDATE / DELETE of the selected rows; nothing runs. Values as shown
  (edits included); UPDATE / DELETE by the primary key (off when the key is not in the result); new rows only as
  INSERT. Also on read-only connections.
- **Checked:** the copied SQL was run back: DELETE then INSERT gives the same rows; UPDATE changes nothing.
- **Impact:** the grid of a table now always has a right-click menu (read-only connections too). Binary values are
  written as `<n bytes>` (not valid SQL): leave those columns out.

## #8 Export SQL INSERT: choose the key columns
- **Done:** "Skip rows that already exist, found by [columns]" (the primary key ticked first; tables without one
  too). By the primary key the old statements are kept; by other columns: `INSERT … SELECT … WHERE NOT EXISTS`.
- **Checked:** exports run twice into a copy on PostgreSQL, MySQL and SQLite: existing rows skipped, nothing doubled.
- **Impact:** the dialog reads the table's columns when it opens. Exporting the current query without a picked
  column stops with a message.

## #12 Data Compare: leave out the primary key
- **Done:** (a) "Leave out the primary key (id)" in the column picker; (b) "INSERT without id" in Generate SQL when
  the key is not serial / identity (those were always left out).
- **Checked:** picker unit test; script generated and run twice on PostgreSQL with a uuid key.
- **Impact:** the picker's checkbox is not saved in the .dbcompare (the ticks it made are). Generate SQL reads the
  target's primary keys (one more catalog read). With the key left out and the compare matched by that key, the
  script's header warns that running it again inserts again.

## #22 Data Compare: hide unchanged columns
- **Done:** compared columns where no row differs are hidden by default; "Hide N unchanged columns" shows them.
- **Impact (behaviour change):** hidden columns are not in Record mode, Ctrl+C or Ctrl+F of the grid. HTML / Excel
  exports and saved reports still have every column.

## #17 Ctrl+Shift+T reopens a closed tab
- **Done:** for 10 minutes, newest first (also in the tab menu): scripts with text, connection and unsaved state;
  tables; compares and scans that have a file.
- **Impact:** tabs closed by Disconnect are kept too; a table dropped meanwhile opens with its error. Closed scripts' text stays in memory for 10 minutes.

## #9 Settings: exit and startup
- **Done:** Settings → Exit & startup, both off by default: ask "Close DBArbiter?" (Cancel the default), and open
  the tabs open last time (saved scripts, tables, saved compares and scans).
- **Impact:** with reopen on, table tabs connect at startup (a password may be asked). Scripts never saved are not
  kept. Off: nothing changes, and the open-tabs file is removed on exit.

## #21 Data Compare: record mode and boolean check boxes
- **Done:** the Record toggle in the results bar; booleans as check boxes (`☑ → ☐` when they differ), from the
  column types now kept in the report.
- **Impact:** the Record toggle is shared by all tabs (as before for tables and scripts). Reports saved before show
  booleans as text. Record mode lists only the grid's visible columns (in table tabs: all of them).

## #23 Sequences
- **Done:** a Sequences folder per schema (read when opened); right-click Copy Name, Copy nextval(…)
  (PostgreSQL), Reset Sequence… (warning level 0, suggests the column's max + 1, shows the SQL). Properties →
  Sequences for the table's own. MySQL: AUTO_INCREMENT counters (read fresh); SQLite: sqlite_sequence.
- **Checked:** PostgreSQL, MySQL, SQLite: listing, restart SQL, the next insert's id; the menu on a warning level 1
  connection has no Reset.
- **Impact:** see the review fix above. Reset Sequence writes to the database (always asks).

## #20 Row a foreign key points to
- **Done:** for a foreign key cell, the value panel shows "→ m_product (id = 7)" and that row underneath; NULL and a
  missing row are said so.
- **Impact:** one small SELECT on the table's connection each time the focus moves to such a cell (in a queue
  behind a running query), and the table's foreign keys are read when its rows are shown.

## #10 Scan: save and reopen
- **Done:** Save… writes `.dbscan` (form + findings + coverage, format version 1); opened from Tools → Open Scan…,
  drag and drop, Ctrl+Shift+T and at startup, without scanning; Replace… works on it.
- **Impact:** new file type only. "Rows" of a finding are read live, so they may differ from when it was saved.

## #7 Background tasks panel
- **Done:** "⟳ N running" in the footer shows a panel with scripts, table queries, compares, scans, exports and
  imports: progress, time, Stop.
- **Checked:** a long script and a job listed with progress; Stop from the panel stops the script; both leave.
- **Impact:** Stop there is the same as the tab's own Stop / Cancel. A small timer refreshes the times while the
  panel is open.
