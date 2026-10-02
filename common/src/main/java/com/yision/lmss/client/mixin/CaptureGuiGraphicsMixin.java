package com.yision.lmss.client.mixin;

import com.yision.lmss.client.capture.CaptureController;
import com.yision.lmss.client.capture.CaptureGeometry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphics.class)
public abstract class CaptureGuiGraphicsMixin {
    @Inject(method = "renderTooltipInternal", at = @At("HEAD"), cancellable = true)
    private void lmss$tooltip(CallbackInfo ci) {
        if (CaptureController.isCapturing()) {
            ci.cancel();
        }
    }

    @Inject(method = "fillGradient(Lnet/minecraft/client/renderer/RenderType;IIIIIII)V",
            at = @At("HEAD"), cancellable = true)
    private void lmss$backgroundGradient(RenderType type, int left, int top, int right, int bottom,
            int topColor, int bottomColor, int z, CallbackInfo ci) {
        if (!CaptureController.isCapturing() || GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
            return;
        }
        GuiGraphics graphics = (GuiGraphics) (Object) this;
        if (CaptureGeometry.isFullscreenGradient(graphics.pose().last().pose(), left, top, right, bottom, z,
                graphics.guiWidth(), graphics.guiHeight())) {
            ci.cancel();
        }
    }
}
