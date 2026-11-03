package io.github.jamatnat.morningskip;

/** Requires fresh, continuous, interactive UI evidence and two failed normal attempts. */
final class StuckGreetingPolicy {
    static final long VISIBLE_MS = 12000;
    static final long MAX_SCAN_GAP_MS = 1500;
    static final long COOLDOWN_MS = 10 * 60000L;
    private int window = -1, failures;
    private long visibleSince = -1, lastTarget = -1, clearSince = -1, nextAllowed;
    private boolean attempted;

    boolean observe(long now, boolean enabled, boolean interactive,
                    DismissController.Observation observation, int id, boolean timedOut) {
        if (!enabled) { resetEvidence(); return false; }
        if (!interactive || observation != DismissController.Observation.TARGET || id < 0) {
            visibleSince = lastTarget = -1;
            if (interactive && observation == DismissController.Observation.CLEAR) {
                if (clearSince < 0) clearSince = now;
                if (now - clearSince >= DismissController.CLEAR_STABLE_MS) resetEvidence();
            } else clearSince = -1;
            return false;
        }
        clearSince = -1;
        if (window != id) { resetEvidence(); window = id; }
        if (lastTarget < 0 || now < lastTarget || now - lastTarget > MAX_SCAN_GAP_MS)
            visibleSince = now;
        lastTarget = now;
        if (timedOut) failures++;
        if (failures < 2 || now - visibleSince < VISIBLE_MS || attempted || now < nextAllowed)
            return false;
        attempted = true;
        return true;
    }

    void unavailable(long now) {
        // A helper started later can recover the same window, without probing every scan.
        attempted = false;
        nextAllowed = Math.max(nextAllowed, now + 60000);
    }

    void recovered(long now) { nextAllowed = now + COOLDOWN_MS; }
    void restoreCooldown(long now, long previous) {
        if (previous > 0 && previous <= now) nextAllowed = previous + COOLDOWN_MS;
    }
    void resetEvidence() {
        window = -1; failures = 0; visibleSince = lastTarget = clearSince = -1;
        attempted = false;
        // User toggles and disappearing windows never clear the restart cooldown.
    }
}
