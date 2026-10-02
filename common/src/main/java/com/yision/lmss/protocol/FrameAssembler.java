package com.yision.lmss.protocol;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class FrameAssembler {
    private static final long TIMEOUT_NANOS = 5_000_000_000L;
    private final Map<Key, Pending> pending = new LinkedHashMap<>();
    private final int maxPending;

    public FrameAssembler(int maxPending) {
        if (maxPending < 1 || maxPending > 2) {
            throw new IllegalArgumentException("Invalid pending frame limit");
        }
        this.maxPending = maxPending;
    }

    public record Assembled(UUID session, long revision, byte[] bytes) {}
    private record Key(UUID session, long revision) {}

    private static final class Pending {
        final byte[][] chunks;
        final int totalLength;
        final long createdAt;
        long lastProgress;
        int receivedBytes;
        int receivedCount;

        Pending(GuiPayloads.Chunk first, long createdAt) {
            chunks = new byte[first.count()][];
            totalLength = first.totalLength();
            this.createdAt = createdAt;
            this.lastProgress = createdAt;
        }
    }

    public synchronized Optional<Assembled> accept(byte[] packet, long nowNanos) throws IOException {
        GuiPayloads.Chunk chunk = GuiPayloads.readChunk(packet);
        pending.entrySet().removeIf(entry -> nowNanos - entry.getValue().lastProgress >= TIMEOUT_NANOS ||
                nowNanos - entry.getValue().createdAt >= 900_000_000_000L);
        Key key = new Key(chunk.session(), chunk.revision());
        Pending frame = pending.get(key);
        if (frame == null) {
            if (pending.size() == maxPending) {
                Iterator<Key> oldest = pending.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            frame = new Pending(chunk, nowNanos);
            pending.put(key, frame);
        }
        if (frame.chunks.length != chunk.count() || frame.totalLength != chunk.totalLength()) {
            pending.remove(key);
            throw new IOException("Conflicting chunk metadata");
        }
        byte[] previous = frame.chunks[chunk.index()];
        if (previous != null) {
            if (!Arrays.equals(previous, chunk.data())) {
                pending.remove(key);
                throw new IOException("Conflicting duplicate chunk");
            }
            return Optional.empty();
        }
        frame.chunks[chunk.index()] = chunk.data();
        frame.lastProgress = nowNanos;
        frame.receivedBytes += chunk.data().length;
        frame.receivedCount++;
        if (frame.receivedBytes > frame.totalLength) {
            pending.remove(key);
            throw new IOException("Chunk data exceeds frame length");
        }
        if (frame.receivedCount != frame.chunks.length) {
            return Optional.empty();
        }
        pending.remove(key);
        if (frame.receivedBytes != frame.totalLength) {
            throw new IOException("Incomplete frame length");
        }
        byte[] bytes = new byte[frame.totalLength];
        int offset = 0;
        for (byte[] part : frame.chunks) {
            System.arraycopy(part, 0, bytes, offset, part.length);
            offset += part.length;
        }
        return Optional.of(new Assembled(chunk.session(), chunk.revision(), bytes));
    }

    public synchronized void clear() {
        pending.clear();
    }

    public synchronized void discard(UUID session, long revision) {
        pending.remove(new Key(session, revision));
    }
}
