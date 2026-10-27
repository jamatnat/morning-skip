package io.github.jamatnat.morningskip;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    public static final String PREFS = "morning_skip_prefs";
    public static final String KEY_MCU_AUTOCLOSE = "mcu_autoclose";
    private static final String[] LOG_FILES = {
            "diagnostics.log", "diagnostics.log.1", "events.log", "events.log.1"
    };
    // Serialize exports and clears, including across activity recreation.
    private static final ExecutorService DIAGNOSTICS_WORKER = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button autoButton, zipButton, clearButton;
    private ScrollView mainScroll;
    private TextView serviceStatus, accessibilityStatus, diagnosticsStatus;
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainScroll = new ScrollView(this);
        mainScroll.setBackgroundColor(0xff000000);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        // Keep the compact single-column layout and spacing of the original app.
        int pad = dp(18);
        int edgePad = getResources().getConfiguration().isScreenRound() ? dp(32) : pad;
        box.setPadding(pad, edgePad, pad, edgePad);
        TextView title = addText(box, "MorningSkip v" + BuildConfig.VERSION_NAME, 17);
        title.setPadding(dp(12), 0, dp(12), 0);
        TextView setup = addText(box, "Enable MorningSkip in Accessibility,\nthen turn Auto-close ON.", 12);
        setup.setPadding(0, dp(6), 0, dp(6));
        serviceStatus = addText(box, "", 13);
        accessibilityStatus = addText(box, "", 13);
        autoButton = addButton(box, "");
        autoButton.setOnClickListener(v -> toggleAutoClose());
        Button accessibility = addButton(box, "Open Accessibility");
        accessibility.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, "Open Accessibility in watch Settings", Toast.LENGTH_LONG).show();
            }
        });
        TextView diagnostics = addText(box, "Diagnostics", 13);
        diagnostics.setPadding(0, dp(8), 0, dp(2));
        addText(box, "Save or clear troubleshooting logs.", 11);
        zipButton = addButton(box, "Build diagnostics ZIP");
        zipButton.setOnClickListener(v -> runDiagnostics(true));
        clearButton = addButton(box, "Clear diagnostics");
        clearButton.setOnClickListener(v -> runDiagnostics(false));
        diagnosticsStatus = addText(box, "", 11);
        diagnosticsStatus.setVisibility(View.GONE);
        TextView hint = addText(box, "Closes the OnePlus greeting.\nYou can leave this screen.", 11);
        hint.setPadding(dp(12), dp(6), dp(12), 0);
        mainScroll.addView(box);
        setContentView(mainScroll);
        mainScroll.setFocusable(true);
        mainScroll.setFocusableInTouchMode(true);
        mainScroll.post(mainScroll::requestFocus);
        updateStatus();
    }

    private TextView addText(LinearLayout box, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(0xffeeeeee);
        box.addView(view);
        return view;
    }

    private Button addButton(LinearLayout box, String text) {
        Button button = new Button(this);
        button.setText(text);
        // Explicit contrast avoids relying on vendor theme button/text colors.
        button.setTextColor(new ColorStateList(
                new int[][] { new int[] {-android.R.attr.state_enabled}, new int[] {} },
                new int[] {0xffaaaaaa, 0xffffffff}));
        button.setBackgroundTintList(new ColorStateList(
                new int[][] { new int[] {-android.R.attr.state_enabled},
                        new int[] {android.R.attr.state_pressed},
                        new int[] {android.R.attr.state_focused}, new int[] {} },
                new int[] {0xff202020, 0xff505050, 0xff505050, 0xff303030}));
        box.addView(button);
        return button;
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshStatus);
        handler.post(refreshStatus);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshStatus);
        super.onPause();
    }

    private void updateStatus() {
        boolean accessibilityEnabled = ServiceStatus.enabled(this);
        boolean connected = accessibilityEnabled && SysUiProbeService.connectedForUi;
        boolean autoClose = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_MCU_AUTOCLOSE, false);
        boolean active = connected && autoClose;
        serviceStatus.setText(active ? "Service: ON" : "Service: OFF");
        serviceStatus.setTextColor(active ? 0xff80e080 : 0xffff7070);
        accessibilityStatus.setText(connected ? "Accessibility: ON" :
                accessibilityEnabled ? "Accessibility: connecting" : "Accessibility: OFF");
        accessibilityStatus.setTextColor(connected ? 0xff80e080 :
                accessibilityEnabled ? 0xffffb060 : 0xffff7070);
        autoButton.setText("Good Morning Auto-close: " + (autoClose ? "ON" : "OFF"));
    }

    private void toggleAutoClose() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enable = !prefs.getBoolean(KEY_MCU_AUTOCLOSE, false);
        if (enable && !SysUiProbeService.connectedForUi) {
            Toast.makeText(this, "Enable MorningSkip in Accessibility first and wait for connection",
                    Toast.LENGTH_LONG).show();
        } else {
            prefs.edit().putBoolean(KEY_MCU_AUTOCLOSE, enable).apply();
        }
        updateStatus();
    }

    private void runDiagnostics(boolean export) {
        zipButton.setEnabled(false);
        clearButton.setEnabled(false);
        diagnosticsStatus.setVisibility(View.VISIBLE);
        diagnosticsStatus.setText(export ? "Building ZIP…" : "Clearing…");
        DIAGNOSTICS_WORKER.execute(() -> {
            String result;
            try {
                if (export) buildZip(); else clearDiagnostics();
                result = export ? "ZIP ready. Export using ADB." : "Diagnostics cleared.";
            } catch (IOException e) {
                result = (export ? "ZIP failed: " : "Clear failed: ") + e.getClass().getSimpleName();
            }
            final String message = result;
            handler.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                diagnosticsStatus.setText(message);
                zipButton.setEnabled(true);
                clearButton.setEnabled(true);
            });
        });
    }

    private void buildZip() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String info = "versionName=" + BuildConfig.VERSION_NAME + "\nversionCode=" + BuildConfig.VERSION_CODE +
                "\npackage=" + getPackageName() + "\nsdk=" + android.os.Build.VERSION.SDK_INT +
                "\nmanufacturer=" + android.os.Build.MANUFACTURER + "\nmodel=" + android.os.Build.MODEL +
                "\nautoClose=" + getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_MCU_AUTOCLOSE, false) +
                "\naccessibilityEnabled=" + ServiceStatus.enabled(this) +
                "\nserviceConnected=" + SysUiProbeService.connectedForUi +
                "\nmcuProtocol=sysui-class10-great1-dismiss33\nexportTime=" + new java.util.Date() + "\n";
        entries.put("build-info.txt", info.getBytes(StandardCharsets.UTF_8));
        // Snapshot rotating logs under the writer lock; compress outside it.
        synchronized (SysUiProbeService.DIAGNOSTICS_LOCK) {
            for (String name : LOG_FILES) {
                File file = new File(getFilesDir(), name);
                if (file.isFile()) entries.put(name, Files.readAllBytes(file.toPath()));
            }
        }
        File temporary = new File(getFilesDir(), "diagnostics.zip.tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temporary))) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            Files.move(temporary.toPath(), new File(getFilesDir(), "diagnostics.zip").toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private void clearDiagnostics() throws IOException {
        synchronized (SysUiProbeService.DIAGNOSTICS_LOCK) {
            for (String name : LOG_FILES) Files.deleteIfExists(new File(getFilesDir(), name).toPath());
            Files.deleteIfExists(new File(getFilesDir(), "diagnostics.zip").toPath());
        }
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mainScroll != null && event.getAction() == MotionEvent.ACTION_SCROLL &&
                (event.getSource() & InputDevice.SOURCE_ROTARY_ENCODER) == InputDevice.SOURCE_ROTARY_ENCODER) {
            float axis = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (axis != 0f) {
                mainScroll.scrollBy(0, Math.round(-axis * dp(64)));
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
