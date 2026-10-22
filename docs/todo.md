1. new tools -> full scan. to find some data in multiple table and columns.
2. make sure table view panel in table detail tab and script query panel use same module. so no redundancy.
3. add feature add, duplicate, edit (already implemented), and delete in table view module. will impact table detail and script query panel.
4. in connection settings if read only un checked. there new check box that active. warning level (0. no warning at all 1. only truly  widely danger query get warning. 2. less danger query get warning, 3. even insert get warning), this user full if user accidenly select prod connection and run some query. even insert and update query can be danger in prod. (this query event count change data in tabel view panel)
5. depend on warning level. right click table give options to drop table. reset table
6. 
