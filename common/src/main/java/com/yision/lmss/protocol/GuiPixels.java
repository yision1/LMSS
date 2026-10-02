package com.yision.lmss.protocol;

public final class GuiPixels {
    private GuiPixels() {}

    public static double edge(int width, int height, double u, double v) {
        double radius = Math.hypot((u - 0.5) * width, (v - 0.5) * height) / (Math.min(width, height) / 2.0);
        double t = Math.clamp((radius - 0.95) / 0.05, 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }

    public static byte[] circular(int width, int height, byte[] rgba) {
        byte[] result = rgba.clone();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int alpha = (int) Math.round((rgba[i + 3] & 255) * edge(width, height,
                        (x + 0.5) / width, (y + 0.5) / height));
                result[i + 3] = (byte) alpha;
                if (alpha == 0) {
                    result[i] = result[i + 1] = result[i + 2] = 0;
                }
            }
        }
        return result;
    }
}
