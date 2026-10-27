package io.github.jamatnat.morningskip;

import java.util.Arrays;
import static io.github.jamatnat.morningskip.McuCloseController.Decision.*;

public final class McuGreetingTest {
    private static int checks;
    private static void eq(Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static byte[] hex(int... bytes) {
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) result[i] = (byte) bytes[i];
        return result;
    }
    private static McuGreetingProtocol.State state(int... bytes) {
        return McuGreetingProtocol.decode(10, 36, 0, hex(bytes));
    }
    public static void main(String[] args) {
        // Exact watch packets from 2026-09-26: show at 08:29:59, hide at 08:48:40.
        McuGreetingProtocol.State show = state(0x08, 0x01, 0x10, 0x33);
        McuGreetingProtocol.State hide = state(0x10, 0x34);
        eq(1, show.great); eq(51, show.count);
        eq(0, hide.great); eq(52, hide.count);
        eq(true, Arrays.equals(hex(8, 1), McuGreetingProtocol.dismissPayload()));
        eq(true, Arrays.equals(hex(8, 1), McuGreetingProtocol.queryPayload()));
        eq(1, McuGreetingProtocol.decode(10, 32, 0, hex(8, 1)).great);
        eq(0, state(8, 0).great);
        eq(1, state(0x18, 0x7f, 8, 1).great); // Unknown varint field.
        eq(1, state(0x22, 2, 0xff, 0xff, 8, 1).great); // Unknown bytes field.
        eq(300, state(8, 1, 16, 0xac, 2).count);
        eq(0, state(8, 1, 8, 0).great); // Protobuf last value wins.
        eq(null, McuGreetingProtocol.decode(11, 36, 0, hex(8, 1)));
        eq(null, McuGreetingProtocol.decode(10, 34, 0, hex(8, 1)));
        eq(null, McuGreetingProtocol.decode(10, 36, 1, hex(8, 1)));
        eq(null, McuGreetingProtocol.decode(10, 36, 0, null));
        eq(null, state());
        eq(null, state(8));
        eq(null, state(8, 2));
        eq(null, state(0, 1));
        eq(null, state(0x18, 1)); // No known fields is not a hidden state.
        eq(null, state(0x0a, 1, 1)); // Wrong wire type for great.
        eq(null, state(0x22, 0x7f, 1));
        eq(null, state(8, 0x80));
        eq(null, state(8, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 2));
        eq(null, McuGreetingProtocol.decode(10, 36, 0, new byte[257]));

        McuCloseController c = new McuCloseController();
        eq(CLOSE, c.observe(10000, 10000, true, show));
        eq(LIMITED, c.observe(10800, 10800, true, show));
        eq(CLOSE, c.observe(11600, 11600, true, show));
        eq(LIMITED, c.observe(12300, 12300, true, show));
        eq(CLOSE, c.observe(13300, 13300, true, show));
        eq(LIMITED, c.observe(15000, 15000, true, show));
        eq(3, c.attempts());
        eq(HIDDEN, c.observe(1120000, 1120000, true, hide));
        eq(CLOSE, c.observe(1122000, 1122000, true, show));
        eq(DISABLED, c.observe(1123000, 1123000, false, show));
        eq(0, c.attempts());
        eq(STALE, c.observe(1130000, 1123000, true, show));
        eq(STALE, c.observe(100, 200, true, hide));
        eq(CLOSE, c.observe(1131000, 1131000, true, show));
        eq(LIMITED, c.observe(1147000, 1147000, true, show)); // Episode expired.
        eq(CLOSE, c.observe(1210001, 1210001, true, show)); // Fresh event after idle.
        eq(LIMITED, c.observe(1210500, 1210500, true, state(8, 1, 16, 99)));
        eq(CLOSE, c.observe(1212000, 1212000, true, state(8, 1, 16, 99)));
        System.out.println("MCU protocol and policy: " + checks + " checks passed");
    }
}
