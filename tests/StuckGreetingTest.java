package io.github.jamatnat.morningskip;

import static io.github.jamatnat.morningskip.DismissController.Observation.*;

public final class StuckGreetingTest {
    private static int checks;
    private static void eq(Object expected, Object actual) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static boolean scan(StuckGreetingPolicy p, long now, int id, boolean timeout) {
        return p.observe(now, true, true, TARGET, id, timeout);
    }
    private static void visible(StuckGreetingPolicy p, long from, long to, int id) {
        for (long t = from; t <= to; t += 500) eq(false, scan(p, t, id, false));
    }
    public static void main(String[] args) {
        StuckGreetingPolicy p = new StuckGreetingPolicy();
        visible(p, 0, 4500, 42);
        eq(false, scan(p, 5000, 42, true));
        visible(p, 5500, 11500, 42);
        eq(false, scan(p, 12000, 42, false)); // One failed attempt is insufficient.
        eq(true, scan(p, 13000, 42, true));
        eq(false, scan(p, 13500, 42, true)); // Never a restart loop on a surviving window.
        p.recovered(14000);
        p.resetEvidence();
        visible(p, 15000, 19500, 43);
        eq(false, scan(p, 20000, 43, true));
        visible(p, 20500, 27500, 43);
        eq(false, scan(p, 28000, 43, true)); // Cooldown survives window replacement/toggles.

        p = new StuckGreetingPolicy();
        visible(p, 0, 4500, 42);
        eq(false, scan(p, 5000, 42, true));
        visible(p, 5500, 11500, 42);
        eq(false, p.observe(12000, true, true, UNKNOWN, -1, false));
        eq(false, scan(p, 12500, 42, true)); // An inaccessible tree breaks continuous evidence.
        visible(p, 13000, 24000, 42);
        eq(true, scan(p, 24500, 42, false));
        p.unavailable(24500);
        eq(false, scan(p, 25000, 42, true));
        eq(false, p.observe(30000, true, false, TARGET, 42, true));
        eq(false, p.observe(31000, false, true, TARGET, 42, true));

        // A long CPU sleep, overlay, or changed window cannot reuse old continuous visibility.
        p = new StuckGreetingPolicy();
        eq(false, scan(p, 0, 1, true));
        eq(false, scan(p, 500, 1, true));
        eq(false, scan(p, 20000, 1, false));
        eq(false, p.observe(20500, true, true, BLOCKED, 1, false));
        eq(false, scan(p, 21000, 2, true));
        visible(p, 21500, 32500, 2);
        eq(true, scan(p, 33000, 2, true));

        // Missing helper can be started later without restarting the watch/app.
        p = new StuckGreetingPolicy();
        eq(false, scan(p, 0, 42, true));
        visible(p, 500, 11500, 42);
        eq(true, scan(p, 12000, 42, true));
        p.unavailable(12000);
        visible(p, 12500, 71500, 42);
        eq(true, scan(p, 72000, 42, false));

        // A persisted service cooldown still blocks a fresh greeting after reconnection.
        p = new StuckGreetingPolicy();
        p.restoreCooldown(20000, 14000);
        eq(false, scan(p, 20000, 42, true));
        visible(p, 20500, 31500, 42);
        eq(false, scan(p, 32000, 42, true));

        String focused = "  mCurrentFocus=Window{abc123 u0 Launcher.SleepOutView}\n" +
                "  mFocusedApp=ActivityRecord{def456 u0 " + RecoveryProtocol.COMPONENT + " t2}\n" +
                "  mAwake=true mScreenOnEarly=true mScreenOnFully=true\n";
        eq(true, RecoveryProtocol.greetingFocused(focused));
        eq(false, RecoveryProtocol.greetingFocused(focused.replace("mAwake=true", "mAwake=false")));
        eq(false, RecoveryProtocol.greetingFocused(focused.replace("Launcher.SleepOutView}", "Settings}")));
        eq(false, RecoveryProtocol.greetingFocused(focused.replace(" u0 ", " u10 ")));
        eq(false, RecoveryProtocol.greetingFocused(focused.replace(RecoveryProtocol.COMPONENT, "other/pkg")));
        eq(true, RecoveryProtocol.authorized(10123, 10123, RecoveryProtocol.PREPARE));
        eq(false, RecoveryProtocol.authorized(10124, 10123, RecoveryProtocol.PREPARE));
        eq(false, RecoveryProtocol.authorized(2000, 10123, RecoveryProtocol.PREPARE));
        eq(true, RecoveryProtocol.authorized(2000, -1, RecoveryProtocol.STOP));
        eq(false, RecoveryProtocol.authorized(10123, 10123, RecoveryProtocol.STOP));
        eq(false, RecoveryProtocol.authorized(10123, 10123, "RUN am force-stop other.package"));
        eq(false, RecoveryProtocol.authorized(0, 10123, RecoveryProtocol.PREPARE));

        McuGreetingProtocol.State reply = McuGreetingProtocol.decodeQueryReply(10, 32, 0, new byte[0], 1100, 1000);
        eq(0, reply.great);
        eq(null, McuGreetingProtocol.decodeQueryReply(10, 36, 0, new byte[0], 1100, 1000));
        eq(null, McuGreetingProtocol.decodeQueryReply(10, 32, 0, new byte[0], 5000, 1000));
        eq(null, McuGreetingProtocol.decodeQueryReply(10, 32, 0, new byte[0], 1100, -1));
        eq(null, McuGreetingProtocol.decodeQueryReply(10, 32, 0, null, 1100, 1000));
        eq(null, McuGreetingProtocol.decodeQueryReply(10, 32, 1, new byte[0], 1100, 1000));
        System.out.println("Stuck greeting recovery: " + checks + " checks passed");
    }
}
