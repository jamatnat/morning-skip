package io.github.jamatnat.morningskip;

public final class HiddenCloseTest {
    private static int checks;
    private static void eq(boolean expected, boolean actual) {
        checks++;
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static boolean target(HiddenClosePolicy p, long time, int id, String bounds) {
        return p.observe(time, true, true, false, true, id, bounds);
    }
    public static void main(String[] args) {
        HiddenClosePolicy p = new HiddenClosePolicy();
        eq(true, p.arm(0));
        eq(false, p.arm(500)); // Events cannot keep a sleeping CPU awake indefinitely.
        eq(false, p.observe(0, true, true, false, false, -1, ""));
        eq(false, target(p, 50, 42, "button"));
        eq(false, target(p, 149, 42, "button"));
        eq(true, target(p, 150, 42, "button"));
        eq(false, target(p, 250, 42, "button"));
        eq(false, target(p, 600, 42, "button"));
        eq(true, target(p, 950, 42, "button"));
        eq(false, target(p, 1350, 42, "button"));
        eq(false, target(p, 1800, 42, "button")); // Two clicks maximum.
        eq(false, p.active(3001));
        eq(false, p.arm(4000)); // Cancel/end does not bypass the arm throttle.
        eq(true, p.arm(10000));
        eq(false, target(p, 10000, 42, "button"));
        eq(false, target(p, 10100, 42, "moved"));
        eq(true, target(p, 10200, 42, "moved"));

        p = new HiddenClosePolicy(); p.arm(0);
        eq(false, target(p, 0, 42, "button"));
        eq(false, p.observe(100, true, false, true, true, 42, "button")); // Screen wakes.
        eq(false, target(p, 200, 42, "button")); // Old attempt cannot resume.

        p = new HiddenClosePolicy(); p.arm(0);
        eq(false, target(p, 0, 42, "button"));
        eq(false, p.observe(100, false, true, false, true, 42, "button"));
        eq(false, target(p, 200, 42, "button")); // Auto-close OFF cancels.

        p = new HiddenClosePolicy(); p.arm(0);
        eq(false, target(p, 0, 42, "button"));
        eq(false, p.observe(100, true, true, false, false, -1, ""));
        eq(false, target(p, 200, 42, "button")); // Missing/failed refresh breaks readiness.
        eq(false, target(p, 300, 43, "button")); // Window replacement breaks readiness.
        eq(true, target(p, 400, 43, "button"));

        p = new HiddenClosePolicy(); p.arm(0);
        eq(false, target(p, 0, 42, "button"));
        eq(false, target(p, 1000, 42, "button")); // A long CPU sleep is not stability.
        eq(true, target(p, 1100, 42, "button"));
        p.cancel();
        eq(false, target(p, 1200, 42, "button"));
        System.out.println("Guarded hidden close: " + checks + " checks passed");
    }
}
