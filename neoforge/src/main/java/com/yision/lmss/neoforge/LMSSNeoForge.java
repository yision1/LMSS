package com.yision.lmss.neoforge;

import com.yision.lmss.LMSS;
import com.yision.lmss.client.stream.ClientNetworkState;
import com.yision.lmss.config.SharingSettings;
import com.yision.lmss.protocol.GuiWirePayload;
import com.yision.lmss.server.GuiRelay;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@Mod(LMSS.MOD_ID)
public final class LMSSNeoForge {
    private final GuiRelay relay;

    public LMSSNeoForge(IEventBus modBus) {
        var configDir = FMLPaths.CONFIGDIR.get();
        var settings = SharingSettings.load(configDir);
        relay = new GuiRelay(PacketDistributor::sendToPlayer, settings);

        modBus.addListener(this::onRegisterPayload);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoined);
        NeoForge.EVENT_BUS.addListener(this::onPlayerDisconnected);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private void onRegisterPayload(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(LMSS.MOD_ID).optional();
        registrar.playToServer(GuiWirePayload.Serverbound.TYPE,
                GuiWirePayload.Serverbound.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        relay.receive((ServerPlayer) context.player(), payload.data())));
        registrar.playToClient(GuiWirePayload.Clientbound.TYPE, GuiWirePayload.Clientbound.CODEC,
                (payload, context) -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) {
                        ClientOnly.receive(payload);
                    }
                });
    }

    private static final class ClientOnly {
        static void receive(GuiWirePayload.Clientbound payload) {
            ClientNetworkState.receive(payload);
        }
    }

    private void onPlayerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player &&
                NetworkRegistry.hasChannel(player.connection, GuiWirePayload.Clientbound.TYPE.id())) {
            relay.joined(player);
        }
    }

    private void onPlayerDisconnected(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            relay.disconnected(player);
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        relay.tick(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        relay.clear();
    }
}
