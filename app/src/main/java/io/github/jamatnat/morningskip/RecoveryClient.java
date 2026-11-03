package io.github.jamatnat.morningskip;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

final class RecoveryClient {
    interface Gate { boolean stillAllowed(); }
    interface Callback { void complete(String response); }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    static void request(Handler main, Gate gate, Callback callback) {
        WORKER.execute(() -> {
            String response = "UNAVAILABLE";
            try (LocalSocket socket = connect()) {
                send(socket, RecoveryProtocol.PREPARE);
                response = read(socket);
                if ("READY 1".equals(response)) {
                    FutureTask<Boolean> check = new FutureTask<>(gate::stillAllowed);
                    main.post(check);
                    boolean allowed = false;
                    try { allowed = check.get(2, TimeUnit.SECONDS); }
                    catch (Exception ignored) { check.cancel(false); }
                    socket.setSoTimeout(20000);
                    send(socket, allowed ? RecoveryProtocol.COMMIT : "CANCEL 1");
                    response = read(socket);
                }
            } catch (IOException e) { response = "UNAVAILABLE"; }
            final String result = response;
            main.post(() -> callback.complete(result));
        });
    }

    static void status(Handler main, Callback callback) {
        WORKER.execute(() -> {
            String response;
            try (LocalSocket socket = connect()) {
                send(socket, RecoveryProtocol.STATUS); response = read(socket);
            } catch (IOException e) { response = "UNAVAILABLE"; }
            final String result = response;
            main.post(() -> callback.complete(result));
        });
    }

    private static LocalSocket connect() throws IOException {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(RecoveryProtocol.SOCKET));
            socket.setSoTimeout(5000);
            // An app must not mistake another app occupying the socket for the shell helper.
            if (socket.getPeerCredentials().getUid() != 2000) throw new IOException("Not a shell helper");
            return socket;
        } catch (IOException e) { socket.close(); throw e; }
    }

    private static void send(LocalSocket socket, String value) throws IOException {
        socket.getOutputStream().write((value + "\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String read(LocalSocket socket) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 64; i++) {
            int b = socket.getInputStream().read();
            if (b == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            if (b < 0) throw new EOFException();
            bytes.write(b);
        }
        throw new IOException("Response too long");
    }
}
