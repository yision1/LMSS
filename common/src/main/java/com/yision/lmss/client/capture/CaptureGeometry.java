package com.yision.lmss.client.capture;

import com.yision.lmss.client.render.ChatPreview;
import java.util.function.IntBinaryOperator;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class CaptureGeometry {
    private CaptureGeometry() {}

    public static boolean isFullscreenGradient(Matrix4f pose, int left, int top, int right, int bottom,
            int z, int width, int height) {
        if (width <= 0 || height <= 0 || pose.m01() != 0 || pose.m10() != 0 ||
                pose.m03() != 0 || pose.m13() != 0 || pose.m23() != 0 || pose.m33() != 1) {
            return false;
        }
        var first = pose.transformPosition(left, top, z, new Vector3f());
        var last = pose.transformPosition(right, bottom, z, new Vector3f());
        return Math.min(first.x, last.x) <= 0 && Math.min(first.y, last.y) <= 0 &&
                Math.max(first.x, last.x) >= width && Math.max(first.y, last.y) >= height;
    }

    public record Rect(int x0, int y0, int x1, int y1) {
        public int width() {
            return x1 - x0;
        }

        public int height() {
            return y1 - y0;
        }
    }

    public static Rect chatBounds(int guiWidth, int guiHeight) {
        return new Rect(0, 0, Math.min(ChatPreview.WIDTH, guiWidth),
                Math.min(ChatPreview.HEIGHT, guiHeight));
    }

    public static byte[] rgba(int width, int height, IntBinaryOperator bottomUpPixels) {
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0, i = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bottomUpPixels.applyAsInt(x, height - 1 - y);
                int alpha = pixel >>> 24;
                pixels[i++] = alpha == 0 ? 0 : (byte) pixel;
                pixels[i++] = alpha == 0 ? 0 : (byte) (pixel >>> 8);
                pixels[i++] = alpha == 0 ? 0 : (byte) (pixel >>> 16);
                pixels[i++] = (byte) alpha;
            }
        }
        return pixels;
    }

    public static Rect guiBounds(int width, int height) {
        int left = Math.floorDiv(width - 288, 2), top = Math.floorDiv(height - 288, 2);
        return new Rect(left, top, left + 288, top + 288);
    }

    public static Rect centeredSquare(int width, int height) {
        int size = Math.min(width, height);
        int left = (width - size) / 2;
        int top = (height - size) / 2;
        return new Rect(left, top, left + size, top + size);
    }

    public static Rect destination(Rect crop, int guiWidth, int guiHeight, int width, int height) {
        return new Rect((int) Math.round((Math.max(0, crop.x0()) - crop.x0()) * (double) width / crop.width()),
                (int) Math.round((crop.y1() - Math.min(guiHeight, crop.y1())) * (double) height / crop.height()),
                (int) Math.round((Math.min(guiWidth, crop.x1()) - crop.x0()) * (double) width / crop.width()),
                (int) Math.round((crop.y1() - Math.max(0, crop.y0())) * (double) height / crop.height()));
    }

    public static Rect map(int left, int top, int right, int bottom,
            int guiWidth, int guiHeight, int fbWidth, int fbHeight) {
        if (guiWidth <= 0 || guiHeight <= 0 || fbWidth <= 0 || fbHeight <= 0) {
            throw new IllegalArgumentException("Empty viewport");
        }
        int x0 = Math.clamp((int) ((long) left * fbWidth / guiWidth), 0, fbWidth);
        int x1 = Math.clamp((int) (((long) right * fbWidth + guiWidth - 1) / guiWidth), 0, fbWidth);
        int topPixel = Math.clamp((int) ((long) top * fbHeight / guiHeight), 0, fbHeight);
        int bottomPixel = Math.clamp((int) (((long) bottom * fbHeight + guiHeight - 1) / guiHeight), 0, fbHeight);
        return new Rect(x0, fbHeight - bottomPixel, x1, fbHeight - topPixel);
    }
}
