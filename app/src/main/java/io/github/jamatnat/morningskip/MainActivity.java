package io.github.jamatnat.morningskip;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private TextView serviceStatus, diagnosticsStatus;
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainScroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(36), dp(24), dp(44));
        addText(box, "MorningSkip", 22);
        addText(box, "Version " + BuildConfig.VERSION_NAME, 12);
        addText(box, "1. Open Accessibility and enable MorningSkip.\n\n" +
                "2. Return here and turn Auto-close ON.", 13);
        serviceStatus = addText(box, "", 12);
        Button accessibility = addButton(box, "Open Accessibility");
        accessibility.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, "Open Accessibility in watch Settings", Toast.LENGTH_LONG).show();
            }
        });
        autoButton = addButton(box, "");
        autoButton.setOnClickListener(v -> toggleAutoClose());

        View divider = new View(this);
        divider.setBackgroundColor(0xff555555);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
        line.setMargins(dp(12), dp(20), dp(12), dp(12));
        box.addView(divider, line);
        addText(box, "Diagnostics", 17);
        addText(box, "Build a ZIP for troubleshooting, then copy it to your computer using ADB. " +
                "Clear removes saved logs and the ZIP; settings stay unchanged.", 12);
        zipButton = addButton(box, "Build diagnostics ZIP");
        zipButton.setOnClickListener(v -> runDiagnostics(true));
        clearButton = addButton(box, "Clear diagnostics");
        clearButton.setOnClickListener(v -> runDiagnostics(false));
        diagnosticsStatus = addText(box, "Export instructions are on GitHub: jamatnat/morning-skip", 11);
        addText(box, "How it works", 17);
        addText(box, "With Accessibility connected and Auto-close ON, MorningSkip watches for the " +
                "OnePlus greeting and asks the watch controller to dismiss it. If the greeting remains " +
                "visible, it tries its Close button with limited retries. You can leave this app screen. " +
                "Turn Auto-close OFF to stop automatic closing.", 12);
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
        view.setPadding(0, dp(6), 0, dp(6));
        box.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Button addButton(LinearLayout box, String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinHeight(dp(48));
        box.addView(button, new LinearLayout.LayoutParams(-1, -2));
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
        boolean connected = SysUiProbeService.connectedForUi;
        serviceStatus.setText(connected ? "Service: connected" : ServiceStatus.enabled(this) ?
                "Service: enabled, waiting for connection" : "Service: OFF — enable in Accessibility");
        serviceStatus.setTextColor(connected ? 0xff80e080 : 0xffffb060);
        boolean enabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_MCU_AUTOCLOSE, false);
        autoButton.setText("Good Morning Auto-close: " + (enabled ? "ON" : "OFF"));
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
        diagnosticsStatus.setText(export ? "Building ZIP…" : "Clearing…");
        DIAGNOSTICS_WORKER.execute(() -> {
            String result;
            try {
                if (export) buildZip(); else clearDiagnostics();
                result = export ? "diagnostics.zip ready. Copy it using ADB; see GitHub instructions." :
                        "Diagnostics cleared. The connected service can start new logs.";
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
