package com.yision.lmss.client.stream;

import com.mojang.blaze3d.platform.NativeImage;
import com.yision.lmss.client.capture.CaptureController;
import com.yision.lmss.client.render.ChatPreview;
import com.yision.lmss.client.render.GestureAnimation;
import com.yision.lmss.client.render.PanelGeometry;
import com.yision.lmss.client.render.PanelVisibility;
import com.yision.lmss.client.render.PreviewAppearance;
import com.yision.lmss.client.render.RemoteGuiTexture;
import com.yision.lmss.client.render.RemotePanelRenderer;
import com.yision.lmss.config.SharingSettings.RenderMode;
import com.yision.lmss.config.SharingSettings;
import com.yision.lmss.protocol.FrameCodec;
import com.yision.lmss.protocol.GuiPixels;
import com.yision.lmss.protocol.GuiWirePayload;
import com.yision.lmss.protocol.WireMessages;
import com.yision.lmss.protocol.WireTransport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

public final class ClientNetworkState {
    private static final CaptureController CAPTURE = new CaptureController();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "LMSS network encoder");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final AtomicReference<Encoded> COMPLETED = new AtomicReference<>();
    private static final ThreadPoolExecutor DECODER = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(WireMessages.MAX_PEERS), task -> {
                Thread thread = new Thread(task, "LMSS network decoder");
                thread.setDaemon(true);
                return thread;
            });
    private static final Map<UUID, Remote> REMOTE = new LinkedHashMap<>();
    private static final Map<UUID, GestureAnimation> GESTURES = new LinkedHashMap<>();
    private static final WireTransport TRANSPORT = new WireTransport();
    private static Consumer<GuiWirePayload.Serverbound> send;
    private static BooleanSupplier canSend;
    private static RenderMode renderMode = RenderMode.DYNAMIC;
    private static SharingSettings settings = new SharingSettings();
    private static int maxShareDistance = 16;
    private static double serverGuiScale = 1;
    private static boolean serverCircularMask = true;
    private static int serverGuiUpdateIntervalMs = 500;
    private static boolean connected;
    private static Remote ownPreview;
    private static Screen ownScreen;
    private static CaptureController.Frame ownFrame;
    private static long ownSampleNanos;
    private static int ownGuiWidth, ownGuiHeight;

    private static GuiPublisher publisher;
    private static final GuiCache<GuiKey, GuiPublisher.Cache> PUBLISHED_CACHE = new GuiCache<>();
    private static final GuiCache<RemoteKey, GuiReceiver.Snapshot> RECEIVED_CACHE = new GuiCache<>();
    private record GuiKey(String screen, String title, int guiWidth, int guiHeight, int width, int height) {}
    private record RemoteKey(UUID owner, UUID session) {}
    private static GuiKey guiKey;
    private static long generation;
    private static Screen screen;
    private static UUID session;
    private static int width, height;
    private static CaptureController.Frame pendingCapture;
    private static long lastSampleNanos, lastMouseNanos, mouseSequence;
    private static int lastMouseU = -1, lastMouseV = -1, lastMouseFlags = -1;
    private static boolean queued;

    private record Completion(GuiPublisher.Work work, GuiPublisher.Encoded encoded) {}
    private record Encoded(long generation, GuiPublisher publisher, List<Completion> results) {}
    public record Panel(UUID owner, RemoteGuiTexture texture, double u, double v, boolean cursorValid, boolean chat,
            boolean caretVisible, double cursorOpacity) {}

    public static GestureAnimation.Sample gesture(UUID owner) {
        if (!settings.showGuiAnimations) {
            return null;
        }
        var minecraft = Minecraft.getInstance();
        boolean own = minecraft.player != null && owner.equals(minecraft.player.getUUID());
        Remote remote = own ? (ownScreen == minecraft.screen && canPreviewOwn(minecraft.screen) ? ownPreview : null)
                : (settings.showRemoteGui ? REMOTE.get(owner) : null);
        long now = System.nanoTime();
        GestureAnimation animation = GESTURES.get(owner);
        if (remote == null || remote.snapshot == null) {
            return animation == null ? null : animation.sample(null, now);
        }
        if (animation == null) {
            animation = new GestureAnimation();
            GESTURES.put(owner, animation);
        }
        double t = PanelGeometry.ease((now - remote.mouseNanos) / 100_000_000.0);
        float press = remote.clickNanos == 0 || (remote.mouseFlags & 8) == 0 ? 0
                : PanelGeometry.click(now - remote.clickNanos);
        return animation.sample(new GestureAnimation.Target(PanelGeometry.gestureCoordinate(
                remote.previousU, remote.mouseU, remote.restU, remote.mouseFlags, t),
                PanelGeometry.gestureCoordinate(
                        remote.previousV, remote.mouseV, remote.restV, remote.mouseFlags, t), press, remote.chat(),
                remote.lastTypingNanos == 0 ? Long.MAX_VALUE : now - remote.lastTypingNanos), now);
    }

    private static final class Remote {
        final UUID session;
        int layout;
        final int width, height;
        GuiDecodePipeline decoder;
        final RemoteGuiTexture texture;
        GuiReceiver.Snapshot snapshot;
        NativeImage preparedImage;
        long decodedRevision;
        boolean needsKeyframe;
        final ViewInterest interest = new ViewInterest();
        long lastUploadNanos, lastTypingNanos;
        long uploadedRevision, confirmedDisplayRevision, lastResyncNanos, mouseSequence, mouseNanos, clickNanos;
        double previousU, previousV, mouseU, mouseV;
        double restU = 0.5, restV = 0.5;
        int mouseFlags;

        Remote(UUID owner, UUID session, int width, int height, int layout) {
            this.layout = layout;
            this.session = session;
            this.width = width;
            this.height = height;
            texture = new RemoteGuiTexture(owner);
            decoder = new GuiDecodePipeline(session, width, height, layout, settings.guiBlur, DECODER);
        }

        boolean chat() {
            return (layout & 3) == WireMessages.CHAT;
        }
        boolean circular() {
            return (layout & WireMessages.CIRCULAR) != 0;
        }

        void acceptSnapshot(GuiReceiver.Snapshot next) {
            if (snapshot != null && next.revision() < snapshot.revision()) {
                return;
            }
            if (chat() && snapshot != null && !Arrays.equals(snapshot.rgba(), next.rgba())) {
                lastTypingNanos = System.nanoTime();
            }
            snapshot = next;
        }

        void close() {
            decoder.close();
            if (preparedImage != null) {
                preparedImage.close();
                preparedImage = null;
            }
            texture.close();
        }
    }

    private ClientNetworkState() {}

    public static SharingSettings settings() {
        return settings;
    }

    public static int maxShareDistance() {
        return maxShareDistance;
    }

    public static double guiScale() {
        return connected ? serverGuiScale : settings.guiScale;
    }

    public static boolean circularMask() {
        return connected ? serverCircularMask : settings.circularMask;
    }

    public static int guiUpdateIntervalMs() {
        return connected ? serverGuiUpdateIntervalMs : settings.guiUpdateIntervalMs;
    }

    public static void initialize(SharingSettings settings, Consumer<GuiWirePayload.Serverbound> sender,
            BooleanSupplier available) {
        ClientNetworkState.settings = settings;
        renderMode = settings.renderMode;
        PUBLISHED_CACHE.setLifetimeSeconds(settings.guiCacheSeconds);
        RECEIVED_CACHE.setLifetimeSeconds(settings.guiCacheSeconds);
        send = sender;
        canSend = available;
    }

    public static void receive(GuiWirePayload.Clientbound payload) {
        Minecraft.getInstance().execute(() -> handle(payload));
    }

    private static void handle(GuiWirePayload.Clientbound payload) {
        WireMessages.Message message;
        WireTransport.Incoming incoming;
        try {
            incoming = TRANSPORT.inbound(payload);
            message = WireMessages.read(incoming.message());
        } catch (IOException bad) {
            return;
        }
        if (message.type() == WireMessages.HELLO) {
            if (renderMode != RenderMode.OFF && canSend.getAsBoolean()) {
                int codecs = FrameCodec.availableCodecs();
                if (codecs != FrameCodec.SUPPORTED_CODECS) {
                    return;
                }
                connected = true;
                maxShareDistance = message.maxShareDistance();
                serverGuiScale = message.guiScale();
                serverCircularMask = message.circularMask();
                serverGuiUpdateIntervalMs = message.guiUpdateIntervalMs();
                closeOwnPreview();
                send(WireMessages.hello(codecs, settings.showRemoteGui));
            }
            return;
        }
        if (!connected) {
            return;
        }
        UUID owner = incoming.binding().owner();
        if (message.type() == WireMessages.WATCH) {
            if (publisher != null && message.session().equals(session) && Minecraft.getInstance().player != null &&
                    owner.equals(Minecraft.getInstance().player.getUUID())) {
                publisher.watch(message.flags(), message.width(), message.height());
                if (!publisher.observed()) {
                    pendingCapture = null;
                }
            }
        } else if (message.type() == WireMessages.ACK) {
            if (publisher != null) {
                publisher.acknowledge(message.session(), message.sequence());
            }
        } else if (message.type() == WireMessages.GRANT) {
            if (publisher != null) {
                publisher.grant(message.session(), message.sequence(), message.width(), message.height(), System.nanoTime());
            }
        } else if (message.type() == WireMessages.STATE) {
            Remote remote = REMOTE.get(owner);
            if (remote == null || !remote.session.equals(message.session())) {
                return;
            }
            remote.confirmedDisplayRevision = 0;
            if (message.sequence() == 0 || remote.decodedRevision != message.sequence()) {
                restartDecoder(remote);
            }
        } else if (message.type() == WireMessages.RESYNC) {
            if (publisher != null) {
                publisher.resync(message.session(), message.sequence(), message.flags());
            }
        } else if (message.type() == WireMessages.OPEN) {
            if (!settings.showRemoteGui) {
                return;
            }
            if (!REMOTE.containsKey(owner) && REMOTE.size() >= WireMessages.MAX_PEERS) {
                return;
            }
            Remote old = REMOTE.remove(owner);
            if (old != null) {
                cacheRemote(owner, old);
            }
            Remote remote = new Remote(owner, message.session(), message.width(), message.height(), message.flags());
            GuiReceiver.Snapshot cached = RECEIVED_CACHE.take(new RemoteKey(owner, message.session()), System.nanoTime());
            REMOTE.put(owner, remote);
            if (remote.decoder.restore(cached, message.sequence())) {
                remote.snapshot = cached;
                remote.decodedRevision = cached.revision();
            }
        } else if (message.type() == WireMessages.CLOSE) {
            Remote remote = REMOTE.get(owner);
            if (remote != null && remote.session.equals(message.session())) {
                REMOTE.remove(owner);
                cacheRemote(owner, remote);
            }
            TRANSPORT.forget(owner);
        } else if (message.type() == WireMessages.MOUSE) {
            Remote remote = REMOTE.get(owner);
            if (remote == null || !remote.session.equals(message.session()) ||
                    message.sequence() <= remote.mouseSequence) {
                return;
            }
            remote.mouseSequence = message.sequence();
            updateMouse(remote, message.u(), message.v(), message.flags(), System.nanoTime());
        } else if (message.type() == WireMessages.CHUNK) {
            Remote remote = REMOTE.get(owner);
            if (remote == null || !remote.session.equals(message.session())) {
                return;
            }
            if (!remote.decoder.offer(message.chunk(), System.nanoTime())) {
                remote.needsKeyframe = true;
                requestResync(owner, remote);
            }
        }
    }

    private static void cacheRemote(UUID owner, Remote remote) {
        if (remote.snapshot != null && !remote.needsKeyframe && remote.decodedRevision == remote.snapshot.revision()) {
            RECEIVED_CACHE.put(new RemoteKey(owner, remote.session), remote.snapshot, System.nanoTime());
        }
        remote.close();
    }

    private static void requestResync(UUID owner, Remote remote) {
        int codecs = FrameCodec.availableCodecs();
        if (codecs != FrameCodec.SUPPORTED_CODECS) {
            return;
        }
        long now = System.nanoTime();
        if (remote.lastResyncNanos != 0 && now - remote.lastResyncNanos < 1_000_000_000L) {
            return;
        }
        remote.lastResyncNanos = now;
        long revision = remote.decodedRevision;
        restartDecoder(remote);
        send(WireMessages.resync(owner, remote.session, revision, codecs));
    }

    private static void send(byte[] message) {
        var player = Minecraft.getInstance().player;
        try {
            send.accept(new GuiWirePayload.Serverbound(TRANSPORT.serverbound(player == null ? null : player.getUUID(), message)));
        } catch (IOException bad) {
            throw new IllegalStateException(bad);
        }
    }

    private static void restartDecoder(Remote remote) {
        remote.decoder.close();
        remote.decoder = new GuiDecodePipeline(remote.session, remote.width, remote.height, remote.layout, settings.guiBlur, DECODER);
        remote.decodedRevision = 0;
        remote.needsKeyframe = false;
        remote.confirmedDisplayRevision = 0;
        if (remote.preparedImage != null) {
            remote.preparedImage.close();
            remote.preparedImage = null;
        }
    }

    public static void updateInterests(Camera camera, Matrix4f projection, float partialTick) {
        var minecraft = Minecraft.getInstance();
        if (!connected || minecraft.level == null || minecraft.player == null) {
            return;
        }
        double horizontalTan = 1 / Math.abs(projection.m00()), verticalTan = 1 / Math.abs(projection.m11());
        if (!Double.isFinite(horizontalTan) || !Double.isFinite(verticalTan)) {
            return;
        }
        long now = System.nanoTime();
        var look = camera.getLookVector();
        var left = camera.getLeftVector();
        var up = camera.getUpVector();
        for (var entry : REMOTE.entrySet()) {
            Remote remote = entry.getValue();
            var player = minecraft.level.getPlayerByUUID(entry.getKey());
            boolean visible = false;
            if (settings.showRemoteGui && player != null && player.distanceToSqr(minecraft.player) <= maxShareDistance * maxShareDistance) {
                float yaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
                var pose = remote.chat() ? PanelGeometry.chatFacing(yaw) : PanelGeometry.facing(yaw);
                var offset = player.getEyePosition(partialTick).add(pose.offset().x(), pose.offset().y(), pose.offset().z()).subtract(camera.getPosition());
                double halfWidth = (remote.chat() ? 0.5 : 0.7) * guiScale();
                double radius = Math.hypot(halfWidth, halfWidth * remote.height / remote.width);
                visible = PanelVisibility.intersects(offset.x * left.x + offset.y * left.y + offset.z * left.z,
                        offset.x * up.x + offset.y * up.y + offset.z * up.z,
                        offset.x * look.x + offset.y * look.y + offset.z * look.z, radius, horizontalTan, verticalTan);
            }
            if (remote.interest.update(visible, now)) {
                send(WireMessages.interest(entry.getKey(), remote.session, remote.decodedRevision, remote.interest.active(), remote.snapshot != null));
            }
        }
    }

    private static void updateMouse(Remote remote, int u, int v, int flags, long now) {
        if ((flags & 8) != 0 && (flags & ~remote.mouseFlags & 3) != 0) {
            remote.clickNanos = now;
        }
        double t = PanelGeometry.ease((now - remote.mouseNanos) / 100_000_000.0);
        remote.previousU = PanelGeometry.cursorStart(
                remote.previousU, remote.mouseU, u / 65535.0, remote.mouseFlags, flags, t);
        remote.previousV = PanelGeometry.cursorStart(
                remote.previousV, remote.mouseV, v / 65535.0, remote.mouseFlags, flags, t);
        remote.mouseU = u / 65535.0;
        remote.mouseV = v / 65535.0;
        if ((flags & 8) != 0) {
            remote.restU = remote.mouseU;
            remote.restV = remote.mouseV;
        }
        remote.mouseFlags = flags;
        remote.mouseNanos = now;
    }

    public static List<Panel> panels() {
        collectDecoded();
        List<Panel> result = new ArrayList<>();
        long now = System.nanoTime();
        if (settings.showRemoteGui) {
            for (var entry : REMOTE.entrySet()) {
                addPanel(result, entry.getKey(), entry.getValue(), now);
            }
        }
        var minecraft = Minecraft.getInstance();
        if (ownPreview != null && ownScreen == minecraft.screen && canPreviewOwn(minecraft.screen)) {
            addPanel(result, minecraft.player.getUUID(), ownPreview, now);
        }
        return result;
    }

    public static void displayed(UUID owner) {
        Remote remote = REMOTE.get(owner);
        if (!connected || remote == null || !remote.interest.active() || remote.uploadedRevision <= remote.confirmedDisplayRevision
                || remote.decodedRevision != remote.uploadedRevision || remote.needsKeyframe) {
            return;
        }
        send(WireMessages.displayed(owner, remote.session, remote.uploadedRevision));
        remote.confirmedDisplayRevision = remote.uploadedRevision;
    }

    private static void addPanel(List<Panel> result, UUID owner, Remote remote, long now) {
        if (remote.snapshot == null) {
            return;
        }
        if (remote.uploadedRevision != remote.snapshot.revision() && (remote.preparedImage != null || remote == ownPreview) &&
                (remote.uploadedRevision == 0 || now - remote.lastUploadNanos >= settings.textureUpdateIntervalMs * 1_000_000L)) {
            if (remote == ownPreview) {
                remote.texture.update(remote.snapshot.width(), remote.snapshot.height(),
                        PreviewAppearance.display(remote.snapshot.width(), remote.snapshot.height(), remote.snapshot.rgba(),
                                !remote.chat() && settings.guiBlur));
            } else {
                remote.texture.update(remote.preparedImage);
                remote.preparedImage = null;
            }
            remote.uploadedRevision = remote.snapshot.revision();
            remote.lastUploadNanos = now;
        }
        double t = PanelGeometry.ease((now - remote.mouseNanos) / 100_000_000.0);
        double u = remote.chat() ? remote.mouseU : remote.previousU + (remote.mouseU - remote.previousU) * t;
        double v = remote.previousV + (remote.mouseV - remote.previousV) * t;
        result.add(new Panel(owner, remote.texture, u, v,
                !remote.chat() && (remote.mouseFlags & 8) != 0, remote.chat(),
                remote.mouseNanos != 0 && ChatPreview.caretVisible(
                        now - Math.max(remote.lastUploadNanos, remote.mouseNanos)),
                remote.circular() ? GuiPixels.edge(remote.snapshot.width(), remote.snapshot.height(), u, v) : 1));
    }

    private static boolean canPreviewOwn(Screen current) {
        var minecraft = Minecraft.getInstance();
        return renderMode != RenderMode.OFF && settings.showOwnGuiInThirdPerson && minecraft.player != null
                && minecraft.level != null && !minecraft.options.getCameraType().isFirstPerson()
                && CaptureController.supports(current) && (!(current instanceof ChatScreen) || settings.shareChatInput);
    }

    private static CaptureController.Frame previewOwn(Screen current, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!canPreviewOwn(current)) {
            closeOwnPreview();
            return null;
        }
        var size = CaptureController.outputSize(current);
        if (size == null) {
            closeOwnPreview();
            return null;
        }
        if (ownScreen != current || ownGuiWidth != current.width || ownGuiHeight != current.height ||
                (ownFrame != null && (ownFrame.width() != size.width() || ownFrame.height() != size.height()))) {
            closeOwnPreview();
            ownScreen = current;
            ownGuiWidth = current.width;
            ownGuiHeight = current.height;
            ownPreview = new Remote(Minecraft.getInstance().player.getUUID(), UUID.randomUUID(), size.width(), size.height(),
                    current instanceof ChatScreen ? WireMessages.CHAT : WireMessages.GUI);
        }
        long now = System.nanoTime();
        CaptureController.Frame captured = null;
        if (ownFrame == null || (renderMode == RenderMode.DYNAMIC && now - ownSampleNanos >= guiUpdateIntervalMs() * 1_000_000L)) {
            captured = CAPTURE.capture(current, graphics, mouseX, mouseY, partialTick);
            ownSampleNanos = now;
            if (captured != null && captured != ownFrame) {
                ownFrame = captured;
                ownPreview.layout = captured.layout() | (circularMask() && captured.layout() != WireMessages.CHAT
                        ? WireMessages.CIRCULAR : 0);
                long nextRevision = ownPreview.snapshot == null ? 1 : ownPreview.snapshot.revision() + 1;
                ownPreview.acceptSnapshot(new GuiReceiver.Snapshot(ownPreview.session, nextRevision,
                        captured.width(), captured.height(), -1,
                        ownPreview.circular() ? GuiPixels.circular(captured.width(), captured.height(), captured.rgba()) : captured.rgba(),
                        ownPreview.layout));
            }
        }
        if (ownFrame != null) {
            MouseState mouse = mouseState(ownFrame, current, mouseX, mouseY);
            if (ownPreview.mouseU != mouse.u / 65535.0 || ownPreview.mouseV != mouse.v / 65535.0 || ownPreview.mouseFlags != mouse.flags) {
                updateMouse(ownPreview, mouse.u, mouse.v, mouse.flags, now);
            }
        }
        return captured;
    }

    private static void closeOwnPreview() {
        if (ownPreview == null) {
            return;
        }
        ownPreview.close();
        ownPreview = null;
        ownFrame = null;
        ownScreen = null;
        ownSampleNanos = 0;
        if (session == null) {
            CAPTURE.close();
        }
    }

    public static void render(Screen current, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (CaptureController.isCapturing() || current != Minecraft.getInstance().screen) {
            return;
        }
        CaptureController.Frame ownCaptured = previewOwn(current, graphics, mouseX, mouseY, partialTick);
        if (renderMode == RenderMode.OFF || !settings.shareGui ||
                (current instanceof ChatScreen && !settings.shareChatInput) || !connected || Minecraft.getInstance().level == null ||
                !CaptureController.supports(current)) {
            closePublishing();
            return;
        }
        CaptureController.Size size = CaptureController.outputSize(current);
        if (size == null) {
            closePublishing();
            return;
        }
        if (screen != current || width != size.width() || height != size.height()
                || guiKey.guiWidth() != current.width || guiKey.guiHeight() != current.height) {
            closePublishing();
            screen = current;
            width = size.width();
            height = size.height();
            guiKey = new GuiKey(current.getClass().getName(), current.getTitle().getString(),
                    current.width, current.height, width, height);
            GuiPublisher.Cache cached = PUBLISHED_CACHE.take(guiKey, System.nanoTime());
            int layout = ownCaptured != null ? ownCaptured.layout() : cached != null ? cached.layout()
                    : current instanceof ChatScreen ? WireMessages.CHAT : WireMessages.GUI;
            if (cached != null && cached.layout() != layout) {
                cached = null;
            }
            registerPublishing(layout, cached);
        }
        Encoded ready = COMPLETED.getAndSet(null);
        if (ready != null && ready.generation() == generation && ready.publisher() == publisher) {
            queued = false;
            for (Completion completion : ready.results()) {
                publisher.completed(completion.work(), completion.encoded());
            }
        }
        pumpUpload();
        long now = System.nanoTime();
        boolean dynamic = renderMode == RenderMode.DYNAMIC;
        if (!queued && publisher.needsSample(dynamic,
                lastSampleNanos == 0 ? Long.MAX_VALUE : now - lastSampleNanos, guiUpdateIntervalMs() * 1_000_000L)) {
            lastSampleNanos = now;
            CaptureController.Frame frame = ownCaptured != null ? ownCaptured : pendingCapture != null ? pendingCapture
                    : CAPTURE.capture(current, graphics, mouseX, mouseY, partialTick);
            pendingCapture = null;
            if (frame != null) {
                schedule(frame, dynamic);
            }
        }
        if (publisher.observed()) {
            sendMouse(mouseX, mouseY, now);
        }
    }

    private static void registerPublishing(int layout, GuiPublisher.Cache cached) {
        resetPublishingWork();
        if (session != null) {
            send(WireMessages.close(session));
        }
        if (Minecraft.getInstance().player != null) {
            TRANSPORT.forget(Minecraft.getInstance().player.getUUID());
        }
        session = cached == null ? UUID.randomUUID() : cached.session();
        publisher = new GuiPublisher(session, layout, cached);
        send(WireMessages.open(session, width, height, 0, layout));
    }

    private static void schedule(CaptureController.Frame frame, boolean dynamic) {
        if (frame.layout() != publisher.layout()) {
            registerPublishing(frame.layout(), null);
            pendingCapture = frame;
            return;
        }
        GuiPublisher expected = publisher;
        List<GuiPublisher.Work> work = expected.work(frame, dynamic);
        if (work.isEmpty()) {
            return;
        }
        queued = true;
        long expectedGeneration = generation;
        WORKER.execute(() -> {
            List<Completion> results = new ArrayList<>();
            for (GuiPublisher.Work task : work) {
                GuiPublisher.Encoded encoded = null;
                try {
                    encoded = task.encode();
                } catch (IOException | RuntimeException bad) {}
                results.add(new Completion(task, encoded));
            }
            COMPLETED.set(new Encoded(expectedGeneration, expected, List.copyOf(results)));
        });
    }

    private static void pumpUpload() {
        if (publisher != null) {
            publisher.pump(System.nanoTime(), ClientNetworkState::send);
        }
    }

    private record MouseState(int u, int v, int flags) {}

    private static MouseState mouseState(CaptureController.Frame capture, Screen current, int mouseX, int mouseY) {
        if (current instanceof ChatScreen) {
            return new MouseState(CaptureController.chatCaret(current), 32768, 0);
        }
        int left = capture.cropLeft(), top = capture.cropTop();
        int cropWidth = capture.cropWidth(), cropHeight = capture.cropHeight();
        boolean valid = mouseX >= left && mouseX < left + cropWidth &&
                mouseY >= top && mouseY < top + cropHeight;
        int u = valid ? Math.min(65535, Math.max(0, (mouseX - left) * 65535 / cropWidth)) : 0;
        int v = valid ? Math.min(65535, Math.max(0, (mouseY - top) * 65535 / cropHeight)) : 0;
        long window = Minecraft.getInstance().getWindow().getWindow();
        int flags = (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS ? 1 : 0) |
                (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS ? 2 : 0) |
                (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS ? 4 : 0) |
                (valid ? 8 : 0);
        return new MouseState(u, v, flags);
    }

    private static void sendMouse(int mouseX, int mouseY, long now) {
        CaptureController.Frame capture = publisher.capture();
        if ((!settings.sendMouseInfo && !(screen instanceof ChatScreen)) || capture == null) {
            return;
        }
        MouseState mouse = mouseState(capture, screen, mouseX, mouseY);
        if (mouse.flags == lastMouseFlags && mouse.u == lastMouseU && mouse.v == lastMouseV) {
            return;
        }
        if (mouse.flags == lastMouseFlags && now - lastMouseNanos < 100_000_000L) {
            return;
        }
        lastMouseNanos = now;
        lastMouseU = mouse.u;
        lastMouseV = mouse.v;
        lastMouseFlags = mouse.flags;
        send(WireMessages.mouse(session, ++mouseSequence, mouse.u, mouse.v, mouse.flags));
    }

    public static void tick() {
        collectDecoded();
        long now = System.nanoTime();
        if (ownScreen != Minecraft.getInstance().screen || !canPreviewOwn(Minecraft.getInstance().screen)) {
            closeOwnPreview();
        }
        var level = Minecraft.getInstance().level;
        GESTURES.keySet().removeIf(owner -> level == null || level.getPlayerByUUID(owner) == null || gesture(owner) == null);
        PUBLISHED_CACHE.expire(now);
        RECEIVED_CACHE.expire(now);
        if (screen != null && Minecraft.getInstance().screen != screen) {
            closePublishing();
        } else if (screen != null) {
            pumpUpload();
        }
    }

    private static void collectDecoded() {
        for (var entry : REMOTE.entrySet()) {
            Remote remote = entry.getValue();
            GuiDecodePipeline.Prepared ready = remote.decoder.take();
            if (ready != null) {
                if (ready.needsKeyframe()) {
                    remote.needsKeyframe = true;
                } else {
                    if (remote.preparedImage != null) {
                        remote.preparedImage.close();
                    }
                    remote.preparedImage = ready.image();
                    remote.snapshot = ready.snapshot();
                    remote.decodedRevision = ready.snapshot().revision();
                    if (ready.typingChanged()) {
                        remote.lastTypingNanos = System.nanoTime();
                    }
                }
            }
            if (remote.needsKeyframe && remote.interest.active()) {
                requestResync(entry.getKey(), remote);
            }
        }
    }

    public static void disconnected() {
        Minecraft.getInstance().execute(() -> {
            connected = false;
            TRANSPORT.clear();
            maxShareDistance = 16;
            closeOwnPreview();
            closePublishing();
            for (Remote remote : REMOTE.values()) {
                remote.close();
            }
            REMOTE.clear();
            GESTURES.clear();
            PUBLISHED_CACHE.clear();
            RECEIVED_CACHE.clear();
            RemotePanelRenderer.clear();
        });
    }

    private static void closePublishing() {
        if (session == null && screen == null) {
            return;
        }
        if (connected && guiKey != null && publisher != null && publisher.capture() != null) {
            PUBLISHED_CACHE.put(guiKey, publisher.cache(), System.nanoTime());
        }
        guiKey = null;
        resetPublishingWork();
        if (session != null && connected) {
            send(WireMessages.close(session));
        }
        if (Minecraft.getInstance().player != null) {
            TRANSPORT.forget(Minecraft.getInstance().player.getUUID());
        }
        publisher = null;
        screen = null;
        session = null;
        width = height = 0;
        CAPTURE.close();
    }

    private static void resetPublishingWork() {
        generation++;
        pendingCapture = null;
        lastSampleNanos = lastMouseNanos = mouseSequence = 0;
        lastMouseU = lastMouseV = lastMouseFlags = -1;
        queued = false;
        COMPLETED.set(null);
        WORKER.getQueue().clear();
    }
}
