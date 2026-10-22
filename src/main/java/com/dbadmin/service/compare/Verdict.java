package com.dbadmin.service.compare;

import com.dbadmin.service.compare.DataCompare.RowStatus;

import java.util.List;

/**
 * What to do with a differing row of a data compare, decided by the user. Changes always go from
 * source to target: a row only in the source is inserted into the target, a changed row updates the
 * target, a row only in the target is deleted from it. Undecided rows have no verdict (null).
 */
public enum Verdict {
    INSERT("Insert"), UPDATE("Update"), DELETE("Delete"), SKIP("Skip");

    private final String label;

    Verdict(String label) {
        this.label = label;
    }

    /** "Insert into Prod", "Update Prod", "Delete from Prod", "Skip". */
    public String label(String target) {
        return switch (this) {
            case INSERT -> "Insert into " + target;
            case UPDATE -> "Update " + target;
            case DELETE -> "Delete from " + target;
            case SKIP -> "Skip";
        };
    }

    public String label() {
        return label;
    }

    /** Its color wherever a verdict is shown (green insert, amber update, red delete, grey skip). */
    public String color() {
        return switch (this) {
            case INSERT -> "#66bb6a";
            case UPDATE -> "#ffb74d";
            case DELETE -> "#ef5350";
            case SKIP -> "#b0bec5";
        };
    }

    /** The verdicts a row with this status can get; none for matching rows and duplicate keys. */
    public static List<Verdict> allowed(RowStatus status) {
        return switch (status) {
            case ONLY_SOURCE -> List.of(INSERT, SKIP);
            case CHANGED -> List.of(UPDATE, SKIP);
            case ONLY_TARGET -> List.of(DELETE, SKIP);
            case MATCH, DUPLICATE_KEY -> List.of();
        };
    }
}
