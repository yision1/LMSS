package com.yision.lmss.client.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.yision.lmss.client.capture.CaptureGeometry.Rect;
import com.yision.lmss.client.render.ChatPreview;
import com.yision.lmss.client.render.PreviewAppearance;
import com.yision.lmss.protocol.WireMessages;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

public final class CaptureController implements AutoCloseable {
    private TextureTarget target;
    private TextureTarget scene;
    private TextureTarget contentLayer;
    private static RenderTarget captureTarget;
    private static CaptureController activeCapture;
    private boolean hasContentLayer;
    private NativeImage outputPixels;
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);
    private final IntBuffer layerViewport = BufferUtils.createIntBuffer(4);
    private final FloatBuffer layerClearColor = BufferUtils.createFloatBuffer(4);
    private final IntBuffer scissorBox = BufferUtils.createIntBuffer(4);
    private Screen chatScreen;
    private String chatText;
    private int chatCursor;
    private Frame chatFrame;

    public static boolean isCapturing() {
        return captureTarget != null;
    }

    public static RenderTarget renderTarget() {
        return captureTarget;
    }

    public static void captureContentLayer() {
        if (activeCapture == null) {
            return;
        }
        activeCapture.saveContentLayer();
    }

    private void saveContentLayer() {
        int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (drawFbo <= 0) {
            return;
        }
        int readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, layerViewport);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, layerClearColor);
        try {
            if (contentLayer == null || contentLayer.width != scene.width || contentLayer.height != scene.height) {
                if (contentLayer != null) {
                    contentLayer.destroyBuffers();
                }
                contentLayer = new TextureTarget(scene.width, scene.height, false, Minecraft.ON_OSX);
            }
            RenderSystem.disableScissor();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, contentLayer.frameBufferId);
            GL30.glBlitFramebuffer(0, scene.height, scene.width, 0, 0, 0, scene.width, scene.height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            hasContentLayer = true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GlStateManager._bindTexture(texture);
            GlStateManager._viewport(layerViewport.get(0), layerViewport.get(1), layerViewport.get(2), layerViewport.get(3));
            GlStateManager._clearColor(layerClearColor.get(0), layerClearColor.get(1), layerClearColor.get(2), layerClearColor.get(3));
            if (depth) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            if (scissor) {
                GlStateManager._enableScissorTest();
            }
        }
    }

    public static boolean supports(Screen screen) {
        return screen != null && !(screen instanceof ReceivingLevelScreen) && !(screen instanceof ProgressScreen)
                && (!(screen instanceof ChatScreen) || chatInput(screen) != null);
    }

    private static EditBox chatInput(Screen screen) {
        if (screen instanceof ChatScreen) {
            for (var child : screen.children()) {
                if (child instanceof EditBox input) {
                    return input;
                }
            }
        }
        return null;
    }

    public static int chatCaret(Screen screen) {
        EditBox input = chatInput(screen);
        if (input == null) {
            return 0;
        }
        int width = bounds(screen).width();
        return Math.clamp(ChatPreview.line(input.getValue(), input.getCursorPosition(), width,
                Minecraft.getInstance().font::width).caretX() * 65535 / Math.max(1, width), 0, 65535);
    }

    private static Rect bounds(Screen screen) {
        EditBox input = chatInput(screen);
        if (input != null) {
            return CaptureGeometry.chatBounds(screen.width, screen.height);
        }
        return new Rect(0, 0, screen.width, screen.height);
    }

    public record Frame(int width, int height, byte[] rgba, int cropLeft, int cropTop,
            int cropWidth, int cropHeight, int layout) {}
    public record Size(int width, int height) {}

    public static Size outputSize(Screen screen) {
        if (!supports(screen) || screen.width <= 0 || screen.height <= 0) {
            return null;
        }
        if (!(screen instanceof ChatScreen)) {
            return new Size(256, 256);
        }
        Rect crop = bounds(screen);
        int left = crop.x0(), top = crop.y0(), right = crop.x1(), bottom = crop.y1();
        if (right <= left || bottom <= top) {
            return null;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        Rect source = CaptureGeometry.map(left, top, right, bottom, screen.width, screen.height,
                main.viewWidth, main.viewHeight);
        if (source.width() <= 0 || source.height() <= 0) {
            return null;
        }
        double scale = Math.min(1.0, 256.0 / Math.max(source.width(), source.height()));
        return new Size(Math.max(1, (int) Math.round(source.width() * scale)),
                Math.max(1, (int) Math.round(source.height() * scale)));
    }

    public Frame capture(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        RenderSystem.assertOnRenderThread();
        if (!supports(screen)) {
            return null;
        }
        Rect crop = bounds(screen);
        int left = crop.x0(), top = crop.y0(), right = crop.x1(), bottom = crop.y1();
        if (screen.width <= 0 || screen.height <= 0 || right <= left || bottom <= top) {
            return null;
        }

        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int fbWidth = main.viewWidth;
        int fbHeight = main.viewHeight;
        Rect source = CaptureGeometry.map(left, top, right, bottom,
                screen.width, screen.height, fbWidth, fbHeight);
        int sourceWidth = source.width();
        int sourceHeight = source.height();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return null;
        }
        Size size = outputSize(screen);
        if (size == null) {
            return null;
        }
        int width = size.width();
        int height = size.height();
        EditBox input = chatInput(screen);
        String text = input == null ? null : input.getValue();
        if (input != null && chatScreen == screen && text.equals(chatText) && input.getCursorPosition() == chatCursor && chatFrame != null &&
                chatFrame.width() == width && chatFrame.height() == height &&
                chatFrame.cropWidth() == crop.width() && chatFrame.cropHeight() == crop.height()) {
            return chatFrame;
        }

        graphics.flush();
        int readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        float[] color = RenderSystem.getShaderColor().clone();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        try {
            if (scene == null || scene.width != fbWidth || scene.height != fbHeight) {
                if (scene != null) {
                    scene.destroyBuffers();
                }
                scene = new TextureTarget(fbWidth, fbHeight, true, Minecraft.ON_OSX);
                scene.setClearColor(0, 0, 0, 0);
            }
            RenderSystem.disableScissor();
            scene.clear(Minecraft.ON_OSX);
            scene.bindWrite(true);
            captureTarget = scene;
            activeCapture = this;
            hasContentLayer = false;
            GuiGraphics isolated = new GuiGraphics(Minecraft.getInstance(), Minecraft.getInstance().renderBuffers().bufferSource());
            try {
                if (input != null) {
                    isolated.fill(left, top, right, bottom, 0xEB17191C);
                    isolated.fill(left + 2, top + 2, right - 2, bottom - 2, 0xEB30343A);
                    var font = Minecraft.getInstance().font;
                    var line = ChatPreview.line(text, input.getCursorPosition(), crop.width(), font::width);
                    isolated.enableScissor(left + 2, top + 2, right - 2, bottom - 2);
                    isolated.pose().pushPose();
                    isolated.pose().translate(left + ChatPreview.PADDING,
                            top + (crop.height() - font.lineHeight * ChatPreview.SCALE) / 2.0, 0);
                    isolated.pose().scale(ChatPreview.SCALE, ChatPreview.SCALE, 1);
                    isolated.drawString(font, line.text(), 0, 0, 0xFFF0F2F5, true);
                    isolated.pose().popPose();
                    isolated.disableScissor();
                } else {
                    screen.renderWithTooltip(isolated, mouseX, mouseY, partialTick);
                }
            } finally {
                try {
                    isolated.flush();
                } finally {
                    captureTarget = null;
                    activeCapture = null;
                }
            }
            RenderTarget capturedScene = hasContentLayer ? contentLayer : scene;
            if (!(screen instanceof ChatScreen)) {
                crop = hasContentLayer ? CaptureGeometry.centeredSquare(screen.width, screen.height)
                        : CaptureGeometry.guiBounds(screen.width, screen.height);
                left = crop.x0();
                top = crop.y0();
                right = crop.x1();
                bottom = crop.y1();
                source = CaptureGeometry.map(left, top, right, bottom, screen.width, screen.height, fbWidth, fbHeight);
            }
            if (target == null || target.width != width || target.height != height) {
                if (target != null) {
                    target.destroyBuffers();
                }
                if (outputPixels != null) {
                    outputPixels.close();
                }
                target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
                outputPixels = new NativeImage(width, height, false);
                target.setClearColor(0, 0, 0, 0);
            }
            RenderSystem.disableScissor();
            target.clear(Minecraft.ON_OSX);
            Rect destination = CaptureGeometry.destination(crop, screen.width, screen.height, width, height);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, capturedScene.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            GL30.glBlitFramebuffer(source.x0(), source.y0(), source.x1(), source.y1(),
                    destination.x0(), destination.y0(), destination.x1(), destination.y1(), GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            target.bindRead();
            outputPixels.downloadTexture(0, false);
            byte[] pixels = CaptureGeometry.rgba(width, height, outputPixels::getPixelRGBA);
            PreviewAppearance.compact(pixels);

            int layout = input != null ? WireMessages.CHAT : hasContentLayer ? WireMessages.FULLSCREEN : WireMessages.GUI;
            Frame frame = new Frame(width, height, pixels, left, top, right - left, bottom - top, layout);
            if (input != null) {
                chatScreen = screen;
                chatText = text;
                chatCursor = input.getCursorPosition();
                chatFrame = frame;
            }
            return frame;
        } finally {
            captureTarget = null;
            activeCapture = null;
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GlStateManager._bindTexture(texture);
            GlStateManager._viewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            if (depth) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            if (blend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            RenderSystem.depthMask(depthMask);
            if (cull) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
            if (scissor) {
                RenderSystem.enableScissor(scissorBox.get(0), scissorBox.get(1), scissorBox.get(2), scissorBox.get(3));
            } else {
                RenderSystem.disableScissor();
            }
        }
    }

    @Override
    public void close() {
        chatScreen = null;
        chatText = null;
        chatFrame = null;
        if (outputPixels != null) {
            outputPixels.close();
            outputPixels = null;
        }
        if (scene != null) {
            scene.destroyBuffers();
            scene = null;
        }
        if (contentLayer != null) {
            contentLayer.destroyBuffers();
            contentLayer = null;
        }
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
