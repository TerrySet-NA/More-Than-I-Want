package com.vt.vt.cleaner;

import com.vt.vt.VTMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class TrashRollbackMenu extends AbstractContainerMenu {
    public final int backupIndex;
    public List<ItemStack> clientTrashItems = new ArrayList<>();

    public TrashRollbackMenu(int containerId, Inventory playerInv, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInv, extraData.readInt());
    }

    public TrashRollbackMenu(int containerId, Inventory playerInv, int backupIndex) {
        super(VTMod.TRASH_ROLLBACK_MENU.get(), containerId);
        this.backupIndex = backupIndex;

        // 原版 generic_54 (6 行箱子) 玩家背包標準起始 Y 為 139
        int playerInvY = 139;

        // 玩家背包 (3x9 = 27 格，起始 X 為 8)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, playerInvY + row * 18));
            }
        }

        // 快捷列 (1x9 = 9 格，起始 X 為 8，Y 為 197)
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, playerInvY + 58));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }
}