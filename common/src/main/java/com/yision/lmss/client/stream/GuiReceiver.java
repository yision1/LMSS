package com.yision.lmss.client.stream;

import com.yision.lmss.protocol.FrameAssembler.Assembled;
import com.yision.lmss.protocol.FrameCodec;
import java.io.IOException;
import java.util.UUID;

public final class GuiReceiver {
    private UUID session;
    private long revision;
    private int width;
    private int height;
    private int layout;
    private byte[] pixels;
    private boolean needsKeyframe;

    public record Snapshot(UUID session, long revision, int width, int height, int codec, byte[] rgba, int layout) {}

    public synchronized void open(UUID newSession, int expectedWidth, int expectedHeight, int layout) {
        session = newSession;
        width = expectedWidth;
        height = expectedHeight;
        this.layout = layout;
        restart();
    }

    public synchronized boolean restore(Snapshot cached, long expectedRevision) {
        if (cached == null || !cached.session().equals(session) || cached.revision() != expectedRevision ||
                cached.width() != width || cached.height() != height || cached.layout() != layout) {
            return false;
        }
        pixels = cached.rgba();
        revision = cached.revision();
        return true;
    }

    public synchronized Snapshot accept(Assembled assembled) throws IOException {
        if (!assembled.session().equals(session) || assembled.revision() < revision ||
                (assembled.revision() == revision && !needsKeyframe)) {
            return null;
        }
        FrameCodec.Frame frame = FrameCodec.decode(assembled.bytes());
        if (!frame.session().equals(assembled.session()) || frame.revision() != assembled.revision()) {
            throw new IOException("Frame and chunk header disagree");
        }
        if (width != 0 && (frame.width() != width || frame.height() != height)) {
            throw new IOException("Frame and OPEN dimensions disagree");
        }
        if (frame.type() != FrameCodec.KEYFRAME &&
                (needsKeyframe || pixels == null || frame.baseRevision() != revision)) {
            needsKeyframe = true;
            return null;
        }
        byte[] next = FrameCodec.apply(pixels, frame);
        pixels = next;
        revision = frame.revision();
        needsKeyframe = false;
        return new Snapshot(session, revision, frame.width(), frame.height(), frame.codec(), next, layout);
    }

    public synchronized boolean needsKeyframe() {
        return needsKeyframe;
    }

    private void restart() {
        revision = 0;
        pixels = null;
        needsKeyframe = false;
    }
}
