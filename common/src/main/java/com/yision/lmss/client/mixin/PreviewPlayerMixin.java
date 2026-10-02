package com.yision.lmss.client.mixin;

import com.yision.lmss.client.render.ChatPreview;
import com.yision.lmss.client.stream.ClientNetworkState;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
public abstract class PreviewPlayerMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void lmss$gesture(LivingEntity player, float walk, float speed, float age,
            float yaw, float pitch, CallbackInfo ci) {
        if (player.isUsingItem() || player.isFallFlying() || player.isSwimming() || player.swinging) {
            return;
        }
        var gesture = ClientNetworkState.gesture(player.getUUID());
        if (gesture == null) {
            return;
        }
        PlayerModel<?> model = (PlayerModel<?>) (Object) this;
        boolean right = player.getMainArm() == HumanoidArm.RIGHT;
        var hand = right ? model.rightArm : model.leftArm;
        var support = right ? model.leftArm : model.rightArm;
        float weight = gesture.weight() * (1 - Math.clamp(speed * 2, 0, 0.8f));
        float horizontal = (float) (gesture.u() - 0.5);
        float vertical = (float) (gesture.v() - 0.5);
        float breath = Mth.sin(age * 0.09f) * 0.018f;
        float chat = gesture.chatMix();
        float stroke = ChatPreview.typingStroke(age, gesture.typingElapsed());
        float typing = (stroke + breath) * (right ? 1 : -1);
        hand.xRot = Mth.lerp(weight, hand.xRot, Mth.lerp(chat,
                -1.20f + vertical * 0.65f - gesture.press() * 0.16f + breath, -1.12f + typing));
        hand.yRot = Mth.lerp(weight, hand.yRot, Mth.lerp(chat,
                horizontal * 0.85f + (right ? -0.10f : 0.10f), right ? -0.22f : 0.22f));
        hand.zRot = Mth.lerp(weight, hand.zRot, Mth.lerp(chat, right ? 0.06f : -0.06f, right ? 0.04f : -0.04f));
        support.xRot = Mth.lerp(weight, support.xRot, Mth.lerp(chat, -1.0f + vertical * 0.12f - breath, -1.12f - typing));
        support.yRot = Mth.lerp(weight, support.yRot, right ? 0.22f : -0.22f);
        support.zRot = Mth.lerp(weight, support.zRot, Mth.lerp(chat, right ? -0.10f : 0.10f, right ? -0.04f : 0.04f));
        model.head.xRot = Mth.lerp(weight * 0.65f, model.head.xRot, Mth.lerp(chat, 0.22f + vertical * 0.24f, 0.22f));
        model.head.yRot = Mth.lerp(weight * 0.65f, model.head.yRot, horizontal * 0.38f * (1 - chat));
        model.hat.copyFrom(model.head);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
    }
}
