package com.dbadmin.ui;

import com.dbadmin.service.DatabaseSession.RowUpdate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Changes not saved yet: cell edits, new rows and deleted rows. Edits change the shown rows in place; for
 * each edited row (by identity: two rows can hold equal values) the original values of its edited columns
 * are kept, to revert them and to know what to save. No JavaFX here, so it can be tested on its own.
 */
final class PendingEdits {

    /** A cell of a new row left to the database: not in the INSERT, so the column's default applies. */
    enum Default {
        VALUE;

        @Override
        public String toString() {
            return "[DEFAULT]";
        }
    }

    /** Edited rows -> original values of their edited columns (by column index). */
    private final IdentityHashMap<List<Object>, Map<Integer, Object>> originals = new IdentityHashMap<>();
    /** New rows, in the order they were added. */
    private final List<List<Object>> added = new ArrayList<>();
    /** Shown rows to delete, in the order they were marked. */
    private final List<List<Object>> deleted = new ArrayList<>();

    /** Gives a cell a new value; giving it back its original value undoes the edit. */
    void set(List<Object> row, int column, Object value) {
        Map<Integer, Object> cells = originals.computeIfAbsent(row, r -> new HashMap<>());
        Object original = cells.containsKey(column) ? cells.get(column) : row.get(column);
        boolean same = original == null || original == Default.VALUE || value == Default.VALUE ? value == original
                : value != null && original.toString().equals(value.toString());
        if (same) {
            cells.remove(column);
            row.set(column, original);
        } else {
            cells.putIfAbsent(column, original);
            row.set(column, value);
        }
        if (cells.isEmpty()) originals.remove(row);
    }

    boolean isEdited(List<Object> row, int column) {
        return originals.containsKey(row) && originals.get(row).containsKey(column);
    }

    void revert(List<Object> row, int column) {
        Map<Integer, Object> cells = originals.get(row);
        if (cells == null || !cells.containsKey(column)) return;
        row.set(column, cells.remove(column));
        if (cells.isEmpty()) originals.remove(row);
    }

    /** A new row (its cells {@link Default#VALUE} or copied values), to be inserted. */
    void add(List<Object> row) {
        added.add(row);
    }

    boolean isAdded(List<Object> row) {
        return indexOf(added, row) >= 0;
    }

    /** Marks a shown row to be deleted; a new row is simply dropped. True: the row should leave the grid (it was new). */
    boolean delete(List<Object> row) {
        int i = indexOf(added, row);
        if (i >= 0) {
            added.remove(i);
            originals.remove(row);
            return true;
        }
        if (indexOf(deleted, row) < 0) deleted.add(row);
        return false;
    }

    /** Keeps a row marked to be deleted after all. */
    void restore(List<Object> row) {
        int i = indexOf(deleted, row);
        if (i >= 0) deleted.remove(i);
    }

    boolean isDeleted(List<Object> row) {
        return indexOf(deleted, row) >= 0;
    }

    /** Puts every original value back and keeps every row; returns the new rows, which should leave the grid. */
    List<List<Object>> revertAll() {
        originals.forEach((row, cells) -> cells.forEach(row::set));
        originals.clear();
        deleted.clear();
        List<List<Object>> dropped = List.copyOf(added);
        added.clear();
        return dropped;
    }

    /** Forgets the changes but keeps the rows as they are now: after they were saved. */
    void clear() {
        originals.clear();
        added.clear();
        deleted.clear();
    }

    boolean isEmpty() {
        return originals.isEmpty() && added.isEmpty() && deleted.isEmpty();
    }

    /** Existing rows with edited cells (not counting new or deleted rows). */
    int rowCount() {
        return (int) originals.keySet().stream().filter(r -> !isAdded(r) && !isDeleted(r)).count();
    }

    int cellCount() {
        return originals.entrySet().stream().filter(e -> !isAdded(e.getKey()) && !isDeleted(e.getKey()))
                .mapToInt(e -> e.getValue().size()).sum();
    }

    /** E.g. "2 changed, 1 new, 3 deleted row(s)". */
    String summary() {
        List<String> parts = new ArrayList<>();
        if (rowCount() > 0) parts.add(rowCount() + " changed");
        if (!added.isEmpty()) parts.add(added.size() + " new");
        if (!deleted.isEmpty()) parts.add(deleted.size() + " deleted");
        return parts.isEmpty() ? "no changes" : String.join(", ", parts) + " row(s)";
    }

    /**
     * One update per edited row that is neither new nor deleted: its primary key values (found among
     * {@code columnNames}, ignoring case) and its edited columns' new values, in column order.
     */
    List<RowUpdate> updates(List<String> columnNames, List<String> primaryKey) {
        List<RowUpdate> updates = new ArrayList<>();
        for (Map.Entry<List<Object>, Map<Integer, Object>> e : originals.entrySet()) {
            List<Object> row = e.getKey();
            if (isAdded(row) || isDeleted(row)) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            new TreeMap<>(e.getValue()).keySet().forEach(c -> values.put(columnNames.get(c), row.get(c)));
            updates.add(new RowUpdate(key(row, columnNames, primaryKey), values));
        }
        return updates;
    }

    /** The primary key of each row to delete, in the order they were marked; with their original values. */
    List<Map<String, Object>> deletes(List<String> columnNames, List<String> primaryKey) {
        List<Map<String, Object>> keys = new ArrayList<>();
        for (List<Object> row : deleted) {
            List<Object> original = new ArrayList<>(row);
            Map<Integer, Object> cells = originals.get(row);
            if (cells != null) cells.forEach(original::set); // the row as it is in the database
            keys.add(key(original, columnNames, primaryKey));
        }
        return keys;
    }

    /** Each new row as column -> value, without the cells left to the database ({@link Default#VALUE}). */
    List<Map<String, Object>> inserts(List<String> columnNames) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Object> row : added) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int c = 0; c < row.size(); c++) {
                if (row.get(c) != Default.VALUE) values.put(columnNames.get(c), row.get(c));
            }
            rows.add(values);
        }
        return rows;
    }

    private static Map<String, Object> key(List<Object> row, List<String> columnNames, List<String> primaryKey) {
        Map<String, Object> key = new LinkedHashMap<>();
        for (String k : primaryKey) {
            int i = indexOf(columnNames, k);
            if (i < 0) throw new IllegalStateException("The primary key column " + k + " is not in the result");
            key.put(k, row.get(i));
        }
        return key;
    }

    /** By identity: two rows can hold equal values. */
    private static int indexOf(List<List<Object>> rows, List<Object> row) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == row) return i;
        }
        return -1;
    }

    private static int indexOf(List<String> names, String column) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(column)) return i;
        }
        return -1;
    }
}
