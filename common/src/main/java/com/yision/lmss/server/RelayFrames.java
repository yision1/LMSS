package com.yision.lmss.server;

import com.yision.lmss.protocol.FrameAssembler;
import com.yision.lmss.protocol.FrameBundle;
import com.yision.lmss.protocol.FrameCodec;
import com.yision.lmss.protocol.PacedSender;
import com.yision.lmss.protocol.WireMessages;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class RelayFrames {
    public static final int QUEUE_FRAMES = 32;
    public static final long QUEUE_BYTES = 512 * 1024;
    public static final long QUEUE_DELAY_NANOS = 1_000_000_000L;

    private RelayFrames() {}

    public record Stored(FrameCodec.Header header, List<byte[]> packets, long bytes) {
        public Stored(FrameCodec.Header header, List<byte[]> packets) {
            this(header, packets, packets.stream().mapToLong(PacedSender::wireBytes).sum());
        }

        public boolean supports(int codecs) {
            return (codecs & (1 << header.codec())) != 0;
        }
    }

    public record Stream(UUID session, long revision, Stored keyframe, byte[] lastFingerprint) {
        public boolean needsKeyframe() {
            return keyframe == null;
        }

        public boolean supports(int codecs) {
            return keyframe == null || keyframe.supports(codecs);
        }

        public long retainedBytes() {
            return keyframe == null ? 0 : keyframe.bytes();
        }
    }

    public record Accepted(Stream stream, Stored frame, Stored snapshot) {
        public Accepted(Stream stream, Stored frame) {
            this(stream, frame, null);
        }

        public boolean duplicate() {
            return frame == null && snapshot == null;
        }
    }

    public record Snapshot(UUID session, int width, int height, int layout, Stream full, Stream circle) {
        public Stream variant(boolean circular) {
            return circular && layout != WireMessages.CHAT ? circle : full;
        }

        public long retainedBytes() {
            return full.retainedBytes() + (circle == full ? 0 : circle.retainedBytes());
        }

        public Snapshot with(boolean circular, Stream next) {
            return new Snapshot(session, width, height, layout, circular ? full : next,
                    layout == WireMessages.CHAT ? next : circular ? next : circle);
        }
    }

    public static Snapshot empty(UUID root, int width, int height, int layout) {
        Stream full = emptyStream(WireMessages.variantSession(root, false));
        return new Snapshot(root, width, height, layout, full,
                layout == WireMessages.CHAT ? full : emptyStream(WireMessages.variantSession(root, true)));
    }

    private static Stream emptyStream(UUID session) {
        return new Stream(session, 0, null, null);
    }

    private static Accepted accept(Stream previous, int width, int height, FrameAssembler.Assembled assembled,
            int codecs) throws IOException {
                FrameCodec.Header header = FrameCodec.readHeader(assembled.bytes(), assembled.bytes().length);
                if (!header.session().equals(previous.session()) || !header.session().equals(assembled.session())
                        || header.revision() != assembled.revision() || header.width() != width || header.height() != height
                        || (codecs & (1 << header.codec())) == 0 || header.revision() < previous.revision()) {
                    throw new IOException("Invalid publishing frame");
                }
                byte[] fingerprint = fingerprint(assembled.bytes());
                if (header.revision() == previous.revision()) {
                    if (Arrays.equals(previous.lastFingerprint(), fingerprint)) {
                        return new Accepted(previous, null);
                    }
                    throw new IOException("Conflicting publishing revision");
                }
                boolean keyframe = header.type() == FrameCodec.KEYFRAME;
                if (!keyframe && (previous.keyframe() == null || header.baseRevision() != previous.revision())) {
                    throw new IOException("Missing publishing baseline");
                }
                if (header.revision() != previous.revision() + 1) {
                    throw new IOException("Publishing revision skipped");
                }
                Stored stored = store(header, assembled.bytes());
                return new Accepted(new Stream(previous.session(), header.revision(),
                        keyframe ? stored : previous.keyframe(), fingerprint), stored);
            }

    public static Accepted acceptBundle(Stream previous, int width, int height, FrameAssembler.Assembled assembled,
            FrameBundle bundle, int codecs) throws IOException {
                byte[] fingerprint = fingerprint(assembled.bytes());
                if (assembled.revision() == previous.revision() && Arrays.equals(previous.lastFingerprint(), fingerprint)) {
                    return new Accepted(previous, null);
                }
                Stream next = previous;
                Stored update = null, snapshot = null;
                if (bundle.update() != null) {
                    Accepted accepted = accept(previous, width, height,
                            new FrameAssembler.Assembled(assembled.session(), assembled.revision(), bundle.update()), codecs);
                    next = accepted.stream();
                    update = accepted.frame();
                } else if (assembled.revision() != previous.revision() || previous.keyframe() == null) {
                    throw new IOException("Snapshot without current baseline");
                }
                if (bundle.snapshot() != null) {
                    FrameCodec.Header header = FrameCodec.readHeader(bundle.snapshot(), bundle.snapshot().length);
                    if (!header.session().equals(next.session()) || !header.session().equals(assembled.session())
                            || header.revision() != next.revision() || header.revision() != assembled.revision()
                            || header.width() != width || header.height() != height || header.type() != FrameCodec.KEYFRAME
                            || (codecs & (1 << header.codec())) == 0) {
                        throw new IOException("Invalid initialization snapshot");
                    }
                    snapshot = store(header, bundle.snapshot());
                }
                return new Accepted(new Stream(next.session(), next.revision(), snapshot == null ? next.keyframe() : snapshot,
                        fingerprint), update, snapshot);
            }

    private static Stored store(FrameCodec.Header header, byte[] bytes) throws IOException {
        return new Stored(header, WireMessages.frameChunks(header.session(), header.revision(), bytes));
    }

    private static byte[] fingerprint(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static final class Delivery {
        private record Queued(Stored frame, long queuedAt) {}
        private final PacedSender output = new PacedSender();
        private final ArrayDeque<Queued> pending = new ArrayDeque<>();
        private Stored sending;
        private long revision, displayedRevision, pendingBytes;
        private boolean waiting = true, cachedOffered, retainedDisplay;

        public void reset(long restoredRevision) {
            reset(restoredRevision, restoredRevision > 0);
        }

        public void reset(long restoredRevision, boolean retainedDisplay) {
            output.clear();
            pending.clear();
            sending = null;
            pendingBytes = 0;
            revision = restoredRevision;
            this.retainedDisplay = retainedDisplay;
            displayedRevision = 0;
            waiting = restoredRevision == 0;
            cachedOffered = false;
        }

        public boolean synchronizedAt(long base) {
            return !waiting && queuedRevision() == base;
        }

        public boolean busy() {
            return output.busy();
        }

        public void pause() {
            pending.clear();
            pendingBytes = 0;
            waiting = true;
            cachedOffered = false;
            if (!output.busy()) {
                displayedRevision = revision;
            }
        }

        public boolean awaitingDisplay() {
            return sending != null || !pending.isEmpty() || revision > displayedRevision;
        }

        public boolean displayed(long confirmedRevision) {
            if (confirmedRevision <= displayedRevision || confirmedRevision != revision) {
                return false;
            }
            displayedRevision = confirmedRevision;
            return true;
        }

        private long queuedRevision() {
            return !pending.isEmpty() ? pending.getLast().frame().header().revision()
                    : sending != null ? sending.header().revision() : revision;
        }

        public boolean needsKeyframe(Stream stream, int codecs) {
            return waiting && (cachedOffered || stream.keyframe() == null || !stream.supports(codecs)
                    || stream.keyframe().header().revision() < stream.revision());
        }

        public void bootstrap(Stream stream, int codecs) {
            if (!waiting || cachedOffered || stream.keyframe() == null || !stream.supports(codecs)) {
                return;
            }
            if (retainedDisplay && stream.keyframe().header().revision() < stream.revision()) {
                return;
            }
            offer(stream.keyframe(), codecs);
            waiting = stream.keyframe().header().revision() < stream.revision();
            cachedOffered = true;
        }

        public void offer(Accepted accepted, int codecs) {
            Stored update = accepted.frame(), snapshot = accepted.snapshot();
            if (update != null && (update.header().type() == FrameCodec.KEYFRAME || synchronizedAt(update.header().baseRevision()))) {
                offer(update, codecs);
            } else if (snapshot != null && !synchronizedAt(snapshot.header().revision())) {
                offer(snapshot, codecs);
            }
        }

        private void offer(Stored frame, int codecs) {
            if (frame.header().revision() <= queuedRevision()) {
                return;
            }
            if (!frame.supports(codecs)) {
                waitForKeyframe();
                return;
            }
            if (frame.header().type() == FrameCodec.KEYFRAME) {
                pending.clear();
                pendingBytes = 0;
                waiting = false;
                cachedOffered = true;
            } else {
                if (waiting) {
                    return;
                }
                if (frame.header().baseRevision() != queuedRevision()
                        || pending.size() >= QUEUE_FRAMES || pendingBytes + frame.bytes() > QUEUE_BYTES) {
                    waitForKeyframe();
                    return;
                }
            }
            pending.addLast(new Queued(frame, System.nanoTime()));
            pendingBytes += frame.bytes();
        }

        public void expire(long now) {
            if (!pending.isEmpty() && pending.getFirst().frame().header().type() != FrameCodec.KEYFRAME
                    && now - pending.getFirst().queuedAt() >= QUEUE_DELAY_NANOS) {
                waitForKeyframe();
            }
        }

        private void waitForKeyframe() {
            pending.clear();
            pendingBytes = 0;
            waiting = true;
            cachedOffered = true;
        }

        public Stored next() {
            return output.busy() || revision > displayedRevision || pending.isEmpty() ? null : pending.getFirst().frame();
        }

        public void start(long now) {
            sending = pending.removeFirst().frame();
            pendingBytes -= sending.bytes();
            long target = sending.header().revision();
            boolean smallUpdate = sending.header().type() != FrameCodec.KEYFRAME
                    && FrameCodec.frameBytes(sending.header()) <= FrameCodec.SMALL_UPDATE_BYTES;
            output.start(sending.packets(), now, revision == 0, smallUpdate,
                    () -> {
                        revision = target;
                        sending = null;
                    });
        }

        public void pump(long now, Consumer<byte[]> send) {
            output.pump(now, send);
        }
    }
}
