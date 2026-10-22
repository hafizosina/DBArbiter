1. new tools -> full scan. to find some data in multiple table and columns.
2. [x] make sure table view panel in table detail tab and script query panel use same module. so no redundancy.
   - done: the table tab and the script results both use DataView (grid, value panel, editing).
3. [x] add feature add, duplicate, edit (already implemented), and delete in table view module. will impact table detail and script query panel.
   - done: right-click a row: Add Row, Duplicate Row(s), Delete Row(s) / Keep Row(s), and Set DEFAULT on a new row's cell. New rows are green with [DEFAULT] cells (left to the database: serial ids, defaults), deleted rows red and struck out; nothing is written until Save, which shows every DELETE / UPDATE / INSERT and runs them in one transaction. The table tab reloads after saving; in script results the new rows stay but can be edited only after running the query again. No keyboard shortcut for delete.
4. [x] in connection settings if read only un checked. there new check box that active. warning level (0. no warning at all 1. only truly  widely danger query get warning. 2. less danger query get warning, 3. even insert get warning), this user full if user accidenly select prod connection and run some query. even insert and update query can be danger in prod. (this query event count change data in tabel view panel)
   - done: "Warn before" in the connection form (0 nothing, 1 drop / truncate / update or delete without WHERE, 2 any change, 3 inserts too; old connections get 1). Scripts and the table query box ask before running, naming the connection; Cancel is the default. Saving table edits and importing already ask every time, with the SQL.
5. depend on warning level. right click table give options to drop table. reset table
6. 
