package com.vt.vt.network;

import com.vt.vt.VTMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CleanNbtPayload() implements CustomPacketPayload {
    public static final Type<CleanNbtPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VTMod.MODID, "clean_nbt"));
    public static final StreamCodec<ByteBuf, CleanNbtPayload> STREAM_CODEC = StreamCodec.unit(new CleanNbtPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}