package com.yision.lmss.client.mixin;

import com.yision.lmss.client.capture.CaptureController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class CaptureMapMixin {
    @Inject(method = {"render", "method_25394"}, at = @At(value = "INVOKE",
            target = "Lxaero/map/graphics/ImprovedFramebuffer;unbindWrite()V"), require = 0, remap = false)
    private void lmss$mapContent(CallbackInfo ci) {
        CaptureController.captureContentLayer();
    }
}
