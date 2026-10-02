package com.yision.lmss.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public record FrameBundle(byte[] update, byte[] snapshot) {
    public byte[] encode() {
        int first = update == null ? 0 : update.length, second = snapshot == null ? 0 : snapshot.length;
        if (first + second == 0 || first > GuiPayloads.MAX_IMAGE_BYTES || second > GuiPayloads.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Invalid frame bundle");
        }
        ByteBuffer result = ByteBuffer.allocate(8 + first + second).putInt(first).putInt(second);
        if (update != null) {
            result.put(update);
        }
        if (snapshot != null) {
            result.put(snapshot);
        }
        return result.array();
    }

    public static FrameBundle decode(byte[] bytes) throws IOException {
        if (bytes.length < 8 || bytes.length > GuiPayloads.MAX_UPLOAD_BYTES) {
            throw new IOException("Invalid bundle length");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        int first = in.getInt(), second = in.getInt();
        if (first < 0 || second < 0 || first > GuiPayloads.MAX_IMAGE_BYTES || second > GuiPayloads.MAX_IMAGE_BYTES
                || first + second == 0 || first + second != bytes.length - 8) {
            throw new IOException("Invalid bundle frames");
        }
        return new FrameBundle(first == 0 ? null : Arrays.copyOfRange(bytes, 8, 8 + first),
                second == 0 ? null : Arrays.copyOfRange(bytes, 8 + first, bytes.length));
    }
}
