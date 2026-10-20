package com.vt.vt.client;

import com.vt.vt.cleaner.TrashRollbackMenu;
import com.vt.vt.network.TrashNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class TrashRollbackScreen extends AbstractContainerScreen<TrashRollbackMenu> {
    private static final ResourceLocation CONTAINER_TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    private List<ItemStack> trashItems = new ArrayList<>();
    private float scrollOffs = 0.0f;
    private boolean isScrolling = false;

    public TrashRollbackScreen(TrashRollbackMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
        this.inventoryLabelY = 126;
    }

    @Override
    protected void init() {
        super.init();
        if (!this.menu.clientTrashItems.isEmpty()) {
            this.trashItems = new ArrayList<>(this.menu.clientTrashItems);
        }

        // 🎯 新增 [一鍵穿回] 按鈕（緊湊放置於標題列右側，不遮擋格子）
        int btnWidth = 58;
        int btnHeight = 12;
        int btnX = this.leftPos + 110;
        int btnY = this.topPos + 4;

        Button equipBtn = Button.builder(
                Component.translatableWithFallback("vt.gui.rollback.equip_all", "§a一鍵穿回"),
                btn -> PacketDistributor.sendToServer(new TrashNetwork.RequestEquipTrashPayload(this.menu.backupIndex))
        )
        .bounds(btnX, btnY, btnWidth, btnHeight)
        .tooltip(Tooltip.create(Component.translatableWithFallback("vt.gui.rollback.equip_all.tooltip", "將背包現有物品全部拋出，並一鍵穿回/歸位屬於自己的死亡掉落物")))
        .build();

        this.addRenderableWidget(equipBtn);
    }

    public void updateTrashItems(List<ItemStack> newItems) {
        this.trashItems = new ArrayList<>(newItems);
        if (getMaxRows() == 0) {
            this.scrollOffs = 0.0F;
        } else {
            this.scrollOffs = Mth.clamp(this.scrollOffs, 0.0F, 1.0F);
        }
    }

    private int getMaxRows() {
        return Math.max(0, Mth.positiveCeilDiv(this.trashItems.size(), 9) - 6);
    }

    private int getRowOffset() {
        return (int) ((double) (this.scrollOffs * (float) getMaxRows()) + 0.5D);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxRows = getMaxRows();
        if (maxRows > 0) {
            this.scrollOffs = Mth.clamp((float) (this.scrollOffs - (scrollY / (double) maxRows)), 0.0F, 1.0F);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int startX = this.leftPos + 8;
        int startY = this.topPos + 18;

        // 點擊虛擬格子區域
        if (mouseX >= startX && mouseX < startX + 9 * 18 && mouseY >= startY && mouseY < startY + 6 * 18) {
            int col = ((int) mouseX - startX) / 18;
            int row = ((int) mouseY - startY) / 18;
            int actualIndex = (getRowOffset() + row) * 9 + col;

            if (actualIndex >= 0 && actualIndex < this.trashItems.size() && !this.trashItems.get(actualIndex).isEmpty()) {
                boolean isShift = hasShiftDown();
                PacketDistributor.sendToServer(new TrashNetwork.RequestTakeTrashPayload(this.menu.backupIndex, actualIndex, isShift));
                return true;
            }
        }

        // 滾動條滑塊點擊
        int barX = this.leftPos + 175;
        int barY = this.topPos + 18;
        if (mouseX >= barX && mouseX <= barX + 6 && mouseY >= barY && mouseY <= barY + 107) {
            this.isScrolling = true;
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.isScrolling = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.isScrolling && getMaxRows() > 0) {
            int barY = this.topPos + 18;
            this.scrollOffs = Mth.clamp(((float) mouseY - (float) barY - 7.5F) / 92.0F, 0.0F, 1.0F);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        guiGraphics.blit(CONTAINER_TEXTURE, x, y, 0, 0, this.imageWidth, 125);
        guiGraphics.blit(CONTAINER_TEXTURE, x, y + 125, 0, 126, this.imageWidth, 96);

        // 繪製滾動條滑軌與滑塊
        int barX = x + 175;
        int barY = y + 18 + (int) (this.scrollOffs * 92.0F);
        guiGraphics.fill(barX, y + 18, barX + 5, y + 125, 0x44000000);
        guiGraphics.fill(barX, barY, barX + 5, barY + 15, 0xFFAAAAAA);

        int startX = x + 8;
        int startY = y + 18;
        int rowOffset = getRowOffset();

        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 9; c++) {
                int itemIdx = (rowOffset + r) * 9 + c;
                int slotX = startX + c * 18;
                int slotY = startY + r * 18;

                if (mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16) {
                    guiGraphics.fillGradient(slotX, slotY, slotX + 16, slotY + 16, 0x80FFFFFF, 0x80FFFFFF);
                }

                if (itemIdx < this.trashItems.size()) {
                    ItemStack stack = this.trashItems.get(itemIdx);
                    if (!stack.isEmpty()) {
                        guiGraphics.renderItem(stack, slotX, slotY);
                        guiGraphics.renderItemDecorations(this.font, stack, slotX, slotY);
                    }
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int startX = this.leftPos + 8;
        int startY = this.topPos + 18;
        boolean hoveredTrash = false;

        if (mouseX >= startX && mouseX < startX + 9 * 18 && mouseY >= startY && mouseY < startY + 6 * 18) {
            int col = ((int) mouseX - startX) / 18;
            int row = ((int) mouseY - startY) / 18;
            int itemIdx = (getRowOffset() + row) * 9 + col;

            if (itemIdx >= 0 && itemIdx < this.trashItems.size()) {
                ItemStack stack = this.trashItems.get(itemIdx);
                if (!stack.isEmpty()) {
                    guiGraphics.renderTooltip(this.font, stack, mouseX, mouseY);
                    hoveredTrash = true;
                }
            }
        }

        if (!hoveredTrash) {
            this.renderTooltip(guiGraphics, mouseX, mouseY);
        }
    }
}