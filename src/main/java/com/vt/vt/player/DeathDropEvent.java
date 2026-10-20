package com.vt.vt.player;

import com.vt.vt.VTMod;
import com.vt.vt.config.ConfigCommon;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

@EventBusSubscriber(modid = VTMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class DeathDropEvent {

    public static final String TAG_OWNER = "VT_DeathOwner";
    public static final String TAG_TYPE = "VT_SlotType";
    public static final String TAG_SLOT = "VT_SlotIndex";
    public static final String TAG_EXTRA = "VT_SlotExtra";
    public static final String TAG_FAILED_ONCE = "VT_FailedOnce";

    private static final EquipmentSlot[] ARMOR_SLOTS = new EquipmentSlot[] {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    /**
     * 1. 玩家死亡瞬間：在原版拋出掉落物前，標記原始欄位資訊（原版背包、裝備、副手、Curios）
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ConfigCommon.PREVENT_DEATH_DROP_SCATTER.get()) {
            return;
        }

        // 開啟 keepInventory 規則時不注入標籤
        if (player.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            return;
        }

        // 主背包與快捷列 (0 ~ 35)
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            tagStack(player.getInventory().items.get(i), player, "main", i, null);
        }

        // 盔甲欄 (0: 腳, 1: 腿, 2: 胸, 3: 頭)
        for (int i = 0; i < player.getInventory().armor.size(); i++) {
            tagStack(player.getInventory().armor.get(i), player, "armor", i, null);
        }

        // 副手 (0)
        for (int i = 0; i < player.getInventory().offhand.size(); i++) {
            tagStack(player.getInventory().offhand.get(i), player, "offhand", i, null);
        }

        // Curios 飾品欄
        CuriosCompat.tagCuriosSlots(player);
    }

    /**
     * 2. 死亡掉落物生成：取消擴散動能，集中於原地腳下
     */
    @SubscribeEvent
    public static void onPlayerDeathDrops(LivingDropsEvent event) {
        if (event.isCanceled()) {
            return;
        }

        if (event.getEntity() instanceof ServerPlayer player && ConfigCommon.PREVENT_DEATH_DROP_SCATTER.get()) {
            for (ItemEntity drop : event.getDrops()) {
                drop.setDeltaMovement(0, 0, 0); // 消除噴射動能
                drop.setPos(player.getX(), player.getY() + 0.1, player.getZ()); // 集中於原地
            }
        }
    }

    /**
     * 3. 拾取事件（兩段式 / 二次脫落）：
     * - 無資料：放行原版正常拾取
     * - 有資料且原位可用：歸位至原欄位
     * - 第一次歸位失敗：保留資料，打上 VT_FailedOnce 並重設 1.5 秒拾取冷卻，等待擴展飾品欄位就位
     * - 第二次歸位失敗：徹底清空所有標籤資料，轉為普通掉落物放行
     */
    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        ItemEntity drop = event.getItemEntity();
        ItemStack stack = drop.getItem();

        // 偵測是否有資料：無資料表示為普通物品，直接放行給原版正常拾取
        if (stack.isEmpty() || !stack.has(DataComponents.CUSTOM_DATA)) {
            return;
        }

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return;
        }

        CompoundTag tag = customData.copyTag();
        if (!tag.contains(TAG_OWNER)) {
            return; // 無 VT 死亡標籤，正常拾取
        }

        String ownerUUID = tag.getString(TAG_OWNER);
        // 若被其他玩家碰到，直接清除所有資料並重設 1 秒拾取冷卻
        if (!ownerUUID.equals(player.getStringUUID())) {
            cleanStackTag(stack);
            drop.setItem(stack);
            drop.setPickUpDelay(20);
            event.setCanPickup(TriState.FALSE);
            return;
        }

        String type = tag.getString(TAG_TYPE);
        int slot = tag.getInt(TAG_SLOT);
        String extra = tag.contains(TAG_EXTRA) ? tag.getString(TAG_EXTRA) : null;

        ItemStack restoreStack = stack.copy();
        cleanStackTag(restoreStack); // 預備放入背包的乾淨副本

        int initialCount = restoreStack.getCount();

        // A. Curios 飾品原位還原
        if ("curios".equals(type) && extra != null) {
            CuriosCompat.restoreCurio(player, extra, slot, restoreStack);
        }
        // B. 盔甲欄原位還原
        else if ("armor".equals(type)) {
            if (slot >= 0 && slot < ARMOR_SLOTS.length) {
                EquipmentSlot eqSlot = ARMOR_SLOTS[slot];
                if (player.getItemBySlot(eqSlot).isEmpty()) {
                    player.setItemSlot(eqSlot, restoreStack.copy());
                    restoreStack.setCount(0);
                }
            }
        }
        // C. 副手欄原位還原
        else if ("offhand".equals(type)) {
            if (player.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
                player.setItemSlot(EquipmentSlot.OFFHAND, restoreStack.copy());
                restoreStack.setCount(0);
            }
        }
        // D. 主背包 / 快捷列原位還原 (0 ~ 35)
        else if ("main".equals(type)) {
            if (slot >= 0 && slot < player.getInventory().items.size()) {
                ItemStack target = player.getInventory().items.get(slot);
                if (target.isEmpty()) {
                    player.getInventory().items.set(slot, restoreStack.copy());
                    restoreStack.setCount(0);
                } else if (ItemStack.isSameItemSameComponents(target, restoreStack)
                        && target.getCount() < target.getMaxStackSize()) {
                    int space = target.getMaxStackSize() - target.getCount();
                    int toAdd = Math.min(space, restoreStack.getCount());
                    target.grow(toAdd);
                    restoreStack.shrink(toAdd);
                }
            }
        }

        int placedCount = initialCount - restoreStack.getCount();

        if (placedCount > 0) {
            // 情況一：成功歸位（部分或全部）
            player.take(drop, placedCount);
            player.containerMenu.broadcastChanges();

            if (restoreStack.isEmpty()) {
                drop.discard(); // 全部歸位，消除掉落物實體
            } else {
                // 原位置已滿，剩餘部分：清除標籤並設置冷卻，留於地面暫不拾取
                cleanStackTag(restoreStack);
                drop.setItem(restoreStack);
                drop.setPickUpDelay(30);
            }
            event.setCanPickup(TriState.FALSE);
        } else {
            // 情況二：原位置暫時無法歸位（被佔用，或提供額外格數的飾品尚未就位）
            boolean failedOnce = tag.getBoolean(TAG_FAILED_ONCE);

            if (!failedOnce) {
                // 第一次嘗試失敗：完整保留所有槽位資料，僅標記 FailedOnce，設置 1.5 秒冷卻
                CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> t.putBoolean(TAG_FAILED_ONCE, true));
                drop.setItem(stack);
                drop.setPickUpDelay(30);
                event.setCanPickup(TriState.FALSE);
            } else {
                // 第二次嘗試失敗：清空所有自訂標籤資料，短暫冷卻後允許原版常規拾取
                cleanStackTag(stack);
                drop.setItem(stack);
                drop.setPickUpDelay(10);
                event.setCanPickup(TriState.FALSE);
            }
        }
    }

    /**
     * 4. 重生清理防護：防止某些防掉落機制（如靈魂綁定）導致身上殘留內部標記
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            for (ItemStack s : player.getInventory().items) cleanStackTag(s);
            for (ItemStack s : player.getInventory().armor) cleanStackTag(s);
            for (ItemStack s : player.getInventory().offhand) cleanStackTag(s);
            CuriosCompat.cleanCuriosInventory(player);
        }
    }

    // --- 輔助方法 ---

    private static void tagStack(ItemStack stack, ServerPlayer player, String type, int slot, String extra) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(TAG_OWNER, player.getStringUUID());
            tag.putString(TAG_TYPE, type);
            tag.putInt(TAG_SLOT, slot);
            if (extra != null) {
                tag.putString(TAG_EXTRA, extra);
            }
        });
    }

    /**
     * 🎯 公開方法：供本類別、InventoryCleaner 以及 CleanupManager 一同共用
     */
    public static void cleanStackTag(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.has(DataComponents.CUSTOM_DATA)) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.remove(TAG_OWNER);
            tag.remove(TAG_TYPE);
            tag.remove(TAG_SLOT);
            tag.remove(TAG_EXTRA);
            tag.remove(TAG_FAILED_ONCE);
        });
    }

    // ==========================================
    // 🎯 Curios 模組相容層（穩定的常規檢查與填入）
    // ==========================================
    public static class CuriosCompat {
        private static final boolean IS_LOADED = ModList.get().isLoaded("curios");

        public static void tagCuriosSlots(ServerPlayer player) {
            if (!IS_LOADED) return;
            try {
                Class<?> apiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
                Method getInv = apiClass.getMethod("getCuriosInventory", LivingEntity.class);
                Optional<?> opt = (Optional<?>) getInv.invoke(null, player);

                if (opt.isPresent()) {
                    Object handler = opt.get();
                    Method getCurios = handler.getClass().getMethod("getCurios");
                    Map<?, ?> curiosMap = (Map<?, ?>) getCurios.invoke(handler);

                    for (Map.Entry<?, ?> entry : curiosMap.entrySet()) {
                        String identifier = (String) entry.getKey();
                        Object stacksHandler = entry.getValue();
                        Method getStacks = stacksHandler.getClass().getMethod("getStacks");
                        Object dynamicStacks = getStacks.invoke(stacksHandler);

                        Method getSlots = dynamicStacks.getClass().getMethod("getSlots");
                        int slots = (Integer) getSlots.invoke(dynamicStacks);

                        Method getStackInSlot = dynamicStacks.getClass().getMethod("getStackInSlot", int.class);
                        for (int i = 0; i < slots; i++) {
                            ItemStack curioStack = (ItemStack) getStackInSlot.invoke(dynamicStacks, i);
                            if (!curioStack.isEmpty()) {
                                tagStack(curioStack, player, "curios", i, identifier);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        public static boolean restoreCurio(ServerPlayer player, String identifier, int slotIndex, ItemStack stackToRestore) {
            if (!IS_LOADED) return false;
            try {
                Class<?> apiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
                Method getInv = apiClass.getMethod("getCuriosInventory", LivingEntity.class);
                Optional<?> opt = (Optional<?>) getInv.invoke(null, player);

                if (opt.isPresent()) {
                    Object handler = opt.get();
                    Method getCurios = handler.getClass().getMethod("getCurios");
                    Map<?, ?> curiosMap = (Map<?, ?>) getCurios.invoke(handler);

                    Object stacksHandler = curiosMap.get(identifier);
                    if (stacksHandler != null) {
                        Method getStacks = stacksHandler.getClass().getMethod("getStacks");
                        Object dynamicStacks = getStacks.invoke(stacksHandler);

                        Method getSlots = dynamicStacks.getClass().getMethod("getSlots");
                        int slots = (Integer) getSlots.invoke(dynamicStacks);

                        // 槽位存在且目標為空：精準還原至原本槽位
                        if (slotIndex >= 0 && slotIndex < slots) {
                            Method getStackInSlot = dynamicStacks.getClass().getMethod("getStackInSlot", int.class);
                            ItemStack current = (ItemStack) getStackInSlot.invoke(dynamicStacks, slotIndex);

                            if (current.isEmpty()) {
                                Method setStackInSlot = dynamicStacks.getClass().getMethod("setStackInSlot", int.class, ItemStack.class);
                                setStackInSlot.invoke(dynamicStacks, slotIndex, stackToRestore.copy());
                                stackToRestore.setCount(0);
                                return true;
                            }
                        } else {
                            // 若原 slotIndex 超出當前上限，但該類別有其餘空格，容錯放入第一個可用空格
                            Method getStackInSlot = dynamicStacks.getClass().getMethod("getStackInSlot", int.class);
                            for (int i = 0; i < slots; i++) {
                                ItemStack current = (ItemStack) getStackInSlot.invoke(dynamicStacks, i);
                                if (current.isEmpty()) {
                                    Method setStackInSlot = dynamicStacks.getClass().getMethod("setStackInSlot", int.class, ItemStack.class);
                                    setStackInSlot.invoke(dynamicStacks, i, stackToRestore.copy());
                                    stackToRestore.setCount(0);
                                    return true;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return false;
        }

        public static void cleanCuriosInventory(ServerPlayer player) {
            if (!IS_LOADED) return;
            try {
                Class<?> apiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
                Method getInv = apiClass.getMethod("getCuriosInventory", LivingEntity.class);
                Optional<?> opt = (Optional<?>) getInv.invoke(null, player);

                if (opt.isPresent()) {
                    Object handler = opt.get();
                    Method getCurios = handler.getClass().getMethod("getCurios");
                    Map<?, ?> curiosMap = (Map<?, ?>) getCurios.invoke(handler);

                    for (Object stacksHandler : curiosMap.values()) {
                        Method getStacks = stacksHandler.getClass().getMethod("getStacks");
                        Object dynamicStacks = getStacks.invoke(stacksHandler);
                        Method getSlots = dynamicStacks.getClass().getMethod("getSlots");
                        int slots = (Integer) getSlots.invoke(dynamicStacks);
                        Method getStackInSlot = dynamicStacks.getClass().getMethod("getStackInSlot", int.class);
                        for (int i = 0; i < slots; i++) {
                            ItemStack s = (ItemStack) getStackInSlot.invoke(dynamicStacks, i);
                            cleanStackTag(s);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }
}