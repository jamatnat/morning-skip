package io.github.jamatnat.morningskip;

import static io.github.jamatnat.morningskip.DismissController.Action.*;
import static io.github.jamatnat.morningskip.DismissController.Observation.*;

/** Regression scenarios from the watch logs. Runs with a JDK, no Android or test library. */
public final class DismissControllerTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        DismissController c = new DismissController();
        equal(NONE, c.observe(0, true, UNKNOWN, -1));
        equal(NONE, c.observe(50, true, BLOCKED, -1));
        equal(CLICK, c.observe(100, true, TARGET, 7));
        // A click accepted by Android but with no visual effect must fall back to a tap.
        equal(NONE, c.observe(300, true, TARGET, 7));
        equal(TAP, c.observe(500, true, TARGET, 7));
        equal(NONE, c.observe(650, true, TARGET, 7));
        // A real disappearance is only confirmed after two stable observations.
        equal(NONE, c.observe(700, true, CLEAR, -1));
        equal(NONE, c.observe(900, true, CLEAR, -1));
        equal(CONFIRMED, c.observe(1050, true, CLEAR, -1));
        equal(false, c.isPending());

        c = new DismissController();
        equal(CLICK, c.observe(0, true, TARGET, 7));
        // Settings covering the greeting is neither a success nor permission to tap.
        equal(NONE, c.observe(500, true, BLOCKED, -1));
        equal(NONE, c.observe(1100, true, UNKNOWN, -1));
        equal(TAP, c.observe(1300, true, TARGET, 7));
        equal(NONE, c.observe(1700, true, CLEAR, -1));
        equal(NONE, c.observe(1900, true, BLOCKED, -1));
        equal(NONE, c.observe(2300, true, CLEAR, -1));
        equal(CONFIRMED, c.observe(2650, true, CLEAR, -1));

        c = new DismissController();
        equal(CLICK, c.observe(0, true, TARGET, 7));
        // Null roots never mean 'closed'; an unobservable attempt expires without another tap.
        equal(NONE, c.observe(1000, true, UNKNOWN, -1));
        equal(TIMED_OUT, c.observe(5000, true, UNKNOWN, -1));
        equal(NONE, c.observe(6000, true, TARGET, 7));
        equal(CLICK, c.observe(8000, true, TARGET, 7));
        // A long CPU sleep must not resume an old coordinate action.
        equal(TIMED_OUT, c.observe(60000, true, TARGET, 7));
        equal(15000L, c.retryDelayMs());
        equal(NONE, c.observe(70000, true, TARGET, 7));
        equal(CLICK, c.observe(75000, true, TARGET, 7));
        equal(TIMED_OUT, c.observe(80000, true, TARGET, 7));
        equal(60000L, c.retryDelayMs());
        equal(NONE, c.observe(100000, true, TARGET, 7));

        c = new DismissController();
        equal(CLICK, c.observe(0, true, TARGET, 7));
        equal(CANCELLED, c.observe(300, false, TARGET, 7));
        equal(NONE, c.observe(500, false, TARGET, 7));
        equal(false, c.isPending());
        equal(CLICK, c.observe(600, true, TARGET, 7));

        c = new DismissController();
        equal(CLICK, c.observe(0, true, TARGET, 7));
        equal(CLICK, c.observe(500, true, TARGET, 9));
        equal(NONE, c.observe(600, true, TARGET, 9));
        equal(TAP, c.observe(900, true, TARGET, 9));
        equal(TAP, c.observe(1700, true, TARGET, 9));
        equal(NONE, c.observe(3000, true, TARGET, 9));
        equal(TIMED_OUT, c.observe(5000, true, TARGET, 9));

        c = new DismissController();
        equal(CLICK, c.observe(0, true, TARGET, 7));
        equal(NONE, c.observe(100, true, CLEAR, -1));
        equal(NONE, c.observe(300, true, UNKNOWN, -1));
        equal(NONE, c.observe(500, true, CLEAR, -1));
        equal(NONE, c.observe(700, true, CLEAR, -1));
        equal(CONFIRMED, c.observe(850, true, CLEAR, -1));
        System.out.println("PASS: " + checks + " assertions across 6 regression scenarios");
    }
}
