package io.github.jamatnat.morningskip;

public final class NativeReadyTest {
    private static int checks;
    private static void eq(boolean expected, boolean actual) {
        checks++;
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        NativeReadyGate gate = new NativeReadyGate();
        eq(false, gate.observe(0, false, true, 42, "button"));
        eq(false, gate.observe(100, true, true, 42, "button"));
        eq(false, gate.observe(449, true, true, 42, "button"));
        eq(true, gate.observe(450, true, true, 42, "button"));
        eq(false, gate.observe(500, true, true, 43, "button"));
        eq(false, gate.observe(700, true, true, 43, "moved"));
        eq(true, gate.observe(1050, true, true, 43, "moved"));
        eq(false, gate.observe(1100, false, true, 43, "moved")); // OFF, DOZE or blocked windows.
        eq(false, gate.observe(1200, true, true, 43, "moved"));
        eq(false, gate.observe(1400, true, false, -1, ""));
        eq(false, gate.observe(1500, true, true, 43, "moved"));
        eq(false, gate.observe(10000, true, true, 43, "moved")); // CPU sleep invalidates old readiness.
        eq(true, gate.observe(10350, true, true, 43, "moved"));
        gate.reset();
        eq(false, gate.observe(10400, true, true, 43, "moved"));

        // Model the Android add-before-attach lifecycle: a guard that skips removal
        // while unattached but clears the reference can leave an uncloseable window.
        WindowModel early = new WindowModel();
        early.add(); early.close(); early.attach();
        eq(true, early.present); eq(false, early.referenced);
        early.close(); eq(true, early.present);
        WindowModel nativeClose = new WindowModel();
        nativeClose.add(); nativeClose.attach(); nativeClose.close();
        eq(false, nativeClose.present);
        System.out.println("Native readiness/lifecycle: " + checks + " checks passed");
    }

    private static final class WindowModel {
        boolean present, attached, referenced;
        void add() { present = referenced = true; }
        void attach() { attached = present; }
        void close() {
            if (referenced) {
                if (attached) present = false;
                referenced = false;
            }
        }
    }
}
