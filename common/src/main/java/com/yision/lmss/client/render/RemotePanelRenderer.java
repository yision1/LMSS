package com.yision.lmss.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yision.lmss.client.stream.ClientNetworkState;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class RemotePanelRenderer {
    private static final RemoteGuiTexture CURSOR = new RemoteGuiTexture(new UUID(0, 1));
    private static final RemoteGuiTexture CARET = new RemoteGuiTexture(new UUID(0, 2));
    private RemotePanelRenderer() {}

    public static void render(PoseStack stack, Camera camera, MultiBufferSource.BufferSource buffers, float partialTick,
            Matrix4f projection) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        ClientNetworkState.updateInterests(camera, projection, partialTick);
        var settings = ClientNetworkState.settings();
        double maxDistance = ClientNetworkState.maxShareDistance();
        var panels = ClientNetworkState.panels();
        panels.sort(Comparator.comparingDouble((ClientNetworkState.Panel panel) -> {
            var player = minecraft.level.getPlayerByUUID(panel.owner());
            return player == null ? 0 : player.position().distanceToSqr(camera.getPosition());
        }).reversed());
        for (ClientNetworkState.Panel panel : panels) {
            var publisher = minecraft.level.getPlayerByUUID(panel.owner());
            if (publisher == null || publisher.distanceToSqr(minecraft.player) > maxDistance * maxDistance) {
                continue;
            }
            float yaw = Mth.rotLerp(partialTick, publisher.yBodyRotO, publisher.yBodyRot);
            PanelGeometry.Pose pose = panel.chat() ? PanelGeometry.chatFacing(yaw) : PanelGeometry.facing(yaw);
            Vec3 center = publisher.getEyePosition(partialTick).add(vector(pose.offset()));
            Vec3 right = vector(pose.right()), up = vector(pose.up()), normal = vector(pose.normal());
            Vec3 offset = center.subtract(camera.getPosition());
            boolean own = panel.owner().equals(minecraft.player.getUUID());
            int alpha = (int) Math.round(255 * (own ? 0.9 : PreviewAppearance.opacity(offset.length(), maxDistance)));
            if (alpha == 0) {
                continue;
            }
            double halfWidth = (panel.chat() ? 0.5 : 0.7) * ClientNetworkState.guiScale();
            double halfHeight = halfWidth * panel.texture().height() / panel.texture().width();
            RenderType type = panel.texture().renderType();
            stack.pushPose();
            stack.translate(offset.x, offset.y, offset.z);
            VertexConsumer out = buffers.getBuffer(type);
            quad(out, stack, right, up, -halfWidth, -halfHeight, halfWidth, halfHeight,
                    0, 0, 1, 1);
            stack.popPose();
            flush(buffers, type, alpha);
            ClientNetworkState.displayed(panel.owner());
            if (panel.chat() && panel.caretVisible()) {
                if (!CARET.ready()) {
                    CARET.update(ChatPreview.CARET_WIDTH, ChatPreview.CARET_HEIGHT, ChatPreview.caretPixels());
                }
                double x = (panel.u() * 2 - 1) * halfWidth;
                double pixel = 2 * halfWidth / panel.texture().width();
                double paddingX = ChatPreview.CARET_PADDING * pixel;
                double paddingY = ChatPreview.CARET_PADDING * halfHeight * 0.75 /
                (ChatPreview.CARET_HEIGHT - 2 * ChatPreview.CARET_PADDING);
                RenderType caretType = CARET.renderType();
                double bias = offset.dot(normal) > 0 ? -0.005 : 0.005;
                stack.pushPose();
                stack.translate(offset.x + normal.x * bias, offset.y + normal.y * bias, offset.z + normal.z * bias);
                quad(buffers.getBuffer(caretType), stack, right, up,
                        x - paddingX, -halfHeight * 0.375 - paddingY, x + 2 * pixel + paddingX, halfHeight * 0.375 + paddingY,
                        0, 0, 1, 1);
                stack.popPose();
                flush(buffers, caretType, alpha);
            }
            if (panel.cursorValid()) {
                if (!CURSOR.ready()) {
                    CURSOR.update(CursorPixels.WIDTH, CursorPixels.HEIGHT,
                            settings.guiBlur ? CursorPixels.softened()
                            : CursorPixels.create());
                }
                double x = (panel.u() * 2 - 1) * halfWidth;
                double y = (1 - panel.v() * 2) * halfHeight;
                cursor(stack, buffers, offset, right, up, normal, x, y,
                        halfWidth * 1.12 / panel.texture().width(),
                        (int) Math.round(alpha * panel.cursorOpacity()));
            }
        }
    }

    private static void cursor(PoseStack stack, MultiBufferSource.BufferSource buffers, Vec3 offset, Vec3 right,
            Vec3 up, Vec3 normal, double x, double y, double pixelSize, int alpha) {
        RenderType type = CURSOR.renderType();
        stack.pushPose();
        double bias = offset.dot(normal) > 0 ? -0.005 : 0.005;
        stack.translate(offset.x + normal.x * bias, offset.y + normal.y * bias, offset.z + normal.z * bias);
        x -= CursorPixels.HOTSPOT * pixelSize;
        y += CursorPixels.HOTSPOT * pixelSize;
        quad(buffers.getBuffer(type), stack, right, up,
                x, y - CursorPixels.HEIGHT * pixelSize, x + CursorPixels.WIDTH * pixelSize, y, 0, 0, 1, 1);
        stack.popPose();
        flush(buffers, type, alpha);
    }

    private static void flush(MultiBufferSource.BufferSource buffers, RenderType type, int alpha) {
        float[] color = RenderSystem.getShaderColor().clone();
        try {
            RenderSystem.setShaderColor(1, 1, 1, alpha / 255f);
            buffers.endBatch(type);
        } finally {
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
        }
    }

    public static void clear() {
        CURSOR.close();
        CARET.close();
    }

    private static Vec3 vector(PanelGeometry.Axis axis) {
        return new Vec3(axis.x(), axis.y(), axis.z());
    }

    private static void quad(VertexConsumer out, PoseStack stack, Vec3 right, Vec3 up,
            double left, double bottom, double rightEdge, double top,
            float u0, float v0, float u1, float v1) {
        vertex(out, stack, right, up, left, bottom, u0, v1);
        vertex(out, stack, right, up, rightEdge, bottom, u1, v1);
        vertex(out, stack, right, up, rightEdge, top, u1, v0);
        vertex(out, stack, right, up, left, top, u0, v0);
    }

    private static void vertex(VertexConsumer out, PoseStack stack, Vec3 right, Vec3 up,
            double horizontal, double vertical, float u, float v) {
        out.addVertex(stack.last().pose(), (float) (right.x * horizontal + up.x * vertical),
                (float) (up.y * vertical), (float) (right.z * horizontal + up.z * vertical))
        .setUv(u, v);
    }
}
