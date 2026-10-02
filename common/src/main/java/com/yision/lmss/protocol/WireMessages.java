package com.yision.lmss.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class WireMessages {
    public static final int HELLO = 1;
    public static final int OPEN = 2;
    public static final int CLOSE = 3;
    public static final int WATCH = 4;
    public static final int CHUNK = 5;
    public static final int MOUSE = 6;
    public static final int RESYNC = 7;
    public static final int CACHE_READY = 9;
    public static final int ACK = 10;
    public static final int DISPLAYED = 11;
    public static final int INTEREST = 12, OFFER = 13, GRANT = 14, STATUS = 15, STATE = 16;
    public static final int GUI = 0, CHAT = 1, FULLSCREEN = 2;
    public static final int CIRCULAR = 4;
    public static final int NEED_FULL = 1, NEED_CIRCLE = 2, KEY_FULL = 4, KEY_CIRCLE = 8;
    public static final int PAUSE_UPDATES = 16;
    public static final int MAX_PEERS = 16;
    public static final int ALL_CODECS = FrameCodec.SUPPORTED_CODECS;
    public static final int MAX_BYTES = 1 + GuiPayloads.MAX_HEADER_BYTES + GuiPayloads.MAX_CHUNK_BYTES;

    private WireMessages() {}

    public record Message(int type, UUID session, int width, int height, int viewers,
            byte[] chunk, UUID target, long sequence, int u, int v, int flags,
            int maxShareDistance, boolean receiveGui, boolean circularMask, double guiScale,
            int guiUpdateIntervalMs) {
        public Message(int type, UUID session, int width, int height, int viewers,
                byte[] chunk, UUID target, long sequence, int u, int v, int flags) {
            this(type, session, width, height, viewers, chunk, target, sequence, u, v, flags, 16, true, true, 1, 500);
        }

        public Message(int type, UUID session, int width, int height, int viewers, byte[] chunk) {
            this(type, session, width, height, viewers, chunk, null, 0, 0, 0, 0);
        }
    }

    public static byte[] mouse(UUID session, long sequence, int u, int v, int flags) {
        if (sequence <= 0 || (u & ~65535) != 0 || (v & ~65535) != 0 || (flags & ~15) != 0) {
            throw new IllegalArgumentException("Invalid mouse state");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(30);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(MOUSE);
            writeUuid(out, session);
            out.writeLong(sequence);
            out.writeShort(u);
            out.writeShort(v);
            out.writeByte(flags);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] resync(UUID target, UUID session, long revision) {
        return resync(target, session, revision, FrameCodec.SUPPORTED_CODECS);
    }

    public static byte[] resync(UUID target, UUID session, long revision, int codecs) {
        if (revision < 0 || (codecs & FrameCodec.SUPPORTED_CODECS) != FrameCodec.SUPPORTED_CODECS ||
                (codecs & ~ALL_CODECS) != 0) {
            throw new IllegalArgumentException("Invalid resync");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(42);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(RESYNC);
            writeUuid(out, target);
            writeUuid(out, session);
            out.writeLong(revision);
            out.writeByte(codecs);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static byte[] hello(int codecs, boolean receiveGui) {
        return hello(codecs, 16, receiveGui, true, 1, 500);
    }

    public static byte[] hello(int codecs, int maxShareDistance, boolean receiveGui, boolean circularMask, double guiScale,
            int guiUpdateIntervalMs) {
        if (maxShareDistance < 1 || maxShareDistance > 128) {
            throw new IllegalArgumentException("Invalid sharing distance");
        }
        if (!Double.isFinite(guiScale) || guiScale < 0.25 || guiScale > 4) {
            throw new IllegalArgumentException("Invalid panel scale");
        }
        if (guiUpdateIntervalMs < 100 || guiUpdateIntervalMs > 10_000) {
            throw new IllegalArgumentException("Invalid image update interval");
        }
        if ((codecs & FrameCodec.SUPPORTED_CODECS) != FrameCodec.SUPPORTED_CODECS ||
                (codecs & ~ALL_CODECS) != 0) {
            throw new IllegalArgumentException("Invalid codecs");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(25);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(HELLO);
            out.writeByte(codecs);
            out.writeShort(256);
            out.writeShort(256);
            out.writeShort(GuiPayloads.MAX_CHUNK_BYTES);
            out.writeInt(GuiPayloads.MAX_IMAGE_BYTES);
            out.writeShort(maxShareDistance);
            out.writeByte((receiveGui ? 1 : 0) | (circularMask ? 2 : 0));
            out.writeDouble(guiScale);
            out.writeShort(guiUpdateIntervalMs);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static byte[] close(UUID session) {
        return withSession(CLOSE, session, 0, 0);
    }

    public static UUID variantSession(UUID root, boolean circular) {
        return UUID.nameUUIDFromBytes(ByteBuffer.allocate(17)
                .putLong(root.getMostSignificantBits()).putLong(root.getLeastSignificantBits())
                .put((byte) (circular ? 1 : 0)).array());
    }

    public static byte[] watch(UUID session, int viewers, int demand, int fullCodecs, int circleCodecs) {
        if (viewers < 0 || viewers > MAX_PEERS) {
            throw new IllegalArgumentException("Invalid viewer count");
        }
        if ((demand & ~31) != 0 || !validCodecs(fullCodecs) || !validCodecs(circleCodecs)) {
            throw new IllegalArgumentException("Invalid publishing demand");
        }
        byte[] result = Arrays.copyOf(withSession(CLOSE, session, 0, 0), 21);
        result[0] = WATCH;
        result[17] = (byte) viewers;
        result[18] = (byte) demand;
        result[19] = (byte) fullCodecs;
        result[20] = (byte) circleCodecs;
        return result;
    }

    public static byte[] cacheReady(UUID owner, UUID session, long revision) {
        byte[] result = resync(owner, session, revision);
        result[0] = CACHE_READY;
        return result;
    }

    public static byte[] open(UUID session, int width, int height, long revision, int layout) {
        if (revision < 0) {
            throw new IllegalArgumentException("Invalid cached revision");
        }
        if (!validLayout(layout)) {
            throw new IllegalArgumentException("Invalid GUI layout");
        }
        if (width < 1 || height < 1 || width > 256 || height > 256) {
            throw new IllegalArgumentException("Invalid image dimensions");
        }
        byte[] result = Arrays.copyOf(withSession(OPEN, session, width, height), 30);
        ByteBuffer.wrap(result, 21, 8).putLong(revision);
        result[29] = (byte) layout;
        return result;
    }

    public static byte[] chunk(byte[] encoded) throws IOException {
        GuiPayloads.readChunk(encoded);
        byte[] result = new byte[encoded.length + 1];
        result[0] = CHUNK;
        System.arraycopy(encoded, 0, result, 1, encoded.length);
        return result;
    }

    public static List<byte[]> frameChunks(UUID session, long revision, byte[] frame) throws IOException {
        List<byte[]> packets = new ArrayList<>();
        for (byte[] part : GuiPayloads.split(session, revision, frame)) {
            packets.add(chunk(part));
        }
        return List.copyOf(packets);
    }

    public static byte[] ack(UUID session, long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("Invalid ACK revision");
        }
        byte[] result = Arrays.copyOf(withSession(CLOSE, session, 0, 0), 25);
        result[0] = ACK;
        ByteBuffer.wrap(result, 17, 8).putLong(revision);
        return result;
    }

    public static byte[] displayed(UUID owner, UUID session, long revision) {
        if (revision <= 0) {
            throw new IllegalArgumentException("Invalid displayed revision");
        }
        byte[] result = Arrays.copyOf(resync(owner, session, revision), 41);
        result[0] = DISPLAYED;
        return result;
    }

    public static byte[] interest(UUID owner, UUID session, long revision, boolean active, boolean retainedDisplay) {
        byte[] result = resync(owner, session, revision);
        result[0] = INTEREST;
        result[41] = (byte) ((active ? 1 : 0) | (retainedDisplay ? 2 : 0));
        return result;
    }

    public static byte[] offer(UUID session, long revision, int bytes, int snapshotBytes, boolean initialization) {
        byte[] result = Arrays.copyOf(admission(OFFER, session, revision, bytes, snapshotBytes), 34);
        result[33] = (byte) (initialization ? 1 : 0);
        return result;
    }

    public static byte[] grant(UUID session, long revision, int bytes, int waitMs) {
        return admission(GRANT, session, revision, bytes, waitMs);
    }

    private static byte[] admission(int type, UUID session, long revision, int bytes, int extra) {
        if (revision <= 0 || bytes < 0 || bytes > GuiPayloads.MAX_UPLOAD_BYTES || extra < 0 || extra > GuiPayloads.MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid frame admission");
        }
        byte[] result = Arrays.copyOf(ack(session, revision), 33);
        result[0] = (byte) type;
        ByteBuffer.wrap(result, 25, 8).putInt(bytes).putInt(extra);
        return result;
    }

    public static byte[] status(UUID session, long revision) {
        byte[] result = ack(session, revision);
        result[0] = STATUS;
        return result;
    }

    public static byte[] state(UUID session, long revision) {
        byte[] result = ack(session, revision);
        result[0] = STATE;
        return result;
    }

    private static boolean validLayout(int flags) {
        return (flags & ~7) == 0 && (flags & 3) <= FULLSCREEN
                && !((flags & 3) == CHAT && (flags & CIRCULAR) != 0);
    }

    private static boolean validCodecs(int codecs) {
        return (codecs & FrameCodec.SUPPORTED_CODECS) == FrameCodec.SUPPORTED_CODECS && (codecs & ~ALL_CODECS) == 0;
    }

    public static Message read(byte[] bytes) throws IOException {
        if (bytes.length < 1 || bytes.length > MAX_BYTES) {
            throw new IOException("Invalid wire length");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int type = in.readUnsignedByte();
        if (type == HELLO) {
            if (in.available() != 24) {
                throw new IOException("Invalid HELLO length");
            }
            int codecs = in.readUnsignedByte();
            if ((codecs & FrameCodec.SUPPORTED_CODECS) != FrameCodec.SUPPORTED_CODECS ||
                    (codecs & ~ALL_CODECS) != 0 ||
                    in.readUnsignedShort() != 256 || in.readUnsignedShort() != 256 ||
                    in.readUnsignedShort() != GuiPayloads.MAX_CHUNK_BYTES ||
                    in.readInt() != GuiPayloads.MAX_IMAGE_BYTES) {
                throw new IOException("Unsupported HELLO capabilities");
            }
            int distance = in.readUnsignedShort(), receive = in.readUnsignedByte();
            double guiScale = in.readDouble();
            int guiUpdateIntervalMs = in.readUnsignedShort();
            if (distance < 1 || distance > 128 || receive > 3 || !Double.isFinite(guiScale) || guiScale < 0.25 || guiScale > 4
                    || guiUpdateIntervalMs < 100 || guiUpdateIntervalMs > 10_000) {
                throw new IOException("Invalid HELLO settings");
            }
            return new Message(type, null, 0, 0, 0, null, null, 0, 0, 0, codecs, distance,
                    (receive & 1) != 0, (receive & 2) != 0, guiScale, guiUpdateIntervalMs);
        }
        if (type == ACK || type == STATUS || type == STATE || type == OFFER || type == GRANT) {
            if (in.available() != (type == OFFER ? 33 : type == GRANT ? 32 : 24)) {
                throw new IOException("Invalid admission length");
            }
            UUID session = readUuid(in);
            long revision = in.readLong();
            if (revision < 0) {
                throw new IOException("Invalid ACK revision");
            }
            int size = 0, extra = 0;
            if (type == OFFER || type == GRANT) {
                size = in.readInt();
                extra = in.readInt();
                if (revision == 0 || size < 0 || size > GuiPayloads.MAX_UPLOAD_BYTES || extra < 0 || extra > GuiPayloads.MAX_UPLOAD_BYTES) {
                    throw new IOException("Invalid admission size");
                }
            }
            int flags = type == OFFER ? in.readUnsignedByte() : 0;
            if (flags > 1) {
                throw new IOException("Invalid admission flags");
            }
            return new Message(type, session, size, extra, 0, null, null, revision, 0, 0, flags);
        }
        if (type == DISPLAYED) {
            if (in.available() != 40) {
                throw new IOException("Invalid display confirmation length");
            }
            UUID target = readUuid(in), session = readUuid(in);
            long revision = in.readLong();
            if (revision <= 0) {
                throw new IOException("Invalid displayed revision");
            }
            return new Message(type, session, 0, 0, 0, null, target, revision, 0, 0, 0);
        }
        if (type == CHUNK) {
            byte[] chunk = in.readAllBytes();
            var header = GuiPayloads.readChunk(chunk);
            return new Message(type, header.session(), 0, 0, 0, chunk, null, 0, 0, 0, 0);
        }
        if (type == MOUSE) {
            if (in.available() != 29) {
                throw new IOException("Invalid MOUSE length");
            }
            UUID session = readUuid(in);
            long sequence = in.readLong();
            int u = in.readUnsignedShort(), v = in.readUnsignedShort(), flags = in.readUnsignedByte();
            if (sequence <= 0 || (flags & ~15) != 0) {
                throw new IOException("Invalid MOUSE state");
            }
            return new Message(type, session, 0, 0, 0, null, null, sequence, u, v, flags);
        }
        if (type == RESYNC || type == CACHE_READY || type == INTEREST) {
            if (in.available() != 41) {
                throw new IOException("Invalid RESYNC length");
            }
            UUID target = readUuid(in), session = readUuid(in);
            long revision = in.readLong();
            int codecs = in.readUnsignedByte();
            if (revision < 0 || (type == INTEREST ? codecs > 3 : !validCodecs(codecs))) {
                throw new IOException("Invalid receiver state");
            }
            return new Message(type, session, 0, 0, 0, null, target, revision, 0, 0, codecs);
        }
        if (type != OPEN && type != CLOSE && type != WATCH) {
            throw new IOException("Unknown wire type");
        }
        if (in.available() < 16) {
            throw new IOException("Truncated session");
        }
        UUID session = readUuid(in);
        if (type == CLOSE) {
            if (in.available() != 0) {
                throw new IOException("Invalid CLOSE length");
            }
            return new Message(type, session, 0, 0, 0, null);
        }
        if (type == WATCH) {
            if (in.available() != 4) {
                throw new IOException("Invalid WATCH length");
            }
            int viewers = in.readUnsignedByte(), demand = in.readUnsignedByte();
            int full = in.readUnsignedByte(), circle = in.readUnsignedByte();
            if (viewers > MAX_PEERS || (demand & ~31) != 0 || !validCodecs(full) || !validCodecs(circle)) {
                throw new IOException("Invalid WATCH demand or codecs");
            }
            return new Message(type, session, full, circle, viewers, null, null, 0, 0, 0, demand);
        }
        if (in.available() != 13) {
            throw new IOException("Invalid OPEN length");
        }
        int first = in.readUnsignedShort();
        int second = in.readUnsignedShort();
        if (first < 1 || first > 256 || second < 1 || second > 256) {
            throw new IOException("Invalid OPEN dimensions");
        }
        long revision = in.readLong();
        if (revision < 0) {
            throw new IOException("Invalid cached revision");
        }
        int flags = in.readUnsignedByte();
        if (!validLayout(flags)) {
            throw new IOException("Invalid OPEN layout");
        }
        return new Message(type, session, first, second, 0, null, null, revision, 0, 0, flags);
    }

    private static byte[] withSession(int type, UUID session, int first, int second) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(type == CLOSE ? 17 : 21);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(type);
            writeUuid(out, session);
            if (type != CLOSE) {
                out.writeShort(first);
                out.writeShort(second);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
