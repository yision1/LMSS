package com.yision.lmss.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GuiPayloads {
    public static final int MAX_IMAGE_BYTES = 320 * 1024;
    public static final int MAX_UPLOAD_BYTES = 2 * MAX_IMAGE_BYTES + 8;
    public static final int MAX_CHUNK_BYTES = 2048;
    public static final int MAX_CHUNKS = (MAX_UPLOAD_BYTES + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
    public static final int MAX_HEADER_BYTES = 16 + 9 + 2 + 3;

    private GuiPayloads() {}

    public record Chunk(UUID session, long revision, int index, int count, int totalLength, byte[] data) {}

    public static List<byte[]> split(UUID session, long revision, byte[] frame) {
        if (revision <= 0 || frame.length == 0 || frame.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid frame size or revision");
        }
        int count = (frame.length + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
        List<byte[]> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int offset = index * MAX_CHUNK_BYTES;
            int length = Math.min(MAX_CHUNK_BYTES, frame.length - offset);
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(MAX_HEADER_BYTES + length);
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeLong(session.getMostSignificantBits());
                out.writeLong(session.getLeastSignificantBits());
                writeUnsigned(out, revision);
                writeUnsigned(out, index);
                writeUnsigned(out, frame.length);
                out.write(frame, offset, length);
                chunks.add(bytes.toByteArray());
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
        }
        return chunks;
    }

    public static Chunk readChunk(byte[] packet) throws IOException {
        if (packet.length < 20 || packet.length > MAX_HEADER_BYTES + MAX_CHUNK_BYTES) {
            throw new IOException("Invalid chunk packet length");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet));
        UUID session = new UUID(in.readLong(), in.readLong());
        long revision = readUnsigned(in, 9);
        int index = (int) readUnsigned(in, 2);
        int totalLength = (int) readUnsigned(in, 3);
        int count = (totalLength + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
        int length = in.available();
        if (revision <= 0 || count < 1 || count > MAX_CHUNKS || index >= count ||
                totalLength < 1 || totalLength > MAX_UPLOAD_BYTES || length < 1 ||
                length > MAX_CHUNK_BYTES ||
                length != Math.min(MAX_CHUNK_BYTES, totalLength - index * MAX_CHUNK_BYTES)) {
            throw new IOException("Invalid chunk header");
        }
        return new Chunk(session, revision, index, count, totalLength, in.readNBytes(length));
    }

    public static int unsignedBytes(long value) {
        int count = 1;
        while ((value >>>= 7) != 0) {
            count++;
        }
        return count;
    }

    public static void writeUnsigned(DataOutputStream out, long value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("Negative unsigned integer");
        }
        while (value >= 128) {
            out.writeByte((int) (value & 127) | 128);
            value >>>= 7;
        }
        out.writeByte((int) value);
    }

    public static long readUnsigned(DataInputStream in, int maxBytes) throws IOException {
        long value = 0;
        for (int index = 0; index < maxBytes; index++) {
            int next = in.readUnsignedByte();
            value |= (long) (next & 127) << (index * 7);
            if ((next & 128) == 0) {
                if (index != 0 && next == 0) {
                    throw new IOException("Noncanonical chunk integer");
                }
                return value;
            }
        }
        throw new IOException("Chunk integer exceeds limit");
    }
}
