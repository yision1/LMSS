package com.yision.lmss.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class FrameCodec {
    public static final int KEYFRAME = 1;
    public static final int FULL_TILES = 2;
    public static final int XOR_TILES = 3;
    public static final int FULL_RECTS = 4;
    public static final int XOR_RECTS = 5;
    public static final int FULL_SMALL_TILES = 6;
    public static final int XOR_SMALL_TILES = 7;
    public static final int MOVED_RECTS = 8;
    public static final int ZSTD = 2;
    public static final int WEBP = 3;
    public static final int SUPPORTED_CODECS = (1 << ZSTD) | (1 << WEBP);
    public static final int SMALL_UPDATE_BYTES = 4 * 1024;
    private static final int MAGIC = 0x4c4d5346;
    private static final int MAX_RAW_BYTES = 256 * 256 * 4 + 6 + 256 * 6;
    private static final PatchStrategy[] PATCH_STRATEGIES = {
        new PatchStrategy(32, FULL_TILES, XOR_TILES),
        new PatchStrategy(16, FULL_SMALL_TILES, XOR_SMALL_TILES),
        new PatchStrategy(32, FULL_RECTS, XOR_RECTS)
    };

    private record PatchStrategy(int tileSize, int fullType, int xorType) {}

    private FrameCodec() {}

    public record Frame(UUID session, long baseRevision, long revision, int width, int height,
            int type, int codec, byte[] rgba) {}

    public record Header(UUID session, long baseRevision, long revision, int width, int height,
            int type, int codec, int rawLength, int bodyLength) {}

    public static int availableCodecs() {
        return NativeCodecs.availableMask();
    }

    public static byte[] encodeKeyframe(UUID session, long revision, int width, int height, byte[] rgba, int codecs) {
        validateImage(width, height, rgba.length);
        return encode(session, 0, revision, width, height, KEYFRAME, rgba, codecs);
    }

    public static byte[] encodeUpdate(UUID session, long baseRevision, int width, int height,
            byte[] baseline, byte[] current, int codecs) {
        validateImage(width, height, baseline.length);
        validateImage(width, height, current.length);
        if (baseRevision <= 0 || baseRevision == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid base");
        }
        if (Arrays.equals(baseline, current)) {
            return null;
        }
        byte[] best = null;
        int changed = 0;
        int total = ((width + 31) / 32) * ((height + 31) / 32);
        for (PatchStrategy strategy : PATCH_STRATEGIES) {
            PatchSet patches = patches(width, height, baseline, current, strategy.tileSize(), rectangles(strategy.fullType()));
            if (strategy.fullType() == FULL_TILES) {
                changed = patches.changed();
            }
            byte[] full = encode(session, baseRevision, baseRevision + 1, width, height, strategy.fullType(), patches.full(), codecs);
            byte[] xor = encode(session, baseRevision, baseRevision + 1, width, height, strategy.xorType(), patches.xor(), codecs);
            byte[] candidate = xor.length < full.length ? xor : full;
            if (best == null || candidate.length < best.length) {
                best = candidate;
            }
        }
        for (boolean vertical : new boolean[] {true, false}) {
            int shift = matchingShift(width, height, baseline, current, vertical);
            if (shift == 0) {
                continue;
            }
            int dx = vertical ? 0 : shift, dy = vertical ? shift : 0;
            byte[] moved = moved(width, height, baseline, dx, dy);
            byte[] residual = patches(width, height, moved, current, 32, true).xor();
            byte[] raw = ByteBuffer.allocate(4 + residual.length)
            .putShort((short) dx).putShort((short) dy).put(residual).array();
            byte[] candidate = encode(session, baseRevision, baseRevision + 1, width, height, MOVED_RECTS, raw, codecs);
            if (candidate.length < best.length) {
                best = candidate;
            }
        }
        if (changed * 2 >= total) {
            byte[] key = encodeKeyframe(session, baseRevision + 1, width, height, current, codecs);
            if (key.length <= best.length) {
                best = key;
            }
        }
        return best;
    }

    private record PatchSet(byte[] full, byte[] xor, int changed) {}

    private static PatchSet patches(int width, int height, byte[] baseline, byte[] current, int tileSize, boolean rectangles) {
        int across = (width + tileSize - 1) / tileSize;
        try {
            ByteArrayOutputStream fullBytes = new ByteArrayOutputStream(), xorBytes = new ByteArrayOutputStream();
            DataOutputStream full = new DataOutputStream(fullBytes), xor = new DataOutputStream(xorBytes);
            full.writeShort(0);
            xor.writeShort(0);
            int count = 0;
            byte[] xorRow = new byte[tileSize * 4];
            for (int y = 0; y < height; y += tileSize) {
                for (int x = 0; x < width; x += tileSize) {
                    int tw = Math.min(tileSize, width - x), th = Math.min(tileSize, height - y);
                    int x0 = tw, y0 = th, x1 = 0, y1 = 0;
                    for (int row = 0; row < th; row++) {
                        for (int col = 0; col < tw; col++) {
                            int offset = ((y + row) * width + x + col) * 4;
                            if (!Arrays.equals(baseline, offset, offset + 4, current, offset, offset + 4)) {
                                x0 = Math.min(x0, col);
                                y0 = Math.min(y0, row);
                                x1 = Math.max(x1, col + 1);
                                y1 = Math.max(y1, row + 1);
                            }
                        }
                    }
                    if (x1 == 0) {
                        continue;
                    }
                    count++;
                    int tile = y / tileSize * across + x / tileSize;
                    full.writeShort(tile);
                    xor.writeShort(tile);
                    if (rectangles) {
                        writeRectangle(full, x0, y0, x1, y1);
                        writeRectangle(xor, x0, y0, x1, y1);
                    } else {
                        x0 = y0 = 0;
                        x1 = tw;
                        y1 = th;
                    }
                    for (int row = y0; row < y1; row++) {
                        int start = ((y + row) * width + x + x0) * 4;
                        int length = (x1 - x0) * 4;
                        full.write(current, start, length);
                        for (int col = 0; col < length; col++) {
                            xorRow[col] = (byte) (current[start + col] ^ baseline[start + col]);
                        }
                        xor.write(xorRow, 0, length);
                    }
                }
            }
            byte[] first = fullBytes.toByteArray(), second = xorBytes.toByteArray();
            ByteBuffer.wrap(first).putShort((short) count);
            ByteBuffer.wrap(second).putShort((short) count);
            return new PatchSet(first, second, count);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void writeRectangle(DataOutputStream out, int x0, int y0, int x1, int y1) throws IOException {
        out.writeByte(x0);
        out.writeByte(y0);
        out.writeByte(x1 - x0 - 1);
        out.writeByte(y1 - y0 - 1);
    }

    private static boolean smallTiles(int type) {
        return type == FULL_SMALL_TILES || type == XOR_SMALL_TILES;
    }

    private static boolean rectangles(int type) {
        return type == FULL_RECTS || type == XOR_RECTS || type == MOVED_RECTS;
    }

    private static boolean xor(int type) {
        return type == XOR_TILES || type == XOR_SMALL_TILES || type == XOR_RECTS || type == MOVED_RECTS;
    }

    private static int matchingShift(int width, int height, byte[] baseline, byte[] current, boolean vertical) {
        int lines = vertical ? height : width, length = vertical ? width : height;
        int[] before = new int[lines], after = new int[lines];
        Map<Integer, Integer> frequency = new HashMap<>();
        for (int line = 0; line < lines; line++) {
            int first = 1, second = 1;
            for (int pixel = 0; pixel < length; pixel++) {
                int offset = (vertical ? line * width + pixel : pixel * width + line) * 4;
                for (int c = 0; c < 4; c++) {
                    first = 31 * first + baseline[offset + c];
                    second = 31 * second + current[offset + c];
                }
            }
            before[line] = first;
            after[line] = second;
            frequency.merge(first, 1, Integer::sum);
        }
        int best = 0, bestScore = 0;
        for (int shift = -Math.min(64, lines - 1); shift <= Math.min(64, lines - 1); shift++) {
            if (shift == 0) {
                continue;
            }
            int score = 0, matches = 0;
            for (int line = Math.max(0, shift); line < Math.min(lines, lines + shift); line++) {
                if (before[line - shift] == after[line]) {
                    matches++;
                    score += 256 / frequency.get(before[line - shift]);
                }
            }
            if (matches * 4 >= lines && (score > bestScore || (score == bestScore && Math.abs(shift) < Math.abs(best)))) {
                best = shift;
                bestScore = score;
            }
        }
        return best;
    }

    private static byte[] moved(int width, int height, byte[] baseline, int dx, int dy) {
        byte[] result = new byte[baseline.length];
        int x0 = Math.max(0, dx), x1 = Math.min(width, width + dx);
        for (int y = Math.max(0, dy); y < Math.min(height, height + dy); y++) {
            System.arraycopy(baseline, ((y - dy) * width + x0 - dx) * 4, result, (y * width + x0) * 4, (x1 - x0) * 4);
        }
        return result;
    }

    public static int frameBytes(Header header) {
        return 24 + GuiPayloads.unsignedBytes(header.baseRevision()) + GuiPayloads.unsignedBytes(header.revision())
        + GuiPayloads.unsignedBytes(header.rawLength()) + GuiPayloads.unsignedBytes(header.bodyLength()) + header.bodyLength();
    }

    private static byte[] encode(UUID session, long baseRevision, long revision, int width, int height,
            int type, byte[] raw, int codecs) {
        if (revision <= 0 || raw.length < 1 || raw.length > MAX_RAW_BYTES) {
            throw new IllegalArgumentException("Invalid frame");
        }
        int codec = type == KEYFRAME ? WEBP : ZSTD;
        if ((codecs & (1 << codec)) == 0) {
            throw new IllegalArgumentException("Required codec unavailable");
        }
        byte[] body = type == KEYFRAME ? NativeCodecs.webp(width, height, raw) : NativeCodecs.zstd(raw);
        if (body == null) {
            throw new IllegalStateException("Required codec failed: " + codec);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(40 + body.length);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(type);
            out.writeByte(codec);
            out.writeLong(session.getMostSignificantBits());
            out.writeLong(session.getLeastSignificantBits());
            GuiPayloads.writeUnsigned(out, baseRevision);
            GuiPayloads.writeUnsigned(out, revision);
            out.writeByte(width - 1);
            out.writeByte(height - 1);
            GuiPayloads.writeUnsigned(out, raw.length);
            GuiPayloads.writeUnsigned(out, body.length);
            out.write(body);
            byte[] frame = bytes.toByteArray();
            if (frame.length > GuiPayloads.MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Frame too large");
            }
            return frame;
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static Frame decode(byte[] bytes) throws IOException {
        Header header = readHeader(bytes, bytes.length);
        byte[] body = Arrays.copyOfRange(bytes, bytes.length - header.bodyLength(), bytes.length);
        byte[] rgba = switch (header.codec) {
            case ZSTD -> NativeCodecs.unzstd(body, header.rawLength);
            case WEBP -> NativeCodecs.unwebp(body, header.width, header.height);
            default -> throw new IOException("Unknown codec");
        };
        if (header.type != KEYFRAME) {
            validateTiles(header.width, header.height, rgba, header.type);
        }
        return new Frame(header.session, header.baseRevision, header.revision, header.width, header.height,
                header.type, header.codec, rgba);
    }

    public static Header readHeader(byte[] prefix, int frameLength) throws IOException {
        if (prefix.length < 28 || frameLength < 29 || frameLength > GuiPayloads.MAX_IMAGE_BYTES ||
                prefix.length > frameLength) {
            throw new IOException("Invalid frame length");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(prefix));
        if (in.readInt() != MAGIC) {
            throw new IOException("Invalid frame signature");
        }
        int type = in.readUnsignedByte();
        int codec = in.readUnsignedByte();
        UUID session = new UUID(in.readLong(), in.readLong());
        long baseRevision = GuiPayloads.readUnsigned(in, 9);
        long revision = GuiPayloads.readUnsigned(in, 9);
        int width = in.readUnsignedByte() + 1;
        int height = in.readUnsignedByte() + 1;
        int rawLength = (int) GuiPayloads.readUnsigned(in, 3);
        int bodyLength = (int) GuiPayloads.readUnsigned(in, 3);
        try {
            validateDimensions(width, height);
        } catch (IllegalArgumentException bad) {
            throw new IOException(bad);
        }
        if (revision <= 0 || rawLength < 1 || rawLength > MAX_RAW_BYTES ||
                (type == KEYFRAME ? baseRevision != 0 || rawLength != width * height * 4
                        : (type < FULL_TILES || type > MOVED_RECTS) || baseRevision <= 0 ||
                        baseRevision == Long.MAX_VALUE || revision != baseRevision + 1) ||
                bodyLength < 1 || bodyLength != frameLength - (prefix.length - in.available()) ||
                codec != (type == KEYFRAME ? WEBP : ZSTD)) {
            throw new IOException("Invalid frame header");
        }
        return new Header(session, baseRevision, revision, width, height, type, codec, rawLength, bodyLength);
    }

    public static byte[] apply(byte[] baseline, Frame frame) throws IOException {
        if (frame.type == KEYFRAME) {
            return frame.rgba;
        }
        validateImage(frame.width, frame.height, baseline.length);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.rgba));
        byte[] result = frame.type == MOVED_RECTS
                ? moved(frame.width, frame.height, baseline, in.readShort(), in.readShort())
                : Arrays.copyOf(baseline, baseline.length);
        int tileSize = smallTiles(frame.type) ? 16 : 32, across = (frame.width + tileSize - 1) / tileSize;
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int tile = in.readUnsignedShort();
            int x = tile % across * tileSize, y = tile / across * tileSize;
            int tw = Math.min(tileSize, frame.width - x), th = Math.min(tileSize, frame.height - y);
            if (rectangles(frame.type)) {
                x += in.readUnsignedByte();
                y += in.readUnsignedByte();
                tw = in.readUnsignedByte() + 1;
                th = in.readUnsignedByte() + 1;
            }
            for (int row = 0; row < th; row++) {
                int start = ((y + row) * frame.width + x) * 4;
                if (!xor(frame.type)) {
                    in.readFully(result, start, tw * 4);
                } else {
                    for (int offset = 0; offset < tw * 4; offset++) {
                        result[start + offset] ^= in.readByte();
                    }
                }
            }
        }
        return result;
    }

    private static void validateTiles(int width, int height, byte[] raw, int type) throws IOException {
        if (raw.length < 2) {
            throw new IOException("Truncated tiles");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        if (type == MOVED_RECTS) {
            if (raw.length < 6) {
                throw new IOException("Truncated moved region");
            }
            int dx = in.readShort(), dy = in.readShort();
            if ((dx == 0 && dy == 0) || Math.abs(dx) >= width || Math.abs(dy) >= height) {
                throw new IOException("Invalid region move");
            }
        }
        int tileSize = smallTiles(type) ? 16 : 32;
        int count = in.readUnsignedShort(), across = (width + tileSize - 1) / tileSize;
        int total = across * ((height + tileSize - 1) / tileSize);
        if ((count == 0 && type != MOVED_RECTS) || count > total) {
            throw new IOException("Invalid tile count");
        }
        int previous = -1;
        for (int i = 0; i < count; i++) {
            if (in.available() < 2) {
                throw new IOException("Truncated tile index");
            }
            int tile = in.readUnsignedShort();
            if (tile <= previous || tile >= total) {
                throw new IOException("Invalid tile index");
            }
            previous = tile;
            int x = tile % across * tileSize, y = tile / across * tileSize;
            int tw = Math.min(tileSize, width - x), th = Math.min(tileSize, height - y);
            if (rectangles(type)) {
                if (in.available() < 4) {
                    throw new IOException("Truncated rectangle");
                }
                int dx = in.readUnsignedByte(), dy = in.readUnsignedByte();
                int rw = in.readUnsignedByte() + 1, rh = in.readUnsignedByte() + 1;
                if (dx + rw > tw || dy + rh > th) {
                    throw new IOException("Rectangle exceeds tile");
                }
                tw = rw;
                th = rh;
            }
            int length = tw * th * 4;
            if (in.available() < length) {
                throw new IOException("Truncated tile pixels");
            }
            in.skipNBytes(length);
        }
        if (in.available() != 0) {
            throw new IOException("Trailing tile bytes");
        }
    }

    private static void validateImage(int width, int height, int length) {
        validateDimensions(width, height);
        if (length != width * height * 4) {
            throw new IllegalArgumentException("Invalid image dimensions");
        }
    }

    private static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 256 || height > 256) {
            throw new IllegalArgumentException("Invalid image dimensions");
        }
    }
}
