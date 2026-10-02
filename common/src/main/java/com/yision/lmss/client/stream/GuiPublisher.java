package com.yision.lmss.client.stream;

import com.yision.lmss.client.capture.CaptureController;
import com.yision.lmss.protocol.FrameBundle;
import com.yision.lmss.protocol.FrameCodec;
import com.yision.lmss.protocol.GuiPixels;
import com.yision.lmss.protocol.WireMessages;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class GuiPublisher {
    public record Baseline(long revision, byte[] pixels, int codecs) {}
    public record Cache(UUID session, int layout, CaptureController.Frame capture, Baseline full, Baseline circle) {}
    public record Work(int index, long epoch, UUID session, long base, byte[] baseline, int codecs, long request,
            boolean keyframe, CaptureController.Frame capture) {
        public Encoded encode() throws IOException {
            byte[] pixels = index == 1 ? GuiPixels.circular(capture.width(), capture.height(), capture.rgba()) : capture.rgba();
            byte[] update = baseline == null || base == 0
                    ? FrameCodec.encodeKeyframe(session, base + 1, capture.width(), capture.height(), pixels, codecs)
                    : FrameCodec.encodeUpdate(session, base, capture.width(), capture.height(), baseline, pixels, codecs);
            long revision = update == null ? base : base + 1;
            byte[] snapshot = keyframe && (update == null || FrameCodec.readHeader(update, update.length).type() != FrameCodec.KEYFRAME)
                    ? FrameCodec.encodeKeyframe(session, revision, capture.width(), capture.height(), pixels, codecs) : null;
            if (update == null && snapshot == null) {
                return new Encoded(this, pixels, List.of(), base, 0, 0);
            }
            byte[] bundle = new FrameBundle(update, snapshot).encode();
            List<byte[]> packets = WireMessages.frameChunks(session, revision, bundle);
            return new Encoded(this, pixels, packets, revision, bundle.length, snapshot == null ? 0 : snapshot.length);
        }
    }

    public record Encoded(Work work, byte[] pixels, List<byte[]> packets, long revision, int bytes, int snapshotBytes) {}

    private static final class Channel {
        final UUID session;
        long revision, epoch, requested = 1, sampled, fulfilled, sentAt, offerAt, retryAt;
        byte[] pixels;
        int codecs = FrameCodec.SUPPORTED_CODECS;
        boolean demanded, working, initialized, refresh = true, keyDemand, granted;
        Encoded awaiting;
        Channel(UUID session) {
            this.session = session;
        }
    }

    private final UUID session;
    private final int layout;
    private final Channel[] channels;
    private CaptureController.Frame capture;
    private boolean paused;

    public GuiPublisher(UUID session, int layout, Cache cached) {
        this.session = session;
        this.layout = layout;
        channels = new Channel[] {new Channel(WireMessages.variantSession(session, false)),
            new Channel(WireMessages.variantSession(session, true))};
        if (cached != null && cached.session().equals(session) && cached.layout() == layout) {
            capture = cached.capture();
            restore(channels[0], cached.full());
            restore(channels[1], cached.circle());
        }
    }

    private static void restore(Channel channel, Baseline baseline) {
        channel.revision = baseline.revision();
        channel.pixels = baseline.pixels();
        channel.codecs = baseline.codecs();
        if (channel.pixels != null) {
            channel.requested = channel.fulfilled = 0;
        }
    }

    public Cache cache() {
        return new Cache(session, layout, capture, new Baseline(channels[0].revision, channels[0].pixels, channels[0].codecs),
                new Baseline(channels[1].revision, channels[1].pixels, channels[1].codecs));
    }

    public CaptureController.Frame capture() {
        return capture;
    }

    public int layout() {
        return layout;
    }

    public boolean observed() {
        return channels[0].demanded || channels[1].demanded;
    }

    public void watch(int demand, int fullCodecs, int circleCodecs) {
        paused = (demand & WireMessages.PAUSE_UPDATES) != 0;
        for (int index = 0; index < channels.length; index++) {
            Channel channel = channels[index];
            boolean demanded = (demand & (1 << index)) != 0 && (index == 0 || layout != WireMessages.CHAT);
            int codecs = index == 0 ? fullCodecs : circleCodecs;
            boolean keyDemand = (demand & (4 << index)) != 0;
            if ((demanded && !channel.demanded) || channel.codecs != codecs || (keyDemand && !channel.keyDemand)) {
                channel.refresh = true;
                if ((channel.codecs & ~codecs) != 0 || (keyDemand && !channel.keyDemand)) {
                    channel.requested++;
                }
            }
            channel.demanded = demanded;
            channel.codecs = codecs;
            channel.keyDemand = keyDemand;
            if (!demanded && channel.awaiting != null && !channel.granted) {
                channel.awaiting = null;
            }
        }
    }

    public boolean needsSample(boolean dynamic, long elapsedNanos, long intervalNanos) {
        long now = System.nanoTime();
        for (Channel channel : channels) {
            if (canSample(channel, dynamic, now)
                    && (elapsedNanos >= intervalNanos || channel.requested > channel.sampled)) {
                return true;
            }
        }
        return false;
    }

    private boolean canSample(Channel channel, boolean dynamic, long now) {
        boolean needsKeyframe = channel.requested > channel.fulfilled;
        return channel.demanded && channel.initialized && !channel.working && channel.awaiting == null
                && now >= channel.retryAt && (!paused || needsKeyframe)
                && (dynamic || channel.refresh || channel.pixels == null || needsKeyframe);
    }

    public List<Work> work(CaptureController.Frame next, boolean dynamic) {
        capture = next;
        List<Work> work = new ArrayList<>();
        for (int index = 0; index < channels.length; index++) {
            Channel channel = channels[index];
            if (!canSample(channel, dynamic, System.nanoTime())) {
                continue;
            }
            channel.working = true;
            channel.sampled = channel.requested;
            work.add(new Work(index, channel.epoch, channel.session, channel.revision, channel.pixels,
                    channel.codecs & FrameCodec.availableCodecs(), channel.requested, channel.requested > channel.fulfilled, next));
        }
        return List.copyOf(work);
    }

    public void completed(Work work, Encoded encoded) {
        Channel channel = channels[work.index()];
        if (channel.epoch != work.epoch()) {
            return;
        }
        channel.working = false;
        if (encoded == null || channel.revision != work.base() || !channel.demanded || (paused && !work.keyframe())
                || (channel.codecs & FrameCodec.availableCodecs()) != work.codecs()) {
            return;
        }
        if (encoded.packets().isEmpty()) {
            channel.refresh = false;
            return;
        }
        channel.awaiting = encoded;
        channel.offerAt = channel.sentAt = 0;
        channel.granted = false;
    }

    public void grant(UUID variant, long revision, int bytes, int waitMs, long now) {
        Channel channel = channel(variant);
        if (channel == null || channel.awaiting == null || channel.awaiting.revision() != revision) {
            return;
        }
        if (bytes == 0) {
            channel.retryAt = now + Math.max(100, waitMs) * 1_000_000L;
            channel.awaiting = null;
            channel.refresh = true;
            channel.granted = false;
        } else if (bytes == channel.awaiting.bytes()) {
            channel.granted = true;
            channel.sentAt = 0;
        }
    }

    public void acknowledge(UUID variant, long revision) {
        Channel channel = channel(variant);
        if (channel == null) {
            return;
        }
        if (!channel.initialized || revision == 0) {
            channel.initialized = true;
            if (channel.revision != revision || revision == 0) {
                channel.epoch++;
                channel.awaiting = null;
                channel.working = channel.granted = false;
                channel.revision = revision;
                channel.pixels = null;
                channel.requested++;
                channel.refresh = true;
            }
            return;
        }
        Encoded awaiting = channel.awaiting;
        if (awaiting == null || revision != awaiting.revision() || channel.sentAt == 0) {
            return;
        }
        channel.revision = revision;
        channel.pixels = awaiting.pixels();
        channel.fulfilled = awaiting.work().request();
        channel.refresh = channel.granted = false;
        channel.awaiting = null;
        channel.sentAt = channel.offerAt = 0;
    }

    public void resync(UUID variant, long revision, int codecs) {
        Channel channel = channel(variant);
        if (channel == null) {
            return;
        }
        if (channel.awaiting != null && revision == channel.awaiting.revision()) {
            acknowledge(variant, revision);
        }
        if (channel.revision != revision) {
            channel.revision = revision;
            channel.pixels = null;
        }
        channel.epoch++;
        channel.awaiting = null;
        channel.working = channel.granted = false;
        channel.codecs = codecs;
        channel.requested++;
        channel.refresh = channel.initialized = true;
    }

    public void pump(long now, Consumer<byte[]> send) {
        for (Channel channel : channels) {
            Encoded awaiting = channel.awaiting;
            if (awaiting == null) {
                continue;
            }
            if (channel.granted && channel.sentAt == 0) {
                channel.sentAt = now;
                try {
                    for (byte[] packet : awaiting.packets()) {
                        send.accept(packet);
                    }
                } catch (RuntimeException failure) {
                    channel.sentAt = now;
                }
            } else if (channel.sentAt != 0) {
                if (now - channel.sentAt >= 2_000_000_000L) {
                    send.accept(WireMessages.status(channel.session, awaiting.revision()));
                    channel.sentAt = now;
                }
            } else if (channel.demanded && (channel.offerAt == 0 || now - channel.offerAt >= 1_000_000_000L)) {
                send.accept(WireMessages.offer(channel.session, awaiting.revision(), awaiting.bytes(), awaiting.snapshotBytes(), awaiting.work().keyframe()));
                channel.offerAt = now;
            }
        }
    }

    private Channel channel(UUID variant) {
        for (Channel channel : channels) {
            if (channel.session.equals(variant)) {
                return channel;
            }
        }
        return null;
    }
}
