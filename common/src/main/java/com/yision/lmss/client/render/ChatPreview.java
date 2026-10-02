package com.yision.lmss.client.render;

import java.util.function.ToIntFunction;

public final class ChatPreview {
    public static final int WIDTH = 192, HEIGHT = 48, PADDING = 10, SCALE = 2;
    public static final int CARET_PADDING = 2, CARET_WIDTH = 6, CARET_HEIGHT = 28;

    public record Line(String text, int caretX) {}
    private ChatPreview() {}

    public static byte[] caretPixels() {
        byte[] pixels = new byte[CARET_WIDTH * CARET_HEIGHT * 4];
        for (int y = CARET_PADDING; y < CARET_HEIGHT - CARET_PADDING; y++) {
            for (int x = CARET_PADDING; x < CARET_WIDTH - CARET_PADDING; x++) {
                int i = (y * CARET_WIDTH + x) * 4;
                pixels[i] = (byte) 240;
                pixels[i + 1] = (byte) 242;
                pixels[i + 2] = (byte) 245;
                pixels[i + 3] = (byte) 255;
            }
        }
        return PreviewAppearance.blur(CARET_WIDTH, CARET_HEIGHT, pixels);
    }

    public static Line line(String text, int cursor, int width, ToIntFunction<String> measure) {
        cursor = Math.clamp(cursor, 0, text.length());
        if (cursor > 0 && cursor < text.length() && Character.isLowSurrogate(text.charAt(cursor))
                && Character.isHighSurrogate(text.charAt(cursor - 1))) {
            cursor--;
        }
        int available = Math.max(0, (width - 2 * PADDING) / SCALE - 1);
        int start = 0;
        while (start < cursor && measure.applyAsInt(text.substring(start, cursor)) > available) {
            start = text.offsetByCodePoints(start, 1);
        }
        int end = cursor;
        while (end < text.length()) {
            int next = text.offsetByCodePoints(end, 1);
            if (measure.applyAsInt(text.substring(start, next)) > available) {
                break;
            }
            end = next;
        }
        return new Line(text.substring(start, end), PADDING + SCALE * measure.applyAsInt(text.substring(start, cursor)));
    }

    public static float typingStroke(float ageTicks, long elapsedNanos) {
        double strength = 1 - PanelGeometry.ease((elapsedNanos - 750_000_000L) / 500_000_000.0);
        return (float) (Math.sin(ageTicks * 1.4) * 0.13 * strength);
    }

    public static boolean caretVisible(long elapsedNanos) {
        return elapsedNanos < 3_000_000_000L || (elapsedNanos - 3_000_000_000L) / 300_000_000L % 2 == 0;
    }
}
