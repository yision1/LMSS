package com.yision.lmss.client.stream;

import com.mojang.blaze3d.platform.NativeImage;
import com.yision.lmss.client.render.PreviewAppearance;
import com.yision.lmss.client.render.RemoteGuiTexture;
import com.yision.lmss.protocol.FrameAssembler;
import com.yision.lmss.protocol.GuiPayloads;
import com.yision.lmss.protocol.WireMessages;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

public final class GuiDecodePipeline implements AutoCloseable {
    private static final int MAX_QUEUED_BYTES = 2 * (GuiPayloads.MAX_IMAGE_BYTES
            + GuiPayloads.MAX_CHUNKS * GuiPayloads.MAX_HEADER_BYTES);
    public record Prepared(GuiReceiver.Snapshot snapshot, NativeImage image, boolean typingChanged,
            boolean needsKeyframe) implements AutoCloseable {
                public void close() {
                    if (image != null) {
                        image.close();
                    }
                }
            }

    private record Chunk(byte[] bytes, long receivedAt) {}
    private final Executor executor;
    private final Runnable task = this::drain;
    private final GuiReceiver receiver = new GuiReceiver();
    private final FrameAssembler assembler = new FrameAssembler(1);
    private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
    private final boolean chat, blur;
    private int queuedBytes;
    private boolean scheduled, closed, failed;
    private GuiReceiver.Snapshot cached, previous;
    private Prepared completed;

    public GuiDecodePipeline(UUID session, int width, int height, int layout, boolean blur, Executor executor) {
        this.executor = executor;
        chat = (layout & 3) == WireMessages.CHAT;
        this.blur = blur && !chat;
        receiver.open(session, width, height, layout);
    }

    public synchronized boolean restore(GuiReceiver.Snapshot snapshot, long expectedRevision) {
        if (closed || scheduled || !receiver.restore(snapshot, expectedRevision)) {
            return false;
        }
        cached = previous = snapshot;
        schedule();
        return true;
    }

    public synchronized boolean offer(byte[] chunk, long now) {
        if (closed || failed) {
            return false;
        }
        if (chunk.length > MAX_QUEUED_BYTES - queuedBytes) {
            fail();
            return false;
        }
        chunks.addLast(new Chunk(chunk, now));
        queuedBytes += chunk.length;
        schedule();
        return !failed;
    }

    public synchronized Prepared take() {
        Prepared next = completed;
        completed = null;
        return next;
    }

    private void schedule() {
        if (scheduled) {
            return;
        }
        scheduled = true;
        try {
            executor.execute(task);
        } catch (RejectedExecutionException full) {
            fail();
        }
    }

    private void drain() {
        while (true) {
            Chunk chunk;
            GuiReceiver.Snapshot next;
            synchronized (this) {
                if (closed || failed) {
                    scheduled = false;
                    return;
                }
                next = cached;
                cached = null;
                chunk = next == null ? chunks.pollFirst() : null;
                if (chunk == null && next == null) {
                    scheduled = false;
                    return;
                }
                if (chunk != null) {
                    queuedBytes -= chunk.bytes().length;
                }
            }
            try {
                if (chunk != null) {
                    var assembled = assembler.accept(chunk.bytes(), chunk.receivedAt());
                    if (assembled.isEmpty()) {
                        continue;
                    }
                    next = receiver.accept(assembled.get());
                    if (next == null) {
                        if (receiver.needsKeyframe()) {
                            synchronized (this) {
                                fail();
                            } return;
                        }
                        continue;
                    }
                }
                boolean typingChanged = chat && previous != null && !Arrays.equals(previous.rgba(), next.rgba());
                NativeImage image = RemoteGuiTexture.prepare(next.width(), next.height(),
                        PreviewAppearance.display(next.width(), next.height(), next.rgba(), blur));
                previous = next;
                synchronized (this) {
                    if (closed || failed) {
                        image.close();
                        return;
                    }
                    if (completed != null) {
                        typingChanged |= completed.typingChanged();
                        completed.close();
                    }
                    completed = new Prepared(next, image, typingChanged, false);
                }
            } catch (IOException | RuntimeException | LinkageError bad) {
                synchronized (this) {
                    if (!closed) {
                        fail();
                    }
                }
                return;
            }
        }
    }

    private void fail() {
        failed = true;
        chunks.clear();
        queuedBytes = 0;
        cached = null;
        if (completed != null) {
            completed.close();
        }
        completed = new Prepared(null, null, false, true);
    }

    @Override
    public synchronized void close() {
        closed = true;
        chunks.clear();
        queuedBytes = 0;
        cached = null;
        if (completed != null) {
            completed.close();
        }
        completed = null;
        if (executor instanceof ThreadPoolExecutor pool) {
            pool.remove(task);
        }
    }
}
