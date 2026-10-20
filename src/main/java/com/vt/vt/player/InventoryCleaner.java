package com.vt.vt.player;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public class InventoryCleaner {

    public static void cleanAndRestack(ServerPlayer player) {
        Inventory inv = player.getInventory();
        int cleanedCount = 0;

        // 1. 直接遍歷並呼叫 DeathDropEvent.cleanStackTag
        for (int i = 0; i < inv.items.size(); i++) {
            if (tryClean(inv.items.get(i))) cleanedCount++;
        }
        for (int i = 0; i < inv.armor.size(); i++) {
            if (tryClean(inv.armor.get(i))) cleanedCount++;
        }
        for (int i = 0; i < inv.offhand.size(); i++) {
            if (tryClean(inv.offhand.get(i))) cleanedCount++;
        }

        // 2. 僅對主背包進行就地重新堆疊合併
        compactInventory(inv);

        player.containerMenu.broadcastChanges();

        player.displayClientMessage(
                Component.translatable("vt.message.nbt_cleaned", cleanedCount), 
                true
        );
    }

    /**
     * 🎯 直接委託給 DeathDropEvent.cleanStackTag 進行標籤清除
     */
    private static boolean tryClean(ItemStack stack) {
        if (stack.isEmpty() || !stack.has(DataComponents.CUSTOM_DATA)) {
            return false;
        }

        CustomData before = stack.get(DataComponents.CUSTOM_DATA);
        DeathDropEvent.cleanStackTag(stack);
        CustomData after = stack.get(DataComponents.CUSTOM_DATA);

        // 如果 CustomData 被完全移除或內容變更，代表成功洗掉 VT 標籤
        return before != null && (after == null || !before.equals(after));
    }

    /**
     * 合併同種類物品
     */
    private static void compactInventory(Inventory inv) {
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack base = inv.items.get(i);
            if (base.isEmpty() || base.getCount() >= base.getMaxStackSize()) continue;

            for (int j = i + 1; j < inv.items.size(); j++) {
                ItemStack target = inv.items.get(j);
                if (target.isEmpty()) continue;

                if (ItemStack.isSameItemSameComponents(base, target)) {
                    int space = base.getMaxStackSize() - base.getCount();
                    int toAdd = Math.min(space, target.getCount());

                    base.grow(toAdd);
                    target.shrink(toAdd);

                    if (target.isEmpty()) {
                        inv.items.set(j, ItemStack.EMPTY);
                    }
                    if (base.getCount() >= base.getMaxStackSize()) {
                        break;
                    }
                }
            }
        }
    }
}