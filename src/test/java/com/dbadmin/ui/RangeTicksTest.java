package com.dbadmin.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Click and shift-click in every picker (no JavaFX thread needed: properties only). */
class RangeTicksTest {

    private final List<BooleanProperty> rows = new ArrayList<>();
    private final RangeTicks ticks = new RangeTicks();

    RangeTicksTest() {
        for (int i = 0; i < 6; i++) rows.add(new SimpleBooleanProperty(false));
    }

    private void click(int at, boolean shift) {
        ticks.click(at, shift, rows.size(), rows::get, i -> i != 4); // row 4 cannot be ticked
    }

    private List<Boolean> state() {
        return rows.stream().map(BooleanProperty::get).toList();
    }

    @Test
    void shiftClickTicksAndUnticksARange() {
        click(1, false);
        click(3, true);
        assertEquals(List.of(false, true, true, true, false, false), state());
        // from the last clicked (3) up to 0: the clicked row was off, so all go on
        click(0, true);
        assertEquals(List.of(true, true, true, true, false, false), state());
        // unticking: the clicked row was on, so the range goes off
        click(2, true);
        assertEquals(List.of(false, false, false, true, false, false), state());
    }

    @Test
    void rowsThatCannotBeTickedAreLeftAlone() {
        click(3, false);
        click(5, true);
        assertEquals(List.of(false, false, false, true, false, true), state());
        click(4, false);
        assertEquals(List.of(false, false, false, true, false, true), state(), "a click on it does nothing");
    }

    @Test
    void afterAResetAShiftClickIsAPlainClick() {
        click(1, false);
        ticks.reset();
        click(3, true);
        assertEquals(List.of(false, true, false, true, false, false), state());
    }
}
