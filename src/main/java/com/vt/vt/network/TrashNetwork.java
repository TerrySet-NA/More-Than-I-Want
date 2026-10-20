package com.vt.vt.network;

import com.vt.vt.cleaner.CleanupManager;
import com.vt.vt.cleaner.SharedTrashInventory;
import com.vt.vt.cleaner.TrashRollbackMenu;
import com.vt.vt.client.ClientTrashNetworkHandler;
import com.vt.vt.config.ConfigCommon;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

public class TrashNetwork {

    public record SyncTrashPayload(int backupIndex, List<ItemStack> items) implements CustomPacketPayload {
        public static final Type<SyncTrashPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("vt", "sync_trash"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncTrashPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, SyncTrashPayload::backupIndex,
                ItemStack.OPTIONAL_LIST_STREAM_CODEC, SyncTrashPayload::items,
                SyncTrashPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(SyncTrashPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (FMLEnvironment.dist.isClient()) {
                    ClientTrashNetworkHandler.handleSync(payload);
                }
            });
        }
    }

    public record RequestTakeTrashPayload(int backupIndex, int itemIndex, boolean quickMove) implements CustomPacketPayload {
        public static final Type<RequestTakeTrashPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("vt", "request_take_trash"));
        public static final StreamCodec<ByteBuf, RequestTakeTrashPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, RequestTakeTrashPayload::backupIndex,
                ByteBufCodecs.INT, RequestTakeTrashPayload::itemIndex,
                ByteBufCodecs.BOOL, RequestTakeTrashPayload::quickMove,
                RequestTakeTrashPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(RequestTakeTrashPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)) {
                    return;
                }

                // 權限驗證：防止未授權客戶端繞過指令直接發送取物封包
                if (!ConfigCommon.CLEANER_PERMISSION.get().hasPermission(player.createCommandSourceStack())) {
                    return;
                }

                // 選單狀態驗證：必須開啟對應備份索引的回溯介面
                if (!(player.containerMenu instanceof TrashRollbackMenu menu) || menu.backupIndex != payload.backupIndex()) {
                    return;
                }

                SharedTrashInventory inv = CleanupManager.getOrCreateSharedInventory(player.server, payload.backupIndex());
                if (inv == null) {
                    return;
                }

                synchronized (inv) {
                    if (payload.itemIndex() < 0 || payload.itemIndex() >= inv.getContainerSize()) {
                        return;
                    }

                    ItemStack target = inv.getItem(payload.itemIndex());
                    if (target.isEmpty()) {
                        return;
                    }

                    if (payload.quickMove()) {
                        int countBefore = target.getCount();
                        player.getInventory().add(target);
                        if (target.isEmpty()) {
                            inv.setItem(payload.itemIndex(), ItemStack.EMPTY);
                        } else if (target.getCount() != countBefore) {
                            inv.setItem(payload.itemIndex(), target);
                        }
                    } else {
                        ItemStack carried = menu.getCarried();
                        if (carried.isEmpty()) {
                            menu.setCarried(target.copy());
                            inv.setItem(payload.itemIndex(), ItemStack.EMPTY);
                        } else if (ItemStack.isSameItemSameComponents(carried, target)) {
                            int space = carried.getMaxStackSize() - carried.getCount();
                            int takeCount = Math.min(space, target.getCount());
                            if (takeCount > 0) {
                                carried.grow(takeCount);
                                target.shrink(takeCount);
                                inv.setItem(payload.itemIndex(), target.isEmpty() ? ItemStack.EMPTY : target);
                            }
                        }
                    }

                    player.containerMenu.broadcastChanges();
                    inv.setChanged();
                    CleanupManager.broadcastInventoryUpdate(player.server, payload.backupIndex());
                }
            });
        }
    }

    /**
     * 🎯 一鍵穿回封包：通知伺服器處理該回溯編號的專屬物品穿回
     */
    public record RequestEquipTrashPayload(int backupIndex) implements CustomPacketPayload {
        public static final Type<RequestEquipTrashPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("vt", "request_equip_trash"));
        public static final StreamCodec<ByteBuf, RequestEquipTrashPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, RequestEquipTrashPayload::backupIndex,
                RequestEquipTrashPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(RequestEquipTrashPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)) {
                    return;
                }

                // 1. 權限驗證：與 RequestTakeTrashPayload 保持一致的防作弊驗證
                if (!ConfigCommon.CLEANER_PERMISSION.get().hasPermission(player.createCommandSourceStack())) {
                    return;
                }

                // 2. 選單狀態驗證：玩家必須正開啟此回溯視窗且 backupIndex 相符
                if (!(player.containerMenu instanceof TrashRollbackMenu menu) || menu.backupIndex != payload.backupIndex()) {
                    return;
                }

                // 3. 呼叫服務端執行全拋出 + 精準穿回 + 清單更新
                CleanupManager.executeEquipAndRestore(player.server, player, payload.backupIndex());
            });
        }
    }
}