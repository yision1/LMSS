package com.yision.lmss.neoforge;

import com.yision.lmss.LMSS;
import com.yision.lmss.client.render.RemotePanelRenderer;
import com.yision.lmss.client.stream.ClientNetworkState;
import com.yision.lmss.config.SharingSettings;
import com.yision.lmss.protocol.GuiWirePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@Mod(value = LMSS.MOD_ID, dist = Dist.CLIENT)
public final class LMSSNeoForgeClient {
    public LMSSNeoForgeClient(IEventBus modBus) {
        ClientNetworkState.initialize(SharingSettings.load(FMLPaths.CONFIGDIR.get()),
                PacketDistributor::sendToServer,
                () -> Minecraft.getInstance().getConnection() != null &&
                NetworkRegistry.hasChannel(Minecraft.getInstance().getConnection(), GuiWirePayload.Serverbound.TYPE.id()));
        NeoForge.EVENT_BUS.addListener(this::onRenderScreen);
        NeoForge.EVENT_BUS.addListener(this::onRenderWorld);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onDisconnect);
    }

    private void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientNetworkState.disconnected();
    }

    private void onRenderScreen(ScreenEvent.Render.Post event) {
        ClientNetworkState.render(event.getScreen(), event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    private void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            event.getPoseStack().pushPose();
            event.getPoseStack().mulPose(event.getModelViewMatrix());
            try {
                RemotePanelRenderer.render(event.getPoseStack(), event.getCamera(),
                        Minecraft.getInstance().renderBuffers().bufferSource(), event.getPartialTick().getGameTimeDeltaPartialTick(false),
                        event.getProjectionMatrix());
            } finally {
                event.getPoseStack().popPose();
            }
        }
    }

    private void onTick(ClientTickEvent.Post event) {
        ClientNetworkState.tick();
    }
}
