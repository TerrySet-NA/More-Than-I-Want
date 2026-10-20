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

public record PacketSyncShape(List<BlockPos> blocks) implements CustomPacketPayload {
    public static final Type<PacketSyncShape> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VTMod.MODID, "sync_shape"));

    // 限制傳輸上限為 2048，防止惡意超大資料包攻擊
    public static final StreamCodec<ByteBuf, PacketSyncShape> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, BlockPos.STREAM_CODEC, 2048), PacketSyncShape::blocks,
            PacketSyncShape::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}