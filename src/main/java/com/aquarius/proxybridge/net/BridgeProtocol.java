package com.aquarius.proxybridge.net;

import com.aquarius.proxybridge.feature.BridgeWaypoint;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Client-side mirror of the proxy's {@code com.aquarius.feature.bridge.BridgeProtocol}. Pure Java (no Minecraft
 * types) so the wire format is provably identical to the proxy regardless of mappings — see {@code PROTOCOL.md}.
 *
 * <p>Envelope: {@code VarInt protocolVersion} · {@code String topic} · {@code byte[] body}. Primitives match
 * Minecraft's {@code FriendlyByteBuf}: VarInt = 7-bit continuation; String = VarInt(byteLen)+UTF-8; int = 4-byte
 * big-endian; boolean = 1 byte.
 */
public final class BridgeProtocol {
    private BridgeProtocol() {}

    public static final String CHANNEL_NAMESPACE = "proxybridge";
    public static final String CHANNEL_PATH = "main";

    public static final int PROTOCOL_VERSION = 1;

    public static final String TOPIC_HELLO = "hello";
    public static final String TOPIC_WP_SYNC = "wp/sync";
    public static final String TOPIC_WP_UPSERT = "wp/upsert";
    public static final String TOPIC_WP_REMOVE = "wp/remove";
    public static final String TOPIC_WP_CLEAR = "wp/clear";
    public static final String TOPIC_CMD_INVOKE = "cmd/invoke";
    public static final String TOPIC_TOAST = "toast";

    // ---- encoders (client -> proxy) ------------------------------------------------------------

    public static byte[] encodeHello(String side, String version, List<String> features) {
        Writer w = startMessage(TOPIC_HELLO);
        w.writeString(side);
        w.writeString(version);
        w.writeVarInt(features.size());
        for (String f : features) w.writeString(f);
        return w.toByteArray();
    }

    public static byte[] encodeCmdInvoke(String name, String args) {
        Writer w = startMessage(TOPIC_CMD_INVOKE);
        w.writeString(name);
        w.writeString(args == null ? "" : args);
        return w.toByteArray();
    }

    private static Writer startMessage(String topic) {
        Writer w = new Writer();
        w.writeVarInt(PROTOCOL_VERSION);
        w.writeString(topic);
        return w;
    }

    public static BridgeWaypoint readWaypoint(Reader r) {
        String id = r.readString();
        String name = r.readString();
        String dimension = r.readString();
        int x = r.readInt();
        int y = r.readInt();
        int z = r.readInt();
        int color = r.readInt();
        int ttl = r.readInt();
        return new BridgeWaypoint(id, name, dimension, x, y, z, color, ttl);
    }

    // ---- buffer primitives ---------------------------------------------------------------------

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

        public void writeVarInt(int value) {
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value & 0x7F);
        }

        public void writeString(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(b.length);
            out.write(b, 0, b.length);
        }

        public void writeInt(int v) {
            out.write((v >>> 24) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        public void writeBoolean(boolean b) {
            out.write(b ? 1 : 0);
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    public static final class Reader {
        private final byte[] data;
        private int pos;

        public Reader(byte[] data) {
            this.data = data;
        }

        public int readVarInt() {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (pos >= data.length) throw new IndexOutOfBoundsException("VarInt past end of buffer");
                b = data[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new IllegalArgumentException("VarInt too big");
            } while ((b & 0x80) != 0);
            return value;
        }

        public String readString() {
            int len = readVarInt();
            if (len < 0 || pos + len > data.length) throw new IndexOutOfBoundsException("String past end of buffer");
            String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        public int readInt() {
            if (pos + 4 > data.length) throw new IndexOutOfBoundsException("Int past end of buffer");
            int v = ((data[pos] & 0xFF) << 24)
                | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8)
                | (data[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        public boolean readBoolean() {
            if (pos >= data.length) throw new IndexOutOfBoundsException("Boolean past end of buffer");
            return data[pos++] != 0;
        }

        public boolean hasRemaining() {
            return pos < data.length;
        }
    }
}
