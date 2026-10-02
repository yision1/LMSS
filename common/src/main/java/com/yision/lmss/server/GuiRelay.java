package com.yision.lmss.server;

import com.yision.lmss.config.SharingSettings;
import com.yision.lmss.protocol.BandwidthBudget;
import com.yision.lmss.protocol.FrameAssembler;
import com.yision.lmss.protocol.FrameBundle;
import com.yision.lmss.protocol.FrameCodec;
import com.yision.lmss.protocol.GuiPayloads;
import com.yision.lmss.protocol.GuiWirePayload;
import com.yision.lmss.protocol.WireMessages;
import com.yision.lmss.protocol.WireTransport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class GuiRelay {
    private static final int UPLOAD_RATE = 1024 * 1024, DOWNLOAD_RATE = 500 * 1024, TOTAL_RATE = 1024 * 1024;
    private static final long CACHE_BYTES = 64L * 1024 * 1024, INTEREST_LEASE = 3_000_000_000L;
    private final int maxShareDistance, guiUpdateIntervalMs;
    private final double guiScale;
    private final boolean circularMask;
    private final long cacheLifetime;
    private final BiConsumer<ServerPlayer, GuiWirePayload.Clientbound> send;
    private final Map<UUID, Integer> compatible = new HashMap<>();
    private final Set<UUID> receiving = new HashSet<>();
    private final Map<UUID, Published> published = new LinkedHashMap<>();
    private final Map<UUID, Budget> budgets = new HashMap<>();
    private final Map<CacheKey, Closed> closed = new LinkedHashMap<>();
    private final BandwidthBudget totalDownload = new BandwidthBudget(TOTAL_RATE, TOTAL_RATE, System.nanoTime());
    private int publisherCursor, viewerCursor;

    private record CacheKey(UUID owner, UUID session) {}
    private record Closed(RelayFrames.Snapshot snapshot, long closedAt) {}

    private static final class Viewer {
        final boolean circular;
        int codecs;
        boolean ready, active, retainedDisplay;
        long interestAt, resumeRevision = -1;
        final RelayFrames.Delivery delivery = new RelayFrames.Delivery();
        Viewer(boolean circular, int codecs) {
            this.circular = circular;
            this.codecs = codecs;
        }
    }

    private static final class Admission {
        final WireMessages.Message offer;
        long expires, remaining;
        Admission(WireMessages.Message offer) {
            this.offer = offer;
        }
    }

    private static final class Published {
        RelayFrames.Snapshot snapshot;
        final FrameAssembler assembler = new FrameAssembler(2);
        final Map<UUID, Viewer> viewers = new LinkedHashMap<>();
        final Map<UUID, Admission> admissions = new LinkedHashMap<>();
        int demand = -1, fullCodecs, circleCodecs;
        long lastMouseNanos;
        int mouseFlags = -1;
        WireMessages.Message lastMouse, deferredMouse;
        Published(RelayFrames.Snapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private static final class Budget {
        final WireTransport transport = new WireTransport();
        final BandwidthBudget upload = new BandwidthBudget(UPLOAD_RATE, UPLOAD_RATE, System.nanoTime());
        final BandwidthBudget download = new BandwidthBudget(DOWNLOAD_RATE, DOWNLOAD_RATE, System.nanoTime());
        long lastOpenNanos;
        WireMessages.Message pendingOpen;
    }

    public GuiRelay(BiConsumer<ServerPlayer, GuiWirePayload.Clientbound> send, SharingSettings settings) {
        this.send = send;
        maxShareDistance = settings.maxShareDistance;
        guiScale = settings.guiScale;
        circularMask = settings.circularMask;
        guiUpdateIntervalMs = settings.guiUpdateIntervalMs;
        cacheLifetime = settings.guiCacheSeconds * 1_000_000_000L;
    }

    public void joined(ServerPlayer player) {
        budgets.computeIfAbsent(player.getUUID(), ignored -> new Budget());
        sendMessage(player, player.getUUID(), WireMessages.hello(WireMessages.ALL_CODECS, maxShareDistance,
                true, circularMask, guiScale, guiUpdateIntervalMs));
    }

    public void receive(ServerPlayer player, byte[] data) {
        UUID owner = player.getUUID();
        Budget budget = budgets.computeIfAbsent(owner, ignored -> new Budget());
        WireMessages.Message message;
        try {
            var incoming = budget.transport.inbound(data);
            message = WireMessages.read(incoming.message());
            if (incoming.binding() != null && message.target() == null && !incoming.binding().owner().equals(owner)) {
                return;
            }
        } catch (IOException | IllegalArgumentException bad) {
            return;
        }
        long now = System.nanoTime();
        if (message.type() != WireMessages.CHUNK) {
            budget.upload.charge(data.length + GuiPayloads.unsignedBytes(data.length), now);
        }
        if (message.type() == WireMessages.HELLO) {
            compatible.put(owner, message.flags());
            if (message.receiveGui()) {
                receiving.add(owner);
            } else {
                receiving.remove(owner);
            }
            return;
        }
        if (!compatible.containsKey(owner)) {
            return;
        }
        if (message.type() == WireMessages.OPEN) {
            if ((message.flags() & WireMessages.CIRCULAR) != 0 || message.sequence() != 0) {
                return;
            }
            budget.pendingOpen = message;
            openPending(player, budget, now);
        } else if (message.type() == WireMessages.CLOSE) {
            if (budget.pendingOpen != null && budget.pendingOpen.session().equals(message.session())) {
                budget.pendingOpen = null;
            }
            Published stream = published.get(owner);
            if (stream != null && stream.snapshot.session().equals(message.session())) {
                close(player, true);
            }
        } else if (message.type() == WireMessages.CHUNK) {
            receiveChunk(player, message, data.length + GuiPayloads.unsignedBytes(data.length), now);
        } else if (message.type() == WireMessages.OFFER) {
            Published stream = published.get(owner);
            if (stream == null) {
                return;
            }
            RelayFrames.Stream variant = variant(stream, message.session());
            if (variant == null || message.width() < 9 || message.height() > GuiPayloads.MAX_IMAGE_BYTES
                    || message.height() > message.width() - 8
                    || (message.sequence() != variant.revision() + 1 && message.sequence() != variant.revision())) {
                return;
            }
            Admission old = stream.admissions.get(message.session());
            if (old != null && old.expires > now) {
                if (old.offer.equals(message)) {
                    sendMessage(player, owner, WireMessages.grant(message.session(), message.sequence(), message.width(), 0));
                }
                return;
            }
            if (old != null) {
                budget.upload.refund(old.remaining, now);
                stream.assembler.discard(message.session(), old.offer.sequence());
            }
            stream.admissions.put(message.session(), new Admission(message));
        } else if (message.type() == WireMessages.STATUS) {
            Published stream = published.get(owner);
            RelayFrames.Stream variant = stream == null ? null : variant(stream, message.session());
            if (variant == null) {
                return;
            }
            Admission admission = stream.admissions.remove(message.session());
            if (admission != null) {
                budget.upload.refund(admission.remaining, now);
            }
            stream.assembler.discard(message.session(), message.sequence());
            if (message.sequence() == variant.revision() && (admission == null || admission.offer.height() == 0
                    || variant.keyframe().header().revision() == variant.revision())) {
                sendMessage(player, owner, WireMessages.ack(variant.session(), variant.revision()));
            } else {
                sendMessage(player, owner, WireMessages.grant(message.session(), message.sequence(), 0, 200));
            }
        } else if (message.type() == WireMessages.MOUSE) {
            Published stream = published.get(owner);
            if (stream == null || !stream.snapshot.session().equals(message.session())) {
                return;
            }
            if (stream.lastMouse != null && message.sequence() <= stream.lastMouse.sequence()) {
                return;
            }
            if (now - stream.lastMouseNanos < (message.flags() == stream.mouseFlags ? 100_000_000L : 20_000_000L)) {
                if (stream.deferredMouse == null || message.sequence() > stream.deferredMouse.sequence()) {
                    stream.deferredMouse = message;
                }
            } else {
                forwardMouse(player, stream, message, now);
            }
        } else {
            receiveViewer(player, message, now);
        }
    }

    private void receiveViewer(ServerPlayer player, WireMessages.Message message, long now) {
        Published stream = published.get(message.target());
        if (stream == null) {
            return;
        }
        Viewer viewer = stream.viewers.get(player.getUUID());
        ServerPlayer publisher = player.getServer().getPlayerList().getPlayer(message.target());
        if (viewer == null || publisher == null || !eligible(publisher).contains(player)) {
            return;
        }
        RelayFrames.Stream variant = stream.snapshot.variant(viewer.circular);
        if (!variant.session().equals(message.session())) {
            return;
        }
        if (message.type() == WireMessages.INTEREST) {
            viewer.interestAt = now;
            viewer.retainedDisplay = (message.flags() & 2) != 0;
            if ((message.flags() & 1) == 0) {
                viewer.active = viewer.ready = false;
                viewer.resumeRevision = -1;
                viewer.delivery.pause();
            } else if (!viewer.active) {
                viewer.active = true;
                viewer.resumeRevision = message.sequence();
            }
        } else if (message.type() == WireMessages.DISPLAYED) {
            if (viewer.active && viewer.ready) {
                viewer.delivery.displayed(message.sequence());
            }
        } else if (message.type() == WireMessages.RESYNC || message.type() == WireMessages.CACHE_READY) {
            if (!viewer.active) {
                return;
            }
            viewer.codecs = message.flags();
            viewer.retainedDisplay |= message.sequence() > 0;
            viewer.ready = false;
            viewer.resumeRevision = message.type() == WireMessages.CACHE_READY ? message.sequence() : 0;
            viewer.delivery.pause();
        }
        resume(player, publisher, stream, viewer, now);
        refresh(publisher, stream, now);
    }

    private void resume(ServerPlayer receiver, ServerPlayer publisher, Published stream, Viewer viewer, long now) {
        if (!viewer.active || viewer.resumeRevision < 0 || viewer.delivery.busy()) {
            return;
        }
        RelayFrames.Stream variant = stream.snapshot.variant(viewer.circular);
        long retained = viewer.resumeRevision, restored = retained == variant.revision() ? retained : 0;
        viewer.resumeRevision = -1;
        viewer.ready = true;
        viewer.delivery.reset(restored, viewer.retainedDisplay);
        sendMessage(receiver, publisher.getUUID(), WireMessages.state(variant.session(), restored));
        viewer.delivery.bootstrap(variant, viewer.codecs);
        if (stream.lastMouse != null) {
            sendMouse(publisher, receiver, variant.session(), stream.lastMouse, now);
        }
    }

    private static RelayFrames.Stream variant(Published stream, UUID session) {
        if (stream.snapshot.full().session().equals(session)) {
            return stream.snapshot.full();
        }
        return stream.snapshot.circle().session().equals(session) ? stream.snapshot.circle() : null;
    }

    private void receiveChunk(ServerPlayer player, WireMessages.Message message, int wireBytes, long now) {
        Published stream = published.get(player.getUUID());
        if (stream == null) {
            return;
        }
        Admission admission = stream.admissions.get(message.session());
        if (admission == null || admission.expires <= now) {
            budgets.get(player.getUUID()).upload.charge(wireBytes, now);
            return;
        }
        RelayFrames.Stream variant = variant(stream, message.session());
        if (variant == null) {
            return;
        }
        boolean circular = variant != stream.snapshot.full();
        try {
            GuiPayloads.Chunk chunk = GuiPayloads.readChunk(message.chunk());
            if (chunk.revision() != admission.offer.sequence() || chunk.totalLength() != admission.offer.width()
                    || wireBytes > admission.remaining) {
                throw new IOException("Upload exceeds admission");
            }
            admission.remaining -= wireBytes;
            var assembled = stream.assembler.accept(message.chunk(), now);
            if (assembled.isEmpty()) {
                return;
            }
            FrameBundle bundle = FrameBundle.decode(assembled.get().bytes());
            if ((bundle.snapshot() == null ? 0 : bundle.snapshot().length) != admission.offer.height()) {
                throw new IOException("Snapshot exceeds admission");
            }
            refresh(player, stream, now);
            RelayFrames.Accepted accepted = RelayFrames.acceptBundle(variant, stream.snapshot.width(), stream.snapshot.height(),
                    assembled.get(), bundle, circular ? stream.circleCodecs : stream.fullCodecs);
            RelayFrames.Snapshot result = stream.snapshot.with(circular, accepted.stream());
            if (!fitsCache(stream, result.retainedBytes())) {
                throw new IOException("Relay cache capacity reached");
            }
            stream.snapshot = result;
            if (!accepted.duplicate()) {
                for (Viewer viewer : stream.viewers.values()) {
                    if (!viewer.active || !viewer.ready || viewer.circular != circular) {
                        continue;
                    }
                    viewer.delivery.offer(accepted, viewer.codecs);
                }
            }
            stream.admissions.remove(message.session());
            budgets.get(player.getUUID()).upload.refund(admission.remaining, now);
            refresh(player, stream, now);
            sendMessage(player, player.getUUID(), WireMessages.ack(accepted.stream().session(), accepted.stream().revision()));
        } catch (IOException bad) {
            stream.admissions.remove(message.session());
            budgets.get(player.getUUID()).upload.refund(admission.remaining, now);
            stream.assembler.clear();
            sendMessage(player, player.getUUID(), WireMessages.resync(player.getUUID(), variant.session(), variant.revision(),
                    circular ? stream.circleCodecs : stream.fullCodecs));
        }
    }

    public void tick(MinecraftServer server) {
        long now = System.nanoTime();
        expire(now);
        for (var entry : budgets.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                openPending(player, entry.getValue(), now);
            }
        }
        List<UUID> owners = new ArrayList<>(published.keySet());
        for (int i = 0; i < owners.size(); i++) {
            UUID owner = owners.get((publisherCursor + i) % owners.size());
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                published.remove(owner);
                continue;
            }
            Published stream = published.get(owner);
            refresh(player, stream, now);
            admit(player, stream, now);
            if (stream.deferredMouse != null && now - stream.lastMouseNanos >=
                    (stream.deferredMouse.flags() == stream.mouseFlags ? 100_000_000L : 20_000_000L)) {
                forwardMouse(player, stream, stream.deferredMouse, now);
            }
            List<UUID> viewers = new ArrayList<>(stream.viewers.keySet());
            for (int j = 0; j < viewers.size(); j++) {
                UUID id = viewers.get((viewerCursor + j) % viewers.size());
                Viewer viewer = stream.viewers.get(id);
                ServerPlayer receiver = server.getPlayerList().getPlayer(id);
                if (receiver == null) {
                    continue;
                }
                viewer.delivery.pump(now, packet -> sendPacket(receiver, owner, packet, false));
                resume(receiver, player, stream, viewer, now);
                if (!viewer.active || !viewer.ready) {
                    continue;
                }
                RelayFrames.Stream variant = stream.snapshot.variant(viewer.circular);
                viewer.delivery.bootstrap(variant, viewer.codecs);
                RelayFrames.Stored frame = viewer.delivery.next();
                if (frame == null) {
                    continue;
                }
                long bytes = downloadBytes(receiver, owner, frame);
                if (!reserveDownload(id, bytes, now)) {
                    continue;
                }
                viewer.delivery.start(now);
                viewer.delivery.pump(now, packet -> sendPacket(receiver, owner, packet, false));
            }
        }
        publisherCursor = owners.isEmpty() ? 0 : (publisherCursor + 1) % owners.size();
        viewerCursor++;
        if (viewerCursor == Integer.MAX_VALUE) {
            viewerCursor = 0;
        }
    }

    private void admit(ServerPlayer player, Published stream, long now) {
        Budget budget = budgets.get(player.getUUID());
        for (var entry : new ArrayList<>(stream.admissions.entrySet())) {
            Admission admission = entry.getValue();
            var offer = admission.offer;
            if (admission.expires > now) {
                continue;
            }
            if (admission.expires != 0) {
                budget.upload.refund(admission.remaining, now);
                stream.assembler.discard(offer.session(), offer.sequence());
            }
            RelayFrames.Stream variant = variant(stream, offer.session());
            boolean circular = variant != stream.snapshot.full();
            int need = circular ? WireMessages.NEED_CIRCLE : WireMessages.NEED_FULL;
            long uploadBytes;
            try {
                uploadBytes = budget.transport.uploadBytes(player.getUUID(), offer.session(), offer.sequence(), offer.width());
            } catch (IOException bad) {
                stream.admissions.remove(entry.getKey());
                continue;
            }
            int wait = Math.max(budget.upload.waitMs(uploadBytes, now), downloadWait(player.getUUID(), stream, circular, offer, now));
            if ((stream.demand & need) == 0 || wait > 0 || ((stream.demand & WireMessages.PAUSE_UPDATES) != 0 && offer.flags() == 0)) {
                stream.admissions.remove(entry.getKey());
                sendMessage(player, player.getUUID(), WireMessages.grant(offer.session(), offer.sequence(), 0, Math.max(100, wait)));
            } else if (budget.upload.reserve(uploadBytes, now)) {
                admission.remaining = uploadBytes;
                admission.expires = now + 5_000_000_000L;
                sendMessage(player, player.getUUID(), WireMessages.grant(offer.session(), offer.sequence(), offer.width(), 0));
            }
        }
    }

    private int downloadWait(UUID owner, Published stream, boolean circular, WireMessages.Message offer, long now) {
        int updateBytes = offer.width() - 8 - offer.height(), wait = 0;
        long projected = 0;
        for (var entry : stream.viewers.entrySet()) {
            Viewer viewer = entry.getValue();
            if (!viewer.active || !viewer.ready || viewer.circular != circular) {
                continue;
            }
            int bytes = updateBytes > 0 && (offer.height() == 0 || viewer.delivery.synchronizedAt(offer.sequence() - 1))
                    ? updateBytes : viewer.delivery.synchronizedAt(offer.sequence()) ? 0 : offer.height();
            if (bytes == 0) {
                continue;
            }
            Budget receiver = budgets.get(entry.getKey());
            try {
                long wireBytes = receiver.transport.downloadBytes(owner, offer.session(), offer.sequence(), bytes);
                projected += wireBytes;
                wait = Math.max(wait, receiver.download.waitMs(wireBytes, now));
            } catch (IOException bad) {
                throw new IllegalStateException(bad);
            }
        }
        return Math.max(wait, totalDownload.waitMs(Math.min(projected, TOTAL_RATE / 2), now));
    }

    private void openPending(ServerPlayer player, Budget budget, long now) {
        if (budget.pendingOpen == null || (budget.lastOpenNanos != 0 && now - budget.lastOpenNanos < 100_000_000L)) {
            return;
        }
        WireMessages.Message message = budget.pendingOpen;
        budget.pendingOpen = null;
        budget.lastOpenNanos = now;
        close(player, true);
        Closed cached = closed.remove(new CacheKey(player.getUUID(), message.session()));
        RelayFrames.Snapshot snapshot = cached == null ? null : cached.snapshot();
        if (snapshot == null || now - cached.closedAt() >= cacheLifetime || snapshot.width() != message.width()
                || snapshot.height() != message.height() || snapshot.layout() != message.flags()) {
            snapshot = RelayFrames.empty(message.session(), message.width(), message.height(), message.flags());
        }
        Published stream = new Published(snapshot);
        published.put(player.getUUID(), stream);
        sendMessage(player, player.getUUID(), WireMessages.ack(snapshot.full().session(), snapshot.full().revision()));
        if (snapshot.circle() != snapshot.full()) {
            sendMessage(player, player.getUUID(), WireMessages.ack(snapshot.circle().session(), snapshot.circle().revision()));
        }
        refresh(player, stream, now);
    }

    private void refresh(ServerPlayer player, Published stream, long now) {
        Set<UUID> ids = new HashSet<>();
        for (ServerPlayer receiver : eligible(player)) {
            UUID id = receiver.getUUID();
            Viewer old = stream.viewers.get(id);
            if (old == null && !hasSubscriptionCapacity(id)) {
                continue;
            }
            ids.add(id);
            int codecs = compatible.get(id);
            if (old == null) {
                Viewer viewer = new Viewer(circularMask && stream.snapshot.layout() != WireMessages.CHAT, codecs);
                stream.viewers.put(id, viewer);
                RelayFrames.Stream variant = stream.snapshot.variant(viewer.circular);
                sendMessage(receiver, player.getUUID(), WireMessages.open(variant.session(), stream.snapshot.width(), stream.snapshot.height(),
                        variant.revision(), stream.snapshot.layout() | (viewer.circular ? WireMessages.CIRCULAR : 0)));
            } else {
                old.codecs = codecs;
            }
            if (ids.size() == WireMessages.MAX_PEERS) {
                break;
            }
        }
        for (UUID id : new ArrayList<>(stream.viewers.keySet())) {
            if (ids.contains(id)) {
                continue;
            }
            Viewer viewer = stream.viewers.remove(id);
            viewer.delivery.reset(0);
            ServerPlayer receiver = player.getServer().getPlayerList().getPlayer(id);
            if (receiver != null) {
                sendMessage(receiver, player.getUUID(), WireMessages.close(stream.snapshot.variant(viewer.circular).session()));
                budgets.get(id).transport.forget(player.getUUID());
            }
        }
        int fullCodecs = compatible.get(player.getUUID()), circleCodecs = fullCodecs, demand = 0, count = 0;
        for (Viewer viewer : stream.viewers.values()) {
            if (viewer.active && now - viewer.interestAt >= INTEREST_LEASE) {
                viewer.active = viewer.ready = false;
                viewer.resumeRevision = -1;
                viewer.delivery.pause();
            }
            if (!viewer.active) {
                continue;
            }
            if (viewer.delivery.next() == null) {
                viewer.delivery.expire(now);
            }
            count++;
            if (viewer.circular) {
                circleCodecs &= viewer.codecs;
                demand |= WireMessages.NEED_CIRCLE;
            } else {
                fullCodecs &= viewer.codecs;
                demand |= WireMessages.NEED_FULL;
            }
            if (viewer.ready && viewer.delivery.needsKeyframe(stream.snapshot.variant(viewer.circular), viewer.codecs)) {
                demand |= viewer.circular ? WireMessages.KEY_CIRCLE : WireMessages.KEY_FULL;
            }
            if (viewer.ready && viewer.delivery.awaitingDisplay()) {
                demand |= WireMessages.PAUSE_UPDATES;
            }
        }
        if ((demand & WireMessages.NEED_FULL) != 0 && stream.snapshot.full().needsKeyframe()) {
            demand |= WireMessages.KEY_FULL;
        }
        if ((demand & WireMessages.NEED_CIRCLE) != 0 && stream.snapshot.circle().needsKeyframe()) {
            demand |= WireMessages.KEY_CIRCLE;
        }
        if (demand != stream.demand || fullCodecs != stream.fullCodecs || circleCodecs != stream.circleCodecs) {
            stream.demand = demand;
            stream.fullCodecs = fullCodecs;
            stream.circleCodecs = circleCodecs;
            sendMessage(player, player.getUUID(), WireMessages.watch(stream.snapshot.session(), count, demand, fullCodecs, circleCodecs));
        }
    }

    private boolean hasSubscriptionCapacity(UUID receiver) {
        return published.values().stream().filter(stream -> stream.viewers.containsKey(receiver)).limit(WireMessages.MAX_PEERS).count() < WireMessages.MAX_PEERS;
    }

    private List<ServerPlayer> eligible(ServerPlayer player) {
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer receiver : player.serverLevel().getChunkSource().chunkMap.getPlayers(player.chunkPosition(), false)) {
            if (!receiver.getUUID().equals(player.getUUID()) && receiving.contains(receiver.getUUID()) && compatible.containsKey(receiver.getUUID())
                    && receiver.level() == player.level() && receiver.distanceToSqr(player) <= maxShareDistance * maxShareDistance) {
                result.add(receiver);
            }
        }
        return result;
    }

    private void forwardMouse(ServerPlayer player, Published stream, WireMessages.Message message, long now) {
        stream.lastMouseNanos = now;
        stream.mouseFlags = message.flags();
        stream.lastMouse = message;
        stream.deferredMouse = null;
        for (var entry : stream.viewers.entrySet()) {
            if (!entry.getValue().active || !entry.getValue().ready) {
                continue;
            }
            ServerPlayer receiver = player.getServer().getPlayerList().getPlayer(entry.getKey());
            if (receiver != null) {
                sendMouse(player, receiver, stream.snapshot.variant(entry.getValue().circular).session(), message, now);
            }
        }
    }

    private void sendMouse(ServerPlayer player, ServerPlayer receiver, UUID session, WireMessages.Message mouse, long now) {
        byte[] message = WireMessages.mouse(session, mouse.sequence(), mouse.u(), mouse.v(), mouse.flags());
        try {
            long bytes = budgets.get(receiver.getUUID()).transport.outboundBytes(player.getUUID(), message);
            if (reserveDownload(receiver.getUUID(), bytes, now)) {
                sendPacket(receiver, player.getUUID(), message, false);
            }
        } catch (IOException bad) {
            throw new IllegalStateException(bad);
        }
    }

    private void sendMessage(ServerPlayer receiver, UUID owner, byte[] message) {
        sendPacket(receiver, owner, message, true);
    }

    private void sendPacket(ServerPlayer receiver, UUID owner, byte[] message, boolean charge) {
        Budget budget = budgets.computeIfAbsent(receiver.getUUID(), ignored -> new Budget());
        try {
            if (charge) {
                long bytes = WireMessages.read(message).session() == null ? 33L + GuiPayloads.unsignedBytes(message.length) + message.length
                        : budget.transport.outboundBytes(owner, message);
                long now = System.nanoTime();
                budget.download.charge(bytes, now);
                totalDownload.charge(bytes, now);
            }
            send.accept(receiver, budget.transport.clientbound(owner, message));
        } catch (IOException bad) {
            throw new IllegalStateException(bad);
        }
    }

    private long downloadBytes(ServerPlayer receiver, UUID owner, RelayFrames.Stored frame) {
        try {
            return budgets.get(receiver.getUUID()).transport.downloadBytes(owner, frame.header().session(),
                    frame.header().revision(), FrameCodec.frameBytes(frame.header()));
        } catch (IOException bad) {
            throw new IllegalStateException(bad);
        }
    }

    private boolean reserveDownload(UUID receiver, long bytes, long now) {
        Budget budget = budgets.get(receiver);
        if (budget == null || !totalDownload.reserve(bytes, now)) {
            return false;
        }
        if (budget.download.reserve(bytes, now)) {
            return true;
        }
        totalDownload.refund(bytes, now);
        return false;
    }

    private boolean fitsCache(Published replacing, long bytes) {
        for (Published stream : published.values()) {
            if (stream != replacing) {
                bytes += stream.snapshot.retainedBytes();
            }
        }
        while (!closed.isEmpty() && bytes + closedBytes() > CACHE_BYTES) {
            closed.remove(closed.keySet().iterator().next());
        }
        return bytes + closedBytes() <= CACHE_BYTES;
    }

    private long closedBytes() {
        return closed.values().stream().mapToLong(value -> value.snapshot().retainedBytes()).sum();
    }

    private void expire(long now) {
        closed.values().removeIf(value -> now - value.closedAt() >= cacheLifetime);
    }

    private void close(ServerPlayer player, boolean retain) {
        Published stream = published.remove(player.getUUID());
        if (stream == null) {
            return;
        }
        stream.assembler.clear();
        Budget source = budgets.get(player.getUUID());
        for (Admission admission : stream.admissions.values()) {
            source.upload.refund(admission.remaining, System.nanoTime());
        }
        for (var entry : stream.viewers.entrySet()) {
            entry.getValue().delivery.reset(0);
            ServerPlayer receiver = player.getServer().getPlayerList().getPlayer(entry.getKey());
            if (receiver != null) {
                sendMessage(receiver, player.getUUID(), WireMessages.close(stream.snapshot.variant(entry.getValue().circular).session()));
            }
        }
        for (Budget budget : budgets.values()) {
            budget.transport.forget(player.getUUID());
        }
        if (retain && cacheLifetime > 0 && stream.snapshot.retainedBytes() > 0) {
            expire(System.nanoTime());
            closed.put(new CacheKey(player.getUUID(), stream.snapshot.session()), new Closed(stream.snapshot, System.nanoTime()));
            fitsCache(null, 0);
        }
    }

    public void disconnected(ServerPlayer player) {
        close(player, false);
        closed.keySet().removeIf(key -> key.owner().equals(player.getUUID()));
        compatible.remove(player.getUUID());
        receiving.remove(player.getUUID());
        budgets.remove(player.getUUID());
    }

    public void clear() {
        for (Published stream : published.values()) {
            for (Viewer viewer : stream.viewers.values()) {
                viewer.delivery.reset(0);
            }
        }
        compatible.clear();
        receiving.clear();
        published.clear();
        budgets.clear();
        closed.clear();
    }
}
