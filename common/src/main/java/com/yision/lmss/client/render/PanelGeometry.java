package com.yision.lmss.client.render;

public final class PanelGeometry {
    private PanelGeometry() {}

    public record Axis(double x, double y, double z) {}
    public record Pose(Axis offset, Axis right, Axis up, Axis normal) {}

    public static double gestureCoordinate(double previous, double current, double resting, int flags, double t) {
        return (flags & 8) == 0 ? resting : previous + (current - previous) * t;
    }

    public static double cursorStart(double previous, double current, double next, int oldFlags, int newFlags, double t) {
        return (oldFlags & newFlags & 8) == 0 ? next : previous + (current - previous) * t;
    }

    public static double ease(double t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static float click(long elapsedNanos) {
        double t = Math.clamp(elapsedNanos / 220_000_000.0, 0, 1);
        return (float) Math.pow(Math.sin(Math.PI * t), 2);
    }

    public static Pose facing(double yawDegrees) {
        return facing(yawDegrees, 15, 1, -0.35);
    }

    public static Pose chatFacing(double yawDegrees) {
        return facing(yawDegrees, 20, 0.85, -0.42);
    }

    private static Pose facing(double yawDegrees, double tiltDegrees, double distance, double height) {
        double yaw = Math.toRadians(yawDegrees), tilt = Math.toRadians(tiltDegrees);
        double x = -Math.sin(yaw), z = Math.cos(yaw);
        return new Pose(new Axis(x * distance, height, z * distance), new Axis(-z, 0, x),
                new Axis(x * Math.sin(tilt), Math.cos(tilt), z * Math.sin(tilt)),
                new Axis(x * Math.cos(tilt), -Math.sin(tilt), z * Math.cos(tilt)));
    }
}
