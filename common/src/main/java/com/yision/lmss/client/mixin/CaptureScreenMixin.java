package com.yision.lmss.client.mixin;

import com.yision.lmss.client.capture.CaptureController;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class CaptureScreenMixin {
    @Inject(method = {"renderBackground", "renderTransparentBackground", "renderBlurredBackground",
        "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;)V",
        "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;IIII)V"}, at = @At("HEAD"), cancellable = true)
    private void lmss$background(CallbackInfo ci) {
        if (CaptureController.isCapturing()) {
            ci.cancel();
        }
    }
}
