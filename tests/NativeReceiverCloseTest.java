package io.github.jamatnat.morningskip;

public final class NativeReceiverCloseTest {
    private static int checks;
    private static void eq(boolean expected, boolean actual) {
        checks++;
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        NativeReceiverClosePolicy p = new NativeReceiverClosePolicy();
        eq(false, p.shouldSend(0, true)); // Never send without a greeting signal.
        eq(true, p.arm(100));
        eq(true, p.shouldSend(100, true)); // No arbitrary readiness delay.
        eq(false, p.shouldSend(101, true));
        eq(false, p.arm(250)); // Duplicate events cannot extend the probe.
        eq(false, p.shouldSend(300, true));
        int sends = 1;
        for (int t = 500; t <= 3100; t += 200) if (p.shouldSend(t, true)) sends++;
        eq(true, sends == NativeReceiverClosePolicy.MAX_SENDS);
        eq(false, p.shouldSend(3100, true));
        eq(false, p.shouldSend(3101, true));
        eq(false, p.arm(5000));
        eq(true, p.arm(10100));
        eq(false, p.shouldSend(10100, false)); // Disable cancels pending work.
        eq(false, p.shouldSend(10300, true));

        p = new NativeReceiverClosePolicy(); p.arm(0);
        eq(true, p.shouldSend(0, true));
        p.cancel(); // Controller-hidden, disconnect or denied broadcast.
        eq(false, p.shouldSend(200, true));
        eq(false, p.arm(201)); // A duplicate cannot resurrect a finished episode.

        p = new NativeReceiverClosePolicy(); p.arm(0);
        eq(false, p.shouldSend(60000, true)); // CPU suspension does not defer a stale send.
        eq(true, p.arm(60000));
        eq(true, p.shouldSend(60000, true));
        System.out.println("Native receiver bounds/cancellation: " + checks + " checks passed");
    }
}
