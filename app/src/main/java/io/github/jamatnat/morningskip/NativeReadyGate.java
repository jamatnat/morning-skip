package io.github.jamatnat.morningskip;

/** Wait for an interactive, unobscured native button to stabilize before touching it. */
final class NativeReadyGate {
    static final long STABLE_MS = 350;
    static final long MAX_GAP_MS = 1000;
    private int window = -1;
    private String bounds = "";
    private long since = -1, last = -1;

    boolean observe(long now, boolean interactiveWindow, boolean target, int id, String rectangle) {
        if (!interactiveWindow || !target || id < 0 || rectangle == null || rectangle.isEmpty()) {
            reset(); return false;
        }
        if (window != id || !bounds.equals(rectangle) || last < 0 || now < last || now - last > MAX_GAP_MS) {
            window = id; bounds = rectangle; since = now;
        }
        last = now;
        return now - since >= STABLE_MS;
    }

    void reset() { window = -1; bounds = ""; since = last = -1; }
}
