package com.vt.vt.network;

import com.vt.vt.VTMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record PacketSpawnParticles(List<BlockPos> blocks, int stateId) implements CustomPacketPayload {
    public static final Type<PacketSpawnParticles> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VTMod.MODID, "spawn_particles"));

    public static final StreamCodec<ByteBuf, PacketSpawnParticles> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, BlockPos.STREAM_CODEC, 2048), PacketSpawnParticles::blocks,
            ByteBufCodecs.VAR_INT, PacketSpawnParticles::stateId,
            PacketSpawnParticles::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}