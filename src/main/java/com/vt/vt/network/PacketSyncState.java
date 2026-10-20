package com.vt.vt.network;

import com.vt.vt.VTMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketSyncState(boolean isActive, int mode, boolean preventToolBreak, int clientMaxCount) implements CustomPacketPayload {
    public static final Type<PacketSyncState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VTMod.MODID, "sync_state"));

    public static final StreamCodec<ByteBuf, PacketSyncState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PacketSyncState::isActive,
            ByteBufCodecs.VAR_INT, PacketSyncState::mode,
            ByteBufCodecs.BOOL, PacketSyncState::preventToolBreak,
            ByteBufCodecs.VAR_INT, PacketSyncState::clientMaxCount,
            PacketSyncState::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}