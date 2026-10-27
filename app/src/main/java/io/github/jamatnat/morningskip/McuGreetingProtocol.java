package io.github.jamatnat.morningskip;

/** Wire format verified against WakeUpGreatSyncProto in the user's SysUI APK. */
final class McuGreetingProtocol {
    static final int CLASS_ID = 10;
    static final int QUERY = 31;
    static final int QUERY_REPLY = 32;
    static final int DISMISS = 33;
    static final int STATE_EVENT = 36;

    static byte[] queryPayload() { return new byte[] {0x08, 0x01}; }
    static byte[] dismissPayload() { return new byte[] {0x08, 0x01}; }

    static final class State {
        final int great;
        final int count;
        State(int great, int count) { this.great = great; this.count = count; }
    }

    // Small bounded protobuf reader: field 1 = int32 great, field 2 = int32 cnt.
    // An omitted great is zero: the observed hide message is just 10 34.
    static State decode(int classId, int messageId, int messageType, byte[] bytes) {
        if (classId != CLASS_ID || messageType != 0 ||
                (messageId != QUERY_REPLY && messageId != STATE_EVENT) ||
                bytes == null || bytes.length == 0 || bytes.length > 256) return null;
        try {
            Reader r = new Reader(bytes);
            int great = 0, count = 0;
            boolean known = false;
            while (r.pos < bytes.length) {
                long tag = r.varint();
                if (tag <= 0 || tag > 0xffffffffL) return null;
                int field = (int) (tag >>> 3), wire = (int) (tag & 7);
                if (field == 0) return null;
                if (field == 1 || field == 2) {
                    if (wire != 0) return null;
                    long value = r.varint();
                    if (field == 1) {
                        if (value != 0 && value != 1) return null;
                        great = (int) value;
                    } else count = (int) value;
                    known = true;
                } else {
                    switch (wire) {
                        case 0: r.varint(); break;
                        case 1: r.skip(8); break;
                        case 2: r.skip(r.varint()); break;
                        case 5: r.skip(4); break;
                        default: return null;
                    }
                }
            }
            return known ? new State(great, count) : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private static final class Reader {
        final byte[] data;
        int pos;
        Reader(byte[] data) { this.data = data; }
        long varint() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                if (pos >= data.length) throw new IllegalArgumentException();
                int b = data[pos++] & 255;
                if (i == 9 && (b & 254) != 0) throw new IllegalArgumentException();
                value |= (long) (b & 127) << (7 * i);
                if ((b & 128) == 0) return value;
            }
            throw new IllegalArgumentException();
        }
        void skip(long length) {
            if (length < 0 || length > data.length - pos) throw new IllegalArgumentException();
            pos += (int) length;
        }
    }
}
