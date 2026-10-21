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
 * Cell edits not saved yet. Edits change the shown rows in place; for each edited row (by identity:
 * two rows can hold equal values) the original values of its edited columns are kept, to revert
 * them and to know what to save. No JavaFX here, so it can be tested on its own.
 */
final class PendingEdits {

    /** Edited rows -> original values of their edited columns (by column index). */
    private final IdentityHashMap<List<Object>, Map<Integer, Object>> originals = new IdentityHashMap<>();

    /** Gives a cell a new value; giving it back its original value undoes the edit. */
    void set(List<Object> row, int column, Object value) {
        Map<Integer, Object> cells = originals.computeIfAbsent(row, r -> new HashMap<>());
        Object original = cells.containsKey(column) ? cells.get(column) : row.get(column);
        boolean same = original == null ? value == null : value != null && original.toString().equals(value.toString());
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

    /** Puts every original value back. */
    void revertAll() {
        originals.forEach((row, cells) -> cells.forEach(row::set));
        originals.clear();
    }

    /** Forgets the edits but keeps the rows as edited: after they were saved, or for new rows. */
    void clear() {
        originals.clear();
    }

    boolean isEmpty() {
        return originals.isEmpty();
    }

    int rowCount() {
        return originals.size();
    }

    int cellCount() {
        return originals.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * One update per edited row: its primary key values (found among {@code columnNames}, ignoring
     * case) and its edited columns' new values, in column order.
     */
    List<RowUpdate> updates(List<String> columnNames, List<String> primaryKey) {
        List<RowUpdate> updates = new ArrayList<>();
        for (Map.Entry<List<Object>, Map<Integer, Object>> e : originals.entrySet()) {
            List<Object> row = e.getKey();
            Map<String, Object> key = new LinkedHashMap<>();
            for (String k : primaryKey) {
                int i = indexOf(columnNames, k);
                if (i < 0) throw new IllegalStateException("The primary key column " + k + " is not in the result");
                key.put(k, row.get(i));
            }
            Map<String, Object> values = new LinkedHashMap<>();
            new TreeMap<>(e.getValue()).keySet().forEach(c -> values.put(columnNames.get(c), row.get(c)));
            updates.add(new RowUpdate(key, values));
        }
        return updates;
    }

    private static int indexOf(List<String> names, String column) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(column)) return i;
        }
        return -1;
    }
}
