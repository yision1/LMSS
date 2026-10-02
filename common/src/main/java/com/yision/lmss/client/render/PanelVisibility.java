package com.yision.lmss.client.render;

public final class PanelVisibility {
    private static final double PRELOAD_FACTOR = 1.35;
    private PanelVisibility() {}

    public static boolean intersects(double horizontal, double vertical, double depth, double radius,
            double horizontalTan, double verticalTan) {
        horizontalTan *= PRELOAD_FACTOR;
        verticalTan *= PRELOAD_FACTOR;
        return depth + radius > 0
                && Math.abs(horizontal) <= Math.max(0, depth) * horizontalTan + radius * Math.sqrt(1 + horizontalTan * horizontalTan)
                && Math.abs(vertical) <= Math.max(0, depth) * verticalTan + radius * Math.sqrt(1 + verticalTan * verticalTan);
    }
}
