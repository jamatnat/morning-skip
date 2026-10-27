package io.github.jamatnat.morningskip;

/** Connects fresh UI observations to bounded MCU queries; never sends a dismissal itself. */
final class GreetingRecoveryPolicy {
    enum Evidence { NONE, UI_STILL_VISIBLE, UI_CLEAR }
    static final long QUERY_INTERVAL_MS = 60000L;
    static final int MAX_QUERIES = 3;

    private int windowId = -1;
    private int queries;
    private long lastQuery = -1L;
    private long clearSince = -1L;
    private long hiddenSince = -1L;
    private Evidence lastEvidence = Evidence.NONE;
    private int reportedWindowId = -1;

    void onMcuState(long now, boolean hidden) {
        if (!hidden) {
            hiddenSince = -1L;
            lastEvidence = Evidence.NONE;
            reportedWindowId = -1;
        } else if (hiddenSince < 0L) {
            // Repeated hidden packets must not restart verification or flood the log.
            hiddenSince = now;
            lastEvidence = Evidence.NONE;
            clearSince = -1L;
        }
    }

    Evidence observeUi(long now, boolean enabled, DismissController.Observation observation,
                       int observedWindowId) {
        if (!enabled) { reset(); return Evidence.NONE; }
        if (observation == DismissController.Observation.CLEAR) {
            if (clearSince < 0L) clearSince = now;
            if (now - clearSince < DismissController.CLEAR_STABLE_MS) return Evidence.NONE;
            windowId = -1;
            queries = 0;
            if (hiddenSince >= 0L && lastEvidence != Evidence.UI_CLEAR) {
                lastEvidence = Evidence.UI_CLEAR;
                reportedWindowId = -1;
                return Evidence.UI_CLEAR;
            }
        } else {
            clearSince = -1L;
            if (observation == DismissController.Observation.TARGET && hiddenSince >= 0L &&
                    (lastEvidence != Evidence.UI_STILL_VISIBLE || reportedWindowId != observedWindowId)) {
                lastEvidence = Evidence.UI_STILL_VISIBLE;
                reportedWindowId = observedWindowId;
                return Evidence.UI_STILL_VISIBLE;
            }
        }
        return Evidence.NONE;
    }

    boolean allowQuery(long now, boolean enabled, DismissController.Observation observation,
                       int observedWindowId, boolean timedOut) {
        if (!enabled || !timedOut || observation != DismissController.Observation.TARGET ||
                observedWindowId < 0) return false;
        if (windowId != observedWindowId) {
            windowId = observedWindowId;
            queries = 0;
        }
        if (queries >= MAX_QUERIES || (lastQuery >= 0L && now - lastQuery < QUERY_INTERVAL_MS))
            return false;
        queries++;
        lastQuery = now;
        return true;
    }

    int queries() { return queries; }
    long hiddenAgeMs(long now) { return hiddenSince < 0L ? -1L : now - hiddenSince; }

    void reset() {
        windowId = reportedWindowId = -1;
        queries = 0;
        lastQuery = clearSince = hiddenSince = -1L;
        lastEvidence = Evidence.NONE;
    }
}
