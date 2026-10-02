package com.yision.lmss.protocol;

import com.yision.lmss.LMSS;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public final class GuiWirePayload {
    private GuiWirePayload() {}

    public record Serverbound(byte[] data) implements CustomPacketPayload {
        public static final Type<Serverbound> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(LMSS.MOD_ID, "serverbound"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Serverbound> CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeByteArray(payload.data),
                buf -> new Serverbound(buf.readByteArray(WireMessages.MAX_BYTES + 4)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Clientbound(int stream, UUID publisher, UUID session, byte[] data) implements CustomPacketPayload {
        public Clientbound(UUID publisher, byte[] data) {
            this(0, publisher, new UUID(0, 0), data);
        }
        public static final Type<Clientbound> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(LMSS.MOD_ID, "clientbound"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Clientbound> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt((payload.stream << 1) | (payload.publisher == null ? 0 : 1));
                    if (payload.publisher != null) {
                        buf.writeUUID(payload.publisher);
                        buf.writeUUID(payload.session);
                    }
                    buf.writeByteArray(payload.data);
                },
                buf -> {
                    int tag = buf.readVarInt();
                    if (tag < 0 || (tag >>> 1) > 0x0fffffff) {
                        throw new IllegalArgumentException("Invalid stream tag");
                    }
                    UUID owner = (tag & 1) != 0 ? buf.readUUID() : null;
                    UUID session = owner == null ? null : buf.readUUID();
                    return new Clientbound(tag >>> 1, owner, session, buf.readByteArray(WireMessages.MAX_BYTES));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
