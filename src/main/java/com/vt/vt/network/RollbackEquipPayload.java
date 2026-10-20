package com.vt.vt.network;

import com.vt.vt.VTMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RollbackEquipPayload(int containerId, int backupIndex) implements CustomPacketPayload {
    public static final Type<RollbackEquipPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VTMod.MODID, "rollback_equip"));
    public static final StreamCodec<ByteBuf, RollbackEquipPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RollbackEquipPayload::containerId,
            ByteBufCodecs.VAR_INT, RollbackEquipPayload::backupIndex,
            RollbackEquipPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}