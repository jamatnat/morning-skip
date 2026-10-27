package io.github.jamatnat.morningskip;

/** Main-thread policy. Never retries without another fresh, decoded visible state. */
final class McuCloseController {
    enum Decision { CLOSE, HIDDEN, DISABLED, STALE, LIMITED }
    private boolean episode;
    private int count, attempts;
    private long started, lastEvent;
    private long lastSend = -1;

    Decision observe(long now, long received, boolean enabled, McuGreetingProtocol.State state) {
        if (!enabled) { reset(); return Decision.DISABLED; }
        if (now < received || now - received > 3000) return Decision.STALE;
        if (state.great == 0) { reset(); return Decision.HIDDEN; }
        if (!episode || state.count != count || now - lastEvent > 60000) {
            episode = true;
            count = state.count;
            attempts = 0;
            started = now;
        }
        lastEvent = now;
        if (attempts >= 3 || now - started > 15000 ||
                (lastSend >= 0 && now - lastSend < 1000)) return Decision.LIMITED;
        attempts++;
        lastSend = now;
        return Decision.CLOSE;
    }

    int attempts() { return attempts; }
    void reset() { episode = false; attempts = 0; }
}
