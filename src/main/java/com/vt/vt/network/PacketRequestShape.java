package com.vt.vt.network;

import com.vt.vt.VTMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketRequestShape(BlockPos pos, int faceIdx, byte dx, byte dy, byte dz) implements CustomPacketPayload {
    public static final Type<PacketRequestShape> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VTMod.MODID, "request_shape"));

    public static final StreamCodec<ByteBuf, PacketRequestShape> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PacketRequestShape::pos,
            ByteBufCodecs.BYTE, p -> (byte) p.faceIdx(),
            ByteBufCodecs.BYTE, PacketRequestShape::dx,
            ByteBufCodecs.BYTE, PacketRequestShape::dy,
            ByteBufCodecs.BYTE, PacketRequestShape::dz,
            (pos, face, dx, dy, dz) -> new PacketRequestShape(pos, face, dx, dy, dz)
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}