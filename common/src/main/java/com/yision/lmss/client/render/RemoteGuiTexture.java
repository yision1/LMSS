package com.yision.lmss.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.yision.lmss.LMSS;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

public final class RemoteGuiTexture implements AutoCloseable {
    private final ResourceLocation location;
    private final RenderType renderType;
    private DynamicTexture texture;
    private int width;
    private int height;

    public RemoteGuiTexture(UUID owner) {
        location = ResourceLocation.fromNamespaceAndPath(LMSS.MOD_ID, "remote/" + owner);
        renderType = PreviewRenderType.create(location);
    }

    public RenderType renderType() {
        return renderType;
    }

    public void update(int newWidth, int newHeight, byte[] pixels) {
        if (texture == null || width != newWidth || height != newHeight) {
            replaceTexture(new DynamicTexture(newWidth, newHeight, false), newWidth, newHeight);
        }
        fill(texture.getPixels(), pixels);
        texture.upload();
        configureWrap();
    }

    public static NativeImage prepare(int width, int height, byte[] pixels) {
        NativeImage image = new NativeImage(width, height, false);
        try {
            fill(image, pixels);
            return image;
        } catch (RuntimeException | Error bad) {
            image.close();
            throw bad;
        }
    }

    private static void fill(NativeImage image, byte[] pixels) {
        for (int y = 0, i = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int red = pixels[i++] & 255, green = pixels[i++] & 255;
                int blue = pixels[i++] & 255, alpha = pixels[i++] & 255;
                image.setPixelRGBA(x, y, red | green << 8 | blue << 16 | alpha << 24);
            }
        }
    }

    public void update(NativeImage image) {
        int newWidth = image.getWidth(), newHeight = image.getHeight();
        if (texture == null || width != newWidth || height != newHeight) {
            replaceTexture(new DynamicTexture(image), newWidth, newHeight);
        } else {
            texture.setPixels(image);
            texture.upload();
        }
        configureWrap();
    }

    private void replaceTexture(DynamicTexture next, int newWidth, int newHeight) {
        close();
        width = newWidth;
        height = newHeight;
        texture = next;
        Minecraft.getInstance().getTextureManager().register(location, texture);
    }

    private void configureWrap() {
        texture.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,
                GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,
                GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean ready() {
        return texture != null;
    }

    @Override
    public void close() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(location);
            texture = null;
            width = height = 0;
        }
    }
}
