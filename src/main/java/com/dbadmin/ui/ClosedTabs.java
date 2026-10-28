package com.dbadmin.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Closed tabs that Ctrl+Shift+T opens again, newest first. Each is kept for a while (10 minutes in the window),
 * as what opens it again: a script's text, a table, a compare's file.
 */
final class ClosedTabs {

    private record Closed(String title, Runnable reopen, long at) {
    }

    private final Deque<Closed> closed = new ArrayDeque<>();
    private final long keepMillis;
    private final LongSupplier clock;

    ClosedTabs(long keepMillis, LongSupplier clock) {
        this.keepMillis = keepMillis;
        this.clock = clock;
    }

    void add(String title, Runnable reopen) {
        closed.push(new Closed(title, reopen, clock.getAsLong()));
        while (closed.size() > 50) closed.removeLast();
    }

    /** Opens the newest closed tab still kept; false when there is none. */
    boolean reopenLast() {
        dropOld();
        Closed last = closed.poll();
        if (last == null) return false;
        last.reopen().run();
        return true;
    }

    /** The newest closed tab still kept, for the menu. */
    Optional<String> lastTitle() {
        dropOld();
        return Optional.ofNullable(closed.peek()).map(Closed::title);
    }

    private void dropOld() {
        long now = clock.getAsLong();
        closed.removeIf(c -> now - c.at() > keepMillis);
    }
}
