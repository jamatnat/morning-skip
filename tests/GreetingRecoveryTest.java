package io.github.jamatnat.morningskip;

import static io.github.jamatnat.morningskip.DismissController.Observation.*;
import static io.github.jamatnat.morningskip.GreetingRecoveryPolicy.Evidence.*;

public final class GreetingRecoveryTest {
    private static int checks;
    private static void eq(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        GreetingRecoveryPolicy p = new GreetingRecoveryPolicy();
        // October 25 pattern: MCU hid the greeting, but the Android window reappeared later.
        p.onMcuState(1000, true);
        eq(NONE, p.observeUi(2000, true, UNKNOWN, -1)); // Screen OFF / no accessible tree.
        eq(NONE, p.observeUi(3000, true, BLOCKED, -1)); // Alarm, Settings or another overlay.
        eq(NONE, p.observeUi(4000, true, CLEAR, -1));
        eq(NONE, p.observeUi(4349, true, CLEAR, -1));
        eq(UI_CLEAR, p.observeUi(4350, true, CLEAR, -1));
        eq(UI_STILL_VISIBLE, p.observeUi(100000, true, TARGET, 193));
        eq(99000L, p.hiddenAgeMs(100000));
        p.onMcuState(100100, true); // Duplicate hidden packet keeps original evidence age.
        eq(99100L, p.hiddenAgeMs(100100));
        eq(NONE, p.observeUi(100200, true, TARGET, 193));
        eq(NONE, p.observeUi(100300, true, UNKNOWN, -1));
        eq(NONE, p.observeUi(100400, true, TARGET, 193)); // No log storm on missing trees.

        DismissController ui = new DismissController();
        eq(DismissController.Action.CLICK, ui.observe(100000, true, TARGET, 193));
        eq(false, p.allowQuery(100000, true, TARGET, 193, false));
        eq(DismissController.Action.TAP, ui.observe(100400, true, TARGET, 193));
        eq(DismissController.Action.TAP, ui.observe(101200, true, TARGET, 193));
        eq(DismissController.Action.TIMED_OUT, ui.observe(105000, true, TARGET, 193));
        eq(true, p.allowQuery(105000, true, TARGET, 193, true));
        eq(false, p.allowQuery(113000, true, TARGET, 193, true)); // Original 3-second retry.
        eq(false, p.allowQuery(164999, true, TARGET, 193, true));
        eq(true, p.allowQuery(165000, true, TARGET, 193, true));
        eq(true, p.allowQuery(225000, true, TARGET, 193, true));
        eq(false, p.allowQuery(600000, true, TARGET, 193, true)); // Cap even after a long wait.
        eq(3, p.queries());
        eq(false, p.allowQuery(600001, false, TARGET, 193, true));
        eq(false, p.allowQuery(600002, true, UNKNOWN, -1, true));
        eq(false, p.allowQuery(600003, true, BLOCKED, -1, true));
        eq(false, p.allowQuery(600004, true, CLEAR, -1, true));
        eq(false, p.allowQuery(600005, true, TARGET, -1, true));

        // A single clear scan or an intervening unknown scan must not replenish the query budget.
        eq(NONE, p.observeUi(601000, true, CLEAR, -1));
        eq(NONE, p.observeUi(601400, true, UNKNOWN, -1));
        eq(NONE, p.observeUi(601500, true, CLEAR, -1));
        eq(NONE, p.observeUi(601600, true, TARGET, 193));
        eq(false, p.allowQuery(601600, true, TARGET, 193, true));
        eq(NONE, p.observeUi(601650, true, CLEAR, -1));
        eq(UI_CLEAR, p.observeUi(602000, true, CLEAR, -1));
        eq(UI_STILL_VISIBLE, p.observeUi(602100, true, TARGET, 193));
        eq(true, p.allowQuery(607000, true, TARGET, 193, true));
        eq(false, p.allowQuery(607001, true, TARGET, 194, true)); // New window keeps global throttle.
        eq(true, p.allowQuery(667000, true, TARGET, 194, true));

        // A normal fresh query reply uses the existing MCU close policy, not a blind command.
        McuCloseController mcu = new McuCloseController();
        McuGreetingProtocol.State visible = McuGreetingProtocol.decode(10, 32, 0,
                new byte[] {8, 1, 16, 103});
        eq(McuCloseController.Decision.CLOSE, mcu.observe(668000, 668000, true, visible));
        p.onMcuState(668000, false);
        eq(NONE, p.observeUi(668100, true, TARGET, 194));
        p.onMcuState(668200, true);
        eq(UI_STILL_VISIBLE, p.observeUi(668300, true, TARGET, 194));
        eq(NONE, p.observeUi(668400, true, BLOCKED, -1));
        eq(NONE, p.observeUi(668500, true, CLEAR, -1));
        eq(UI_CLEAR, p.observeUi(668850, true, CLEAR, -1));
        eq(NONE, p.observeUi(669000, false, UNKNOWN, -1));
        eq(-1L, p.hiddenAgeMs(669000));
        eq(0, p.queries());
        eq(NONE, p.observeUi(670000, true, TARGET, 194));
        eq(true, p.allowQuery(675000, true, TARGET, 194, true));
        p.reset(); // Service disconnect / destroy.
        eq(0, p.queries());
        eq(NONE, p.observeUi(676000, true, TARGET, 194));
        System.out.println("UI/MCU recovery: " + checks + " checks passed");
    }
}
