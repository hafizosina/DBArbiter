package com.dbadmin.ui;

import javafx.beans.property.BooleanProperty;

import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * How a list of checkboxes is ticked with the mouse, the same in every picker: a click ticks or unticks one row; a
 * shift-click gives every row from the last one clicked to this one the state the clicked row gets (rows that cannot
 * be ticked are left alone). Holds only the last row clicked; {@link #reset()} when other rows are shown.
 */
final class RangeTicks {

    private int anchor = -1;

    /**
     * A click on row {@code at} of {@code size} rows; {@code row} gives each row's checkbox value, {@code usable}
     * says which rows can be ticked.
     */
    void click(int at, boolean shift, int size, IntFunction<BooleanProperty> row, IntPredicate usable) {
        if (at < 0 || at >= size || !usable.test(at)) return;
        boolean on = !row.apply(at).get();
        if (shift && anchor >= 0 && anchor < size) {
            for (int i = Math.min(anchor, at); i <= Math.max(anchor, at); i++) {
                if (usable.test(i)) row.apply(i).set(on);
            }
        } else {
            row.apply(at).set(on);
        }
        anchor = at;
    }

    void reset() {
        anchor = -1;
    }
}
