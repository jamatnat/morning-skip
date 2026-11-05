package io.github.jamatnat.morningskip;

/** Bounded delivery attempts to the vendor's attach-scoped native receiver. */
final class NativeReceiverClosePolicy {
    static final long WINDOW_MS = 3000;
    static final long RETRY_MS = 200;
    static final int MAX_SENDS = 16;
    private long deadline = -1, nextArm, nextSend;
    private int sends;

    boolean arm(long now) {
        if (active(now) || now < nextArm) return false;
        deadline = now + WINDOW_MS;
        nextArm = now + 10000;
        nextSend = now;
        sends = 0;
        return true;
    }

    boolean active(long now) { return deadline >= 0 && now <= deadline; }

    boolean shouldSend(long now, boolean enabled) {
        if (!enabled || !active(now)) { cancel(); return false; }
        if (now < nextSend || sends >= MAX_SENDS) return false;
        sends++;
        nextSend = now + RETRY_MS;
        return true;
    }

    int sends() { return sends; }
    void cancel() { deadline = -1; }
}
