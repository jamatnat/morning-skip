package io.github.jamatnat.morningskip;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional app_process entry point, explicitly started by the owner through ADB. */
public final class ShellRecovery {
    private static final Pattern APP_UID = Pattern.compile(
            "(?m)^package:io\\.github\\.jamatnat\\.morningskip uid:(\\d+)\\s*$");
    private static long lastRestart = -1, dayStarted;
    private static int restarts;

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("Start through adb shell");
        if (args.length == 1 && ("--status".equals(args[0]) || "--stop".equals(args[0]))) {
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(RecoveryProtocol.SOCKET));
                socket.setSoTimeout(3000);
                reply(socket, "--stop".equals(args[0]) ? RecoveryProtocol.STOP : RecoveryProtocol.STATUS);
                System.out.println(read(socket));
            } catch (IOException e) { System.out.println("UNAVAILABLE"); }
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Unknown option");
        dayStarted = SystemClock.elapsedRealtime();
        try (LocalServerSocket server = new LocalServerSocket(RecoveryProtocol.SOCKET)) {
            System.out.println("MorningSkip recovery 1 ready; shell uid=2000; fixed SysUI restart only");
            boolean running = true;
            while (running) {
                try (LocalSocket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    int uid = socket.getPeerCredentials().getUid();
                    // Authenticate before reading requests or examining system windows.
                    int appUid = uid == 2000 ? -1 : installedUid();
                    if (uid != 2000 && (appUid < 10000 || uid != appUid)) {
                        reply(socket, "DENIED"); continue;
                    }
                    String op = read(socket);
                    if (!RecoveryProtocol.authorized(uid, appUid, op)) { reply(socket, "DENIED"); continue; }
                    if (RecoveryProtocol.STATUS.equals(op)) { reply(socket, "AVAILABLE 1"); continue; }
                    if (RecoveryProtocol.STOP.equals(op)) { reply(socket, "STOPPED"); running = false; continue; }
                    if (!budgetAvailable()) { reply(socket, "LIMITED"); continue; }
                    String first = windows();
                    if (!RecoveryProtocol.greetingFocused(first)) { reply(socket, "NOT_FOCUSED"); continue; }
                    reply(socket, "READY 1");
                    // The app must refresh its UI evidence on its main thread before committing.
                    if (!RecoveryProtocol.COMMIT.equals(read(socket))) { reply(socket, "CANCELLED"); continue; }
                    String current = windows();
                    if (!RecoveryProtocol.greetingFocused(current)) { reply(socket, "NOT_FOCUSED"); continue; }
                    if (!budgetAvailable()) { reply(socket, "LIMITED"); continue; }
                    saveEvidence(current);
                    // Reserve even on command failure; never get into a force-stop loop.
                    lastRestart = SystemClock.elapsedRealtime(); restarts++;
                    Result result = run(12, "/system/bin/am", "start", "-S", "-W", "--user", "0",
                            "-n", RecoveryProtocol.COMPONENT);
                    System.out.println("Restart attempt " + restarts + " exit=" + result.code + " " + result.text);
                    // A successful command is not proof that the greeting visually disappeared.
                    reply(socket, result.code == 0 && result.text.contains("Status: ok") ?
                            "RESTART_SENT" : "RESTART_FAILED");
                } catch (Exception e) {
                    System.out.println("Request failed: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    private static boolean budgetAvailable() {
        long now = SystemClock.elapsedRealtime();
        if (now - dayStarted >= 24 * 60 * 60000L) { dayStarted = now; restarts = 0; }
        return restarts < 3 && (lastRestart < 0 || now - lastRestart >= StuckGreetingPolicy.COOLDOWN_MS);
    }

    private static int installedUid() throws Exception {
        Result result = run(3, "/system/bin/cmd", "package", "list", "packages", "-U", "--user", "0",
                RecoveryProtocol.PACKAGE);
        Matcher match = APP_UID.matcher(result.text);
        return result.code == 0 && match.find() ? Integer.parseInt(match.group(1)) : -1;
    }

    private static String windows() throws Exception {
        Result result = run(3, "/system/bin/dumpsys", "window");
        return result.code == 0 ? result.text : "";
    }

    private static void saveEvidence(String windowDump) throws IOException {
        File file = new File("/data/local/tmp/morningskip-recovery-last.txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(("Before SysUI restart; elapsed=" + SystemClock.elapsedRealtime() + "\n" +
                    windowDump).getBytes(StandardCharsets.UTF_8));
        }
        file.setReadable(false, false); file.setWritable(false, false);
        file.setReadable(true, true); file.setWritable(true, true);
    }

    private static String read(LocalSocket socket) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream input = socket.getInputStream();
        for (int i = 0; i < 64; i++) {
            int b = input.read();
            if (b == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            if (b < 0) throw new EOFException();
            bytes.write(b);
        }
        throw new IOException("Request too long");
    }

    private static void reply(LocalSocket socket, String value) throws IOException {
        socket.getOutputStream().write((value + "\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static final class Result {
        final int code; final String text;
        Result(int code, String text) { this.code = code; this.text = text; }
    }

    private static Result run(int seconds, String... command) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) >= 0) {
                    synchronized (output) {
                        int keep = Math.min(n, Math.max(0, 256 * 1024 - output.size()));
                        output.write(buffer, 0, keep);
                    }
                }
            } catch (IOException ignored) { }
        }, "recovery-command-output");
        reader.setDaemon(true); reader.start();
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly(); reader.join(500);
            return new Result(-1, "command timed out");
        }
        reader.join(1000);
        synchronized (output) {
            return new Result(process.exitValue(), new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}
