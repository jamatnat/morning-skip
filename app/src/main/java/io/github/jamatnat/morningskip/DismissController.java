package io.github.jamatnat.morningskip;

/** Pure Java policy. Every observation must come from a new Accessibility window scan. */
final class DismissController {
    enum Observation { TARGET, CLEAR, BLOCKED, UNKNOWN }
    enum Action { NONE, CLICK, TAP, CONFIRMED, TIMED_OUT, CANCELLED }

    static final long VERIFY_DELAY_MS = 400L;
    static final long TAP_INTERVAL_MS = 800L;
    static final long CLEAR_STABLE_MS = 350L;
    static final long ATTEMPT_TIMEOUT_MS = 5000L;
    static final long RETRY_COOLDOWN_MS = 3000L;
    static final int MAX_TAPS = 2;

    private boolean pending;
    private int windowId = -1;
    private int taps;
    private long deadline;
    private long nextAction;
    private long clearSince = -1L;
    private long cooldownUntil;
    private int consecutiveTimeouts;
    private long retryDelay;

    boolean isPending() { return pending; }
    long retryDelayMs() { return retryDelay; }

    Action observe(long now, boolean enabled, Observation observation, int observedWindowId) {
        if (!enabled) {
            boolean wasPending = pending;
            pending = false;
            clearSince = -1L;
            cooldownUntil = 0L;
            consecutiveTimeouts = 0;
            retryDelay = 0L;
            return wasPending ? Action.CANCELLED : Action.NONE;
        }
        if (!pending) {
            if (observation != Observation.TARGET || now < cooldownUntil) return Action.NONE;
            pending = true;
            windowId = observedWindowId;
            taps = 0;
            clearSince = -1L;
            deadline = now + ATTEMPT_TIMEOUT_MS;
            nextAction = now + VERIFY_DELAY_MS;
            return Action.CLICK;
        }
        // elapsedRealtime, not uptime: never resume a stale attempt after a long CPU sleep.
        if (now >= deadline) {
            pending = false;
            clearSince = -1L;
            consecutiveTimeouts++;
            retryDelay = consecutiveTimeouts == 1 ? RETRY_COOLDOWN_MS :
                    consecutiveTimeouts == 2 ? 15000L : 60000L;
            cooldownUntil = now + retryDelay;
            return Action.TIMED_OUT;
        }
        if (observation == Observation.CLEAR) {
            if (clearSince < 0L) clearSince = now;
            if (now - clearSince >= CLEAR_STABLE_MS) {
                pending = false;
                clearSince = -1L;
                cooldownUntil = now + VERIFY_DELAY_MS;
                consecutiveTimeouts = 0;
                retryDelay = 0L;
                return Action.CONFIRMED;
            }
            return Action.NONE;
        }
        clearSince = -1L;
        // Null/empty trees and Settings/Assistant on top never count as successful dismissal.
        if (observation != Observation.TARGET) return Action.NONE;
        if (observedWindowId != windowId) {
            windowId = observedWindowId;
            nextAction = now + VERIFY_DELAY_MS;
            return Action.CLICK;
        }
        // Do not trust ACTION_CLICK=true. If the same greeting is still there, try a real tap.
        if (now >= nextAction && taps < MAX_TAPS) {
            taps++;
            nextAction = now + TAP_INTERVAL_MS;
            return Action.TAP;
        }
        return Action.NONE;
    }
}
