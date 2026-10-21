package com.dbadmin.ui;

import com.dbadmin.service.compare.DataCompare;

/**
 * A cell of a data-compare result: the value on each side. Shown as "value" when equal and
 * "source → target" when not; the value panel shows the two sides separately. Only a
 * {@code compared} column's difference is a change (highlighted); a "show" column just displays.
 */
record ComparedValue(Object source, Object target, boolean inSource, boolean inTarget,
                     String sourceName, String targetName, boolean compared) {

    /** A difference that counts: highlighted in the grid. */
    boolean differs() {
        return compared && valuesDiffer();
    }

    private boolean valuesDiffer() {
        return inSource && inTarget && !DataCompare.normalize(source).equals(DataCompare.normalize(target));
    }

    @Override
    public String toString() {
        if (!inTarget) return show(source);
        if (!inSource) return show(target);
        return valuesDiffer() ? show(source) + "  →  " + show(target) : show(source);
    }

    private static String show(Object v) {
        return v == null ? "NULL" : v.toString();
    }
}
