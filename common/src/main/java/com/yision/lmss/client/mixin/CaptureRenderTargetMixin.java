package com.yision.lmss.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.yision.lmss.client.capture.CaptureController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class CaptureRenderTargetMixin {
    @Inject(method = "getMainRenderTarget", at = @At("RETURN"), cancellable = true)
    private void lmss$renderTarget(CallbackInfoReturnable<RenderTarget> ci) {
        RenderTarget target = CaptureController.renderTarget();
        if (target != null) {
            ci.setReturnValue(target);
        }
    }
}
