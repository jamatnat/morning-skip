package io.github.jamatnat.morningskip;

import java.util.regex.Pattern;

/** Fixed operations only; no caller-supplied command, package, component or shell text. */
final class RecoveryProtocol {
    static final String SOCKET = "morningskip.recovery.v1";
    static final String PACKAGE = "io.github.jamatnat.morningskip";
    static final String COMPONENT = "com.oplus.wearable.sysui/" +
            "com.google.android.clockwork.sysui.mainui.activity.SysUiActivity";
    static final String STATUS = "STATUS 1", PREPARE = "PREPARE 1", COMMIT = "COMMIT 1";
    static final String STOP = "STOP 1";
    private static final Pattern FOCUS = Pattern.compile(
            "(?m)^\\s*mCurrentFocus=Window\\{[0-9a-fA-F]+ u0 Launcher\\.SleepOutView\\}\\s*$");

    static boolean greetingFocused(String dump) {
        return dump != null && FOCUS.matcher(dump).find() &&
                dump.contains("mFocusedApp=ActivityRecord{") && dump.contains(" u0 " + COMPONENT) &&
                dump.contains("mAwake=true mScreenOnEarly=true mScreenOnFully=true");
    }

    static boolean authorized(int peerUid, int appUid, String operation) {
        if (peerUid == 2000) return STATUS.equals(operation) || STOP.equals(operation);
        return appUid >= 10000 && peerUid == appUid &&
                (STATUS.equals(operation) || PREPARE.equals(operation));
    }
}
