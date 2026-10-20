package com.vt.vt.network;

import com.vt.vt.config.ConfigCommon;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestSyncPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestSyncPayload> TYPE = 
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("vt", "request_sync"));

    public static final StreamCodec<ByteBuf, RequestSyncPayload> STREAM_CODEC = StreamCodec.unit(new RequestSyncPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { 
        return TYPE; 
    }

    public static void handle(RequestSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 同步功能關閉時不處理
            if (!ConfigCommon.ENABLE_CONFIG_SYNC.get()) {
                return;
            }

            if (context.player() instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, SyncCommonConfigValuesPayload.createFromServer());
            }
        });
    }
}