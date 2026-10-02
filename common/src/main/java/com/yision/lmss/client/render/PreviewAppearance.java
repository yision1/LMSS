package com.yision.lmss.client.render;

public final class PreviewAppearance {
    private PreviewAppearance() {}

    public static double opacity(double distance, double maxDistance) {
        return 0.9 * fade((distance - maxDistance / 2) / (maxDistance / 2));
    }

    private static double fade(double t) {
        t = Math.clamp(t, 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }

    public static void compact(byte[] rgba) {
        for (int i = 0; i < rgba.length; i += 4) {
            if (rgba[i + 3] != 0) {
                for (int c = 0; c < 3; c++) {
                    rgba[i + c] = (byte) Math.min(255, ((rgba[i + c] & 255) + 1) & ~1);
                }
            }
        }
    }

    public static byte[] display(int width, int height, byte[] rgba, boolean blur) {
        return blur ? soften(width, height, rgba, true) : rgba.clone();
    }

    public static byte[] blur(int width, int height, byte[] rgba) {
        return soften(width, height, rgba, false);
    }

    private static byte[] soften(int width, int height, byte[] rgba, boolean feather) {
        byte[] result = new byte[rgba.length];
        int centerWeight = feather ? 6 : 4;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int visibleAlpha = feather ? rgba[i + 3] & 255 : 0;
                if (feather && visibleAlpha == 0) {
                    continue;
                }
                int alpha = 0, red = 0, green = 0, blue = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int sample = (Math.clamp(y + dy, 0, height - 1) * width
                                + Math.clamp(x + dx, 0, width - 1)) * 4;
                        int weight = (dx == 0 ? centerWeight : 1) * (dy == 0 ? centerWeight : 1);
                        int a = (rgba[sample + 3] & 255) * weight;
                        alpha += a;
                        red += (rgba[sample] & 255) * a;
                        green += (rgba[sample + 1] & 255) * a;
                        blue += (rgba[sample + 2] & 255) * a;
                    }
                }
                int a = feather ? visibleAlpha : (int) Math.round(alpha / 36.0);
                if (a == 0) {
                    continue;
                }
                result[i] = (byte) (red / alpha);
                result[i + 1] = (byte) (green / alpha);
                result[i + 2] = (byte) (blue / alpha);
                result[i + 3] = (byte) a;
            }
        }
        return result;
    }
}
