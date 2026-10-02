package com.yision.lmss.client.mixin;

import com.yision.lmss.client.capture.CaptureController;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class CaptureContainerMixin {
    @Inject(method = "renderFloatingItem", at = @At("HEAD"), cancellable = true)
    private void lmss$floatingItem(CallbackInfo ci) {
        if (CaptureController.isCapturing()) {
            ci.cancel();
        }
    }
}
