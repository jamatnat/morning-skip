package io.github.jamatnat.morningskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SysUiProbeService extends AccessibilityService {
    private static final String TAG = "MorningSkip";
    private static final String TARGET_PACKAGE = "com.oplus.wearable.sysui";
    private static final long MAX_EVENT_LOG_BYTES = 1024 * 1024L;
    private static final long MAX_DIAG_LOG_BYTES = 4 * 1024 * 1024L;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private final Handler handler = new Handler(Looper.getMainLooper());
    static final Object DIAGNOSTICS_LOCK = new Object();

    private BroadcastReceiver screenReceiver;
    private long lastGreetingActionElapsed = 0L;
    private static final long GREETING_DEBOUNCE_MS = 5000L;
    private long lastVisibleGreetingDismissCheckElapsed = 0L;
    private static final long VISIBLE_GREETING_SCAN_THROTTLE_MS = 120L;
    private static final long WATCHDOG_ON_MS = 500L;
    private static final long WATCHDOG_DOZE_MS = 750L;
    private static final long WATCHDOG_OFF_MS = 5000L;
    private static final long VERIFY_POLL_MS = 150L;
    private static final long HEALTH_LOG_MS = 60000L;
    private int lastWatchdogDisplayState = Integer.MIN_VALUE;
    private boolean lastWatchdogInteractive;
    private boolean lastWatchdogEnabled;
    private long lastHealthLogElapsed;
    private long previousWatchdogElapsed;
    private long previousWatchdogUptime;
    private long lastScanLogElapsed;
    private long lastMcuHintElapsed;
    private String lastScanSummary = "";
    private volatile boolean serviceRunning;
    static volatile boolean connectedForUi;
    private final McuCloseController mcuCloseController = new McuCloseController();
    private final GreetingRecoveryPolicy greetingRecovery = new GreetingRecoveryPolicy();
    private PowerManager.WakeLock mcuWakeLock;
    private boolean mcuVerificationPending;
    private long mcuCloseSentElapsed;
    private long lastMcuQueryElapsed = -1;
    private final Runnable verifyMcuQuery = () -> {
        if (serviceRunning && autoCloseEnabled() && mcuVerificationPending &&
                SystemClock.elapsedRealtime() - mcuCloseSentElapsed <= 3000) {
            queryMcuGreeting("verify-close");
        }
    };
    private final Runnable verifyMcuTimeout = () -> {
        if (mcuVerificationPending) {
            writeDiag("MCU v1.0 CLOSE_UNVERIFIED: no hidden-state report; send is not proof of closure");
        }
        finishMcuVerification();
    };
    private DisplayManager displayManager;
    private PowerManager.WakeLock dismissWakeLock;
    private SharedPreferences preferences;
    private final DismissController dismissController = new DismissController();
    private final Runnable mcuScanHint = () -> {
        long elapsed = SystemClock.elapsedRealtime();
        if (serviceRunning && autoCloseEnabled() && elapsed - lastMcuHintElapsed >= 1000L) {
            lastMcuHintElapsed = elapsed;
            kickWatchdog();
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (prefs, key) -> {
        if (MainActivity.KEY_MCU_AUTOCLOSE.equals(key)) {
            if (!autoCloseEnabled()) {
                dismissController.observe(SystemClock.elapsedRealtime(), false,
                        DismissController.Observation.UNKNOWN, -1);
                releaseDismissWakeLock();
                mcuCloseController.reset();
                greetingRecovery.reset();
                finishMcuVerification();
            } else {
                initMcuBridge(true);
            }
            writeDiag("A11Y v1.0 autoClose=" + autoCloseEnabled());
            kickWatchdog();
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { changed(id); }
        @Override public void onDisplayRemoved(int id) { changed(id); }
        @Override public void onDisplayChanged(int id) { changed(id); }
        private void changed(int id) {
            if (id == Display.DEFAULT_DISPLAY) kickWatchdog();
        }
    };

    // Direct vendor MCU bridge (loaded only via reflection; no compile-time hidden API dependency).
    private Object mcuManager;
    private Object mcuListener;
    private Class<?> mcuManagerClass;
    private Class<?> mcuListenerClass;
    private boolean mcuRegistered = false;

    // Handler timers do NOT wake a suspended CPU. Display callbacks supplement polling;
    // a short PARTIAL_WAKE_LOCK only finishes an already detected greeting attempt.
    private final Runnable greetingWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!serviceRunning) return;
            long nextDelay = WATCHDOG_OFF_MS;
            try {
                boolean enabled = autoCloseEnabled();
                int state = currentDisplayState();
                boolean interactive = isInteractiveNow();
                long elapsed = SystemClock.elapsedRealtime();
                long uptime = SystemClock.uptimeMillis();
                long gap = previousWatchdogElapsed == 0L ? 0L : elapsed - previousWatchdogElapsed;
                long sleepGap = previousWatchdogElapsed == 0L ? 0L :
                        Math.max(0L, gap - (uptime - previousWatchdogUptime));
                previousWatchdogElapsed = elapsed;
                previousWatchdogUptime = uptime;
                if (state != lastWatchdogDisplayState || interactive != lastWatchdogInteractive ||
                        enabled != lastWatchdogEnabled || elapsed - lastHealthLogElapsed >= HEALTH_LOG_MS) {
                    lastWatchdogDisplayState = state;
                    lastWatchdogInteractive = interactive;
                    lastWatchdogEnabled = enabled;
                    lastHealthLogElapsed = elapsed;
                    writeDiag("A11Y v1.0 health display=" + displayStateName(state) +
                            " interactive=" + interactive + " autoClose=" + enabled +
                            " tickGapMs=" + gap + " cpuSleepGapMs=" + sleepGap +
                            " pending=" + dismissController.isPending());
                } else if (sleepGap > 10000L) {
                    writeDiag("A11Y v1.0 resumed tickGapMs=" + gap + " cpuSleepGapMs=" + sleepGap);
                }
                if ((enabled && state != Display.STATE_OFF) || dismissController.isPending()) {
                    maybeDismissVisibleGreeting("watchdog-" + displayStateName(state));
                } else {
                    // A screen-OFF interval breaks consecutive clear UI observations.
                    greetingRecovery.observeUi(elapsed, enabled, DismissController.Observation.UNKNOWN, -1);
                }
                if (dismissController.isPending()) {
                    nextDelay = VERIFY_POLL_MS;
                } else if (!enabled || state == Display.STATE_OFF) {
                    nextDelay = WATCHDOG_OFF_MS;
                } else if (state == Display.STATE_ON || state == Display.STATE_VR) {
                    nextDelay = WATCHDOG_ON_MS;
                } else if (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND ||
                        state == Display.STATE_ON_SUSPEND) {
                    nextDelay = WATCHDOG_DOZE_MS;
                } else {
                    nextDelay = interactive ? WATCHDOG_ON_MS : WATCHDOG_OFF_MS;
                }
            } catch (Throwable t) {
                writeDiag("A11Y v1.0 watchdog error " + throwableChain(t));
                nextDelay = WATCHDOG_OFF_MS;
            }
            handler.removeCallbacks(this);
            handler.postDelayed(this, nextDelay);
        }
    };

    private void kickWatchdog() {
        if (!serviceRunning) return;
        handler.removeCallbacks(greetingWatchdog);
        handler.post(greetingWatchdog);
    }

    private boolean autoCloseEnabled() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_MCU_AUTOCLOSE, false);
    }

    private int currentDisplayState() {
        Display display = displayManager == null ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        return display == null ? Display.STATE_UNKNOWN : display.getState();
    }

    private boolean isInteractiveNow() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private static String displayStateName(int state) {
        switch (state) {
            case Display.STATE_OFF: return "OFF";
            case Display.STATE_ON: return "ON";
            case Display.STATE_DOZE: return "DOZE";
            case Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            case Display.STATE_ON_SUSPEND: return "ON_SUSPEND";
            case Display.STATE_VR: return "VR";
            case Display.STATE_UNKNOWN:
            default: return "UNKNOWN(" + state + ")";
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connectedForUi = false;
        serviceRunning = true;
        writeEvent("\n=== SERVICE CONNECTED " + now() + " ===\n");
        writeDiag("SERVICE CONNECTED v" + BuildConfig.VERSION_NAME + " code=" + BuildConfig.VERSION_CODE +
                " sdk=" + Build.VERSION.SDK_INT + " device=" + Build.MANUFACTURER + " " + Build.MODEL);
        preferences = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayManager.registerDisplayListener(displayListener, handler);
        }

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }

        registerReceivers();
        connectedForUi = true;
        initMcuBridge(autoCloseEnabled());
        handler.removeCallbacks(greetingWatchdog);
        handler.post(greetingWatchdog);
        writeDiag("A11Y v1.0 watchdog started onMs=" + WATCHDOG_ON_MS + " dozeMs=" + WATCHDOG_DOZE_MS +
                " offMs=" + WATCHDOG_OFF_MS + " timersDoNotWakeCpu=true");
    }

    private void registerReceivers() {
        if (screenReceiver == null) {
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        writeDiag("SCREEN_ON");
                        handler.removeCallbacks(greetingWatchdog);
                        handler.post(greetingWatchdog);
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        writeDiag("SCREEN_OFF");
                        kickWatchdog();
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenReceiver, f, Context.RECEIVER_EXPORTED);
        }

    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (!TARGET_PACKAGE.equals(pkg)) return;

        String line = "\nEVENT " + now() +
                " type=" + AccessibilityEvent.eventTypeToString(event.getEventType()) +
                " pkg=" + pkg +
                " class=" + safe(event.getClassName()) +
                " windowId=" + event.getWindowId() +
                " text=" + event.getText() +
                " desc=" + safe(event.getContentDescription()) + "\n";
        writeEvent(line);

        // All sources feed the same bounded attempt; events cannot schedule duplicate taps.
        if (getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_MCU_AUTOCLOSE, false)) {
            maybeDismissVisibleGreeting("sysui-event");
        }

        if (eventLooksLikeGoodMorning(event)) {
            long elapsed = SystemClock.elapsedRealtime();
            if (elapsed - lastGreetingActionElapsed >= GREETING_DEBOUNCE_MS) {
                lastGreetingActionElapsed = elapsed;
                writeDiag("A11Y GOOD_MORNING DETECTED windowId=" + event.getWindowId() +
                        " type=" + AccessibilityEvent.eventTypeToString(event.getEventType()) +
                        " text=" + event.getText());

                boolean autoClose = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                        .getBoolean(MainActivity.KEY_MCU_AUTOCLOSE, false);
                writeDiag("A11Y GOOD_MORNING autoClose=" + autoClose);
                if (autoClose) {
                    kickWatchdog();
                }
            } else {
                writeDiag("A11Y GOOD_MORNING duplicate ignored windowId=" + event.getWindowId());
            }
        }
    }

    private boolean eventLooksLikeGoodMorning(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null) sb.append(cs).append(' ');
            }
        }
        if (event.getContentDescription() != null) sb.append(event.getContentDescription());
        String t = sb.toString().toLowerCase(Locale.ROOT);
        return t.contains("good morning") || t.contains("доброе утро");
    }


    private static final class GreetingSnapshot {
        DismissController.Observation observation = DismissController.Observation.UNKNOWN;
        AccessibilityNodeInfo close;
        final Rect bounds = new Rect();
        int windowId = -1;
        boolean canTap;
        String detail = "no-windows";
        void recycle() { if (close != null) { close.recycle(); close = null; } }
    }

    private void maybeDismissVisibleGreeting(String reason) {
        if (!serviceRunning) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed - lastVisibleGreetingDismissCheckElapsed < VISIBLE_GREETING_SCAN_THROTTLE_MS) return;
        lastVisibleGreetingDismissCheckElapsed = nowElapsed;
        GreetingSnapshot snapshot = new GreetingSnapshot();
        try {
            boolean enabled = autoCloseEnabled();
            if (enabled && currentDisplayState() != Display.STATE_OFF) snapshot = inspectGreeting();
            else snapshot.detail = enabled ? "display-off" : "disabled";
            GreetingRecoveryPolicy.Evidence evidence = greetingRecovery.observeUi(nowElapsed, enabled,
                    snapshot.observation, snapshot.windowId);
            if (evidence != GreetingRecoveryPolicy.Evidence.NONE) {
                writeDiag("RECOVERY v1.0 " + evidence + " lastMcuHiddenAgeMs=" +
                        greetingRecovery.hiddenAgeMs(nowElapsed) + " windowId=" + snapshot.windowId +
                        " detail=" + snapshot.detail);
            }
            DismissController.Action action = dismissController.observe(nowElapsed, enabled,
                    snapshot.observation, snapshot.windowId);
            String summary = snapshot.observation + " " + snapshot.detail;
            if (!summary.equals(lastScanSummary) || nowElapsed - lastScanLogElapsed >= HEALTH_LOG_MS) {
                lastScanSummary = summary;
                lastScanLogElapsed = nowElapsed;
                writeDiag("A11Y v1.0 scan=" + summary + " source=" + reason);
            }
            if (action == DismissController.Action.CLICK) {
                acquireDismissWakeLock();
                writeDiag("A11Y v1.0 attempt windowId=" + snapshot.windowId +
                        " bounds=" + snapshot.bounds.toShortString() + " source=" + reason);
                // Boolean return is logged but never treated as proof that the greeting vanished.
                boolean result = snapshot.close.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                writeDiag("A11Y v1.0 ACTION_CLICK result=" + result + " verification=pending");
                kickWatchdog();
            } else if (action == DismissController.Action.TAP) {
                // The snapshot and bounds were just refreshed, not saved from the previous click.
                if (snapshot.canTap && snapshot.close.refresh() && snapshot.close.isVisibleToUser() &&
                        snapshot.close.isEnabled()) {
                    Rect freshBounds = new Rect();
                    snapshot.close.getBoundsInScreen(freshBounds);
                    // If it moved after the window/occlusion scan, wait for a fresh full scan.
                    if (freshBounds.equals(snapshot.bounds) && !freshBounds.isEmpty()) {
                        boolean result = dispatchTap(freshBounds.centerX(), freshBounds.centerY());
                        writeDiag("A11Y v1.0 TAP dispatched=" + result + " windowId=" + snapshot.windowId +
                                " bounds=" + freshBounds.toShortString() + " verification=pending");
                    } else writeDiag("A11Y v1.0 TAP deferred: bounds changed");
                } else writeDiag("A11Y v1.0 TAP deferred: no fresh unobscured target");
            } else if (action == DismissController.Action.CONFIRMED) {
                writeDiag("A11Y v1.0 CLOSED_CONFIRMED: stable SysUI foreground without greeting");
                releaseDismissWakeLock();
            } else if (action == DismissController.Action.TIMED_OUT) {
                writeDiag("A11Y v1.0 CLOSE_UNVERIFIED: " + summary + " retryAfterMs=" +
                        dismissController.retryDelayMs());
                // Only a fresh visible target permits recovery. OFF/occluded/missing trees do not.
                if (greetingRecovery.allowQuery(nowElapsed, enabled, snapshot.observation,
                        snapshot.windowId, true)) {
                    boolean sent = queryMcuGreeting("ui-close-timeout windowId=" + snapshot.windowId);
                    writeDiag("RECOVERY v1.0 QUERY windowId=" + snapshot.windowId + " attempt=" +
                            greetingRecovery.queries() + " callAccepted=" + sent);
                }
                releaseDismissWakeLock();
            } else if (action == DismissController.Action.CANCELLED) {
                writeDiag("A11Y v1.0 attempt cancelled: auto-close disabled");
                releaseDismissWakeLock();
            }
        } catch (Throwable t) {
            writeDiag("A11Y v1.0 dismiss scan error " + throwableChain(t));
        } finally {
            snapshot.recycle();
            if (!dismissController.isPending()) releaseDismissWakeLock();
        }
    }

    private GreetingSnapshot inspectGreeting() {
        GreetingSnapshot result = new GreetingSnapshot();
        List<AccessibilityWindowInfo> windows = null;
        boolean greetingSeen = false;
        boolean uncertain = false;
        boolean activeSysUi = false;
        boolean otherForeground = false;
        int validRoots = 0;
        try {
            // Wear OS may keep a cached tree after a missing content-change event.
            if (Build.VERSION.SDK_INT >= 33) clearCache();
            windows = getWindows();
            if (windows != null) for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) { uncertain = true; continue; }
                try {
                    if (!root.refresh()) { uncertain = true; continue; }
                    validRoots++;
                    boolean sysUi = TARGET_PACKAGE.contentEquals(safe(root.getPackageName()));
                    if (window.isActive()) {
                        activeSysUi = sysUi;
                        otherForeground = !sysUi;
                    }
                    if (!sysUi) continue;
                    if (!isGreetingRoot(root)) continue;
                    greetingSeen = true;
                    AccessibilityNodeInfo close = findVisibleClose(root);
                    if (close == null) continue;
                    boolean kept = false;
                    try {
                        Rect bounds = new Rect();
                        close.getBoundsInScreen(bounds);
                        if (!window.isActive() || bounds.isEmpty() ||
                                obscuredAt(windows, window, bounds.centerX(), bounds.centerY())) continue;
                        result.close = close;
                        kept = true;
                        result.bounds.set(bounds);
                        result.windowId = window.getId();
                        result.canTap = true;
                        result.observation = DismissController.Observation.TARGET;
                        result.detail = "active-greeting windowId=" + result.windowId;
                        return result;
                    } finally {
                        if (!kept) close.recycle();
                    }
                } finally { root.recycle(); }
            }
            // A root may be available even when the windows list is temporarily empty.
            // Allow a node action, but no coordinate gesture without window/occlusion evidence.
            if (windows == null || windows.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) try {
                    if (root.refresh() && TARGET_PACKAGE.contentEquals(safe(root.getPackageName())) &&
                            isGreetingRoot(root)) {
                        greetingSeen = true;
                        result.close = findVisibleClose(root);
                        if (result.close != null) {
                            result.close.getBoundsInScreen(result.bounds);
                            result.windowId = root.getWindowId();
                            result.observation = DismissController.Observation.TARGET;
                            result.detail = "active-root-only windowId=" + result.windowId;
                            return result;
                        }
                    }
                } finally { root.recycle(); }
            }
            if (greetingSeen || otherForeground) result.observation = DismissController.Observation.BLOCKED;
            else if (activeSysUi && validRoots > 0 && !uncertain)
                result.observation = DismissController.Observation.CLEAR;
            result.detail = "windows=" + (windows == null ? -1 : windows.size()) +
                    " roots=" + validRoots + " greeting=" + greetingSeen +
                    " activeSysUi=" + activeSysUi + " otherForeground=" + otherForeground +
                    " incomplete=" + uncertain;
        } catch (Throwable t) {
            result.recycle();
            result.observation = DismissController.Observation.UNKNOWN;
            result.detail = "scan-error " + throwableChain(t);
        } finally {
            if (windows != null) for (AccessibilityWindowInfo window : windows)
                if (window != null) window.recycle();
        }
        return result;
    }

    private boolean obscuredAt(List<AccessibilityWindowInfo> windows, AccessibilityWindowInfo target,
                               int x, int y) {
        for (AccessibilityWindowInfo window : windows) {
            if (window == null || window.getId() == target.getId() || window.getLayer() <= target.getLayer()) continue;
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            if (bounds.contains(x, y)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findVisibleClose(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(TARGET_PACKAGE + ":id/bt_negative");
        if (nodes == null) return null;
        try {
            for (AccessibilityNodeInfo node : nodes) {
                if (node != null && node.refresh() && node.isVisibleToUser() && node.isEnabled()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) return AccessibilityNodeInfo.obtain(node);
                }
            }
        } finally {
            for (AccessibilityNodeInfo node : nodes) if (node != null) node.recycle();
        }
        return null;
    }

    private boolean isGreetingRoot(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(TARGET_PACKAGE + ":id/wake_up_root");
        if (nodes != null) {
            boolean found = !nodes.isEmpty();
            for (AccessibilityNodeInfo node : nodes) if (node != null) node.recycle();
            if (found) return true;
        }
        return nodeContainsGreeting(root, 0, new Counter());
    }

    private boolean nodeContainsGreeting(AccessibilityNodeInfo node, int depth, Counter counter) {
        if (node == null || depth > 24 || counter.count++ >= 900) return false;
        String text = (safe(node.getText()) + " " + safe(node.getContentDescription())).toLowerCase(Locale.ROOT);
        if (text.contains("good morning") || text.contains("доброе утро")) return true;
        for (int i = 0; i < Math.min(node.getChildCount(), 100); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (nodeContainsGreeting(child, depth + 1, counter)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    private void acquireDismissWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (dismissWakeLock == null && pm != null) {
                dismissWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":dismiss");
                dismissWakeLock.setReferenceCounted(false);
            }
            if (dismissWakeLock != null && !dismissWakeLock.isHeld())
                dismissWakeLock.acquire(DismissController.ATTEMPT_TIMEOUT_MS + 1000L);
        } catch (Throwable t) { writeDiag("A11Y v1.0 short wakelock unavailable " + throwableChain(t)); }
    }

    private void releaseDismissWakeLock() {
        try { if (dismissWakeLock != null && dismissWakeLock.isHeld()) dismissWakeLock.release(); }
        catch (Throwable ignored) { }
    }

    private boolean dispatchTap(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                    .build();
            return dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    writeDiag("A11Y fallback gesture completed");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    writeDiag("A11Y fallback gesture cancelled");
                }
            }, handler);
        } catch (Throwable t) {
            writeDiag("A11Y fallback gesture error " + throwableChain(t));
            return false;
        }
    }

    @Override
    public void onInterrupt() {
        writeEvent("=== SERVICE INTERRUPTED " + now() + " ===\n");
        writeDiag("SERVICE INTERRUPTED");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connectedForUi = false;
        serviceRunning = false;
        mcuCloseController.reset();
        greetingRecovery.reset();
        finishMcuVerification();
        writeDiag("SERVICE UNBOUND");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connectedForUi = false;
        serviceRunning = false;
        mcuCloseController.reset();
        greetingRecovery.reset();
        finishMcuVerification();
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        if (displayManager != null) displayManager.unregisterDisplayListener(displayListener);
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
        handler.removeCallbacksAndMessages(null);
        unregisterMcuBridge();
        releaseDismissWakeLock();
        super.onDestroy();
    }

    private synchronized void initMcuBridge(boolean sendQuery) {
        try {
            if (mcuManager == null || mcuListener == null || !mcuRegistered) {
                writeDiag("MCU INIT begin");
                mcuManagerClass = Class.forName("android.app.wear.McuManager");
                mcuListenerClass = Class.forName("android.app.wear.DataEventListener");

                // SysUiApplication uses GlobalInit(context, 0).
                Method globalInit = mcuManagerClass.getDeclaredMethod("GlobalInit", Context.class, int.class);
                globalInit.setAccessible(true);
                globalInit.invoke(null, getApplicationContext(), 0);

                Method getInstance = mcuManagerClass.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                mcuManager = getInstance.invoke(null);
                if (mcuManager == null) throw new IllegalStateException("McuManager.getInstance() returned null");

                ClassLoader cl = mcuListenerClass.getClassLoader();
                if (cl == null) cl = getClassLoader();
                mcuListener = Proxy.newProxyInstance(cl, new Class<?>[]{mcuListenerClass}, (proxy, method, args) -> {
                    String name = method.getName();
                    if ("onDataChanged".equals(name) && args != null && args.length > 0 && args[0] != null) {
                        onMcuDataEvent(args[0]);
                        return null;
                    }
                    if ("toString".equals(name)) return "MorningSkipMcuListener";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                    return null;
                });

                Method register = mcuManagerClass.getDeclaredMethod(
                        "registerMessageClassListener", mcuListenerClass, int.class);
                register.setAccessible(true);
                Object result = register.invoke(mcuManager, mcuListener, 10);
                mcuRegistered = result instanceof Boolean && (Boolean) result;
                writeDiag("MCU REGISTER classId=10 result=" + result);
            } else {
                writeDiag("MCU REGISTER already active");
            }

            if (sendQuery) queryMcuGreeting("startup-or-manual-check");
        } catch (Throwable t) {
            writeDiag("MCU ERROR " + throwableChain(t));
        }
    }

    private void onMcuDataEvent(Object event) {
        try {
            Class<?> c = event.getClass();
            Field classIdField = c.getField("classId");
            Field msgIdField = c.getField("msgId");
            Field msgTypeField = c.getField("msgType");
            Field valuesField = c.getField("values");
            int classId = classIdField.getInt(event);
            int msgId = msgIdField.getInt(event);
            int msgType = msgTypeField.getInt(event);
            byte[] values = (byte[]) valuesField.get(event);
            writeDiag("MCU EVENT classId=" + classId + " msgId=" + msgId +
                    " msgType=" + msgType + " len=" + (values == null ? -1 : values.length) +
                    " hex=" + toHex(values, 96));

            if (!serviceRunning) return;
            final long receivedElapsed = SystemClock.elapsedRealtime();
            final byte[] payload = values == null ? null : values.clone();
            if (classId == McuGreetingProtocol.CLASS_ID &&
                    (msgId == McuGreetingProtocol.STATE_EVENT || msgId == McuGreetingProtocol.QUERY_REPLY)) {
                handler.post(() -> handleMcuGreeting(classId, msgId, msgType, payload, receivedElapsed));
            }
            if (classId == 10 && !handler.hasCallbacks(mcuScanHint)) handler.post(mcuScanHint);
        } catch (Throwable t) {
            writeDiag("MCU EVENT ERROR " + throwableChain(t));
        }
    }

    private boolean queryMcuGreeting(String reason) {
        if (!serviceRunning || !mcuRegistered) return false;
        long elapsed = SystemClock.elapsedRealtime();
        if (lastMcuQueryElapsed >= 0 && elapsed - lastMcuQueryElapsed < 1000) return false;
        lastMcuQueryElapsed = elapsed;
        return sendMcuCommand(McuGreetingProtocol.QUERY, McuGreetingProtocol.queryPayload(), reason);
    }

    private boolean sendMcuCommand(int messageId, byte[] payload, String reason) {
        if (!serviceRunning || !mcuRegistered) return false;
        try {
            Method send = mcuManagerClass.getDeclaredMethod(
                    "sendMessageToMcu", int.class, int.class, byte[].class);
            send.setAccessible(true);
            Object result = send.invoke(mcuManager, McuGreetingProtocol.CLASS_ID, messageId, payload);
            boolean accepted = !(result instanceof Boolean) || (Boolean) result;
            writeDiag("MCU v1.0 SEND classId=10 msgId=" + messageId + " hex=" + toHex(payload, 32) +
                    " apiResult=" + result + " callAccepted=" + accepted +
                    " closureConfirmed=false reason=" + reason);
            return accepted;
        } catch (Throwable t) {
            writeDiag("MCU v1.0 SEND_ERROR msgId=" + messageId + " " + throwableChain(t));
            return false;
        }
    }

    private void handleMcuGreeting(int classId, int messageId, int messageType, byte[] payload, long received) {
        if (!serviceRunning) return;
        McuGreetingProtocol.State state = McuGreetingProtocol.decode(classId, messageId, messageType, payload);
        if (state == null) {
            writeDiag("MCU v1.0 STATE_IGNORED: invalid or unsupported payload msgId=" + messageId);
            return;
        }
        McuCloseController.Decision decision = mcuCloseController.observe(
                SystemClock.elapsedRealtime(), received, autoCloseEnabled(), state);
        writeDiag("MCU v1.0 STATE msgId=" + messageId + " great=" + state.great + " cnt=" + state.count +
                " decision=" + decision + " display=" + displayStateName(currentDisplayState()));
        if (decision != McuCloseController.Decision.STALE && decision != McuCloseController.Decision.DISABLED)
            greetingRecovery.onMcuState(SystemClock.elapsedRealtime(), state.great == 0);
        if (decision == McuCloseController.Decision.HIDDEN) {
            writeDiag("MCU v1.0 REPORTED_HIDDEN afterCloseRequest=" + mcuVerificationPending +
                    " visualConfirmation=false");
            finishMcuVerification();
            // Verify the Android window separately; an unavailable/OFF tree proves nothing.
            kickWatchdog();
        } else if (decision == McuCloseController.Decision.CLOSE) {
            // SysUI's X removes its own view AND sends this same MCU message.
            // Keep the Accessibility path for the Android view; this command targets the MCU copy.
            acquireMcuWakeLock();
            if (sendMcuCommand(McuGreetingProtocol.DISMISS, McuGreetingProtocol.dismissPayload(),
                    "visible-state cnt=" + state.count + " attempt=" + mcuCloseController.attempts())) {
                mcuVerificationPending = true;
                mcuCloseSentElapsed = SystemClock.elapsedRealtime();
                handler.removeCallbacks(verifyMcuQuery);
                handler.removeCallbacks(verifyMcuTimeout);
                handler.postDelayed(verifyMcuQuery, 1200);
                handler.postDelayed(verifyMcuTimeout, 3500);
            } else {
                finishMcuVerification();
            }
            kickWatchdog();
        } else if (decision == McuCloseController.Decision.DISABLED) {
            finishMcuVerification();
        }
    }

    private void acquireMcuWakeLock() {
        try {
            if (mcuWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                mcuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MorningSkip:McuClose");
                mcuWakeLock.setReferenceCounted(false);
            }
            mcuWakeLock.acquire(4000);
        } catch (Throwable t) { writeDiag("MCU v1.0 wake lock error " + throwableChain(t)); }
    }

    private void finishMcuVerification() {
        mcuVerificationPending = false;
        handler.removeCallbacks(verifyMcuQuery);
        handler.removeCallbacks(verifyMcuTimeout);
        if (mcuWakeLock != null && mcuWakeLock.isHeld()) mcuWakeLock.release();
    }

    private synchronized void unregisterMcuBridge() {
        if (!mcuRegistered || mcuManager == null || mcuListener == null ||
                mcuManagerClass == null || mcuListenerClass == null) return;
        try {
            Method unregister = mcuManagerClass.getDeclaredMethod(
                    "unregisterMessageClassListener", mcuListenerClass, int.class);
            unregister.setAccessible(true);
            unregister.invoke(mcuManager, mcuListener, 10);
            writeDiag("MCU UNREGISTER classId=10");
        } catch (Throwable t) {
            writeDiag("MCU UNREGISTER ERROR " + throwableChain(t));
        } finally {
            mcuRegistered = false;
        }
    }

    private String throwableChain(Throwable t) {
        StringBuilder b = new StringBuilder();
        Throwable cur = t;
        int n = 0;
        while (cur != null && n++ < 6) {
            if (b.length() > 0) b.append(" <- ");
            b.append(cur.getClass().getName());
            String m = cur.getMessage();
            if (m != null && !m.isEmpty()) b.append(": ").append(m.replace("\\n", " "));
            cur = cur.getCause();
        }
        return b.toString();
    }

    private String toHex(byte[] data, int maxBytes) {
        if (data == null) return "null";
        StringBuilder b = new StringBuilder();
        int n = Math.min(data.length, maxBytes);
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append(' ');
            b.append(String.format(Locale.US, "%02X", data[i] & 0xff));
        }
        if (data.length > n) b.append(" …");
        return b.toString();
    }

    private void writeEvent(String text) {
        writeRotating(new File(getFilesDir(), "events.log"), new File(getFilesDir(), "events.log.1"), MAX_EVENT_LOG_BYTES, text);
    }

    private void writeDiag(String text) {
        String line = now() + " " + text + "\n";
        File current = new File(getFilesDir(), "diagnostics.log");
        File old = new File(getFilesDir(), "diagnostics.log.1");
        writeRotating(current, old, MAX_DIAG_LOG_BYTES, line);
        Log.i(TAG, text);
    }

    private void writeRotating(File current, File old, long maxBytes, String text) {
        synchronized (DIAGNOSTICS_LOCK) {
        try {
            if (current.exists() && current.length() >= maxBytes) {
                if (old.exists()) old.delete();
                current.renameTo(old);
            }
            try (FileOutputStream fos = new FileOutputStream(current, true)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.e(TAG, "write failed", t);
        }
        }
    }

    private String now() { synchronized (timeFormat) { return timeFormat.format(new Date()); } }
    private String safe(CharSequence value) { return value == null ? "" : value.toString().replace("\n", "\\n"); }
    private static class Counter { int count = 0; }
}
