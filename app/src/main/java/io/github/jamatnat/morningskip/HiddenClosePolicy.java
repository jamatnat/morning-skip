package io.github.jamatnat.morningskip;

/** Bounded OFF-screen native-button attempts; a controller packet never authorizes a click. */
final class HiddenClosePolicy {
    static final long WINDOW_MS = 3000;
    static final long STABLE_MS = 100;
    static final long MAX_GAP_MS = 500;
    private long deadline = -1, nextArm, since = -1, last = -1, nextClick;
    private int window = -1, attempts;
    private String bounds = "";

    boolean arm(long now) {
        if (active(now) || now < nextArm) return false;
        deadline = now + WINDOW_MS;
        nextArm = now + 10000;
        since = last = -1;
        window = -1; bounds = ""; attempts = 0; nextClick = now;
        return true;
    }

    boolean active(long now) { return deadline >= 0 && now <= deadline; }

    boolean observe(long now, boolean enabled, boolean displayOff, boolean interactive,
                    boolean liveTarget, int windowId, String rectangle) {
        if (!enabled || !displayOff || interactive || !active(now)) { cancel(); return false; }
        if (!liveTarget || windowId < 0 || rectangle == null || rectangle.isEmpty()) {
            since = last = -1; window = -1; bounds = "";
            return false;
        }
        if (window != windowId || !bounds.equals(rectangle) || last < 0 || now < last ||
                now - last > MAX_GAP_MS) {
            window = windowId; bounds = rectangle; since = now;
        }
        last = now;
        if (now - since < STABLE_MS || now < nextClick || attempts >= 2) return false;
        attempts++;
        nextClick = now + 800;
        return true;
    }

    int attempts() { return attempts; }
    void cancel() { deadline = since = last = -1; window = -1; bounds = ""; }
}
