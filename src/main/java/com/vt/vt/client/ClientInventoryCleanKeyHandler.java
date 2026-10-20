package com.vt.vt.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.vt.vt.VTMod;
import com.vt.vt.network.CleanNbtPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = VTMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class ClientInventoryCleanKeyHandler {

    // 衝突上下文設為 GUI，預設 Delete 鍵
    public static final KeyMapping CLEAN_NBT_KEY = new KeyMapping(
            "key.vt.clean_nbt",
            net.neoforged.neoforge.client.settings.KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_P,
            "key.categories.vt"
    );

    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        // 1. 僅在開啟任何容器/背包介面時生效 (包含生存背包、箱子等所有 ContainerScreen)
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        // 2. 判斷是否按下了設定的清理鍵
        int keyCode = event.getKeyCode();
        if (keyCode != CLEAN_NBT_KEY.getKey().getValue()) {
            return;
        }

        // 3. 輸入防呆：若游標正處於任何 EditBox 或 JEI/EMI 搜尋框輸入文字時，不觸發清理
        if (isTyping(containerScreen)) {
            return;
        }

        // 4. 發送封包至伺服端執行清洗與重疊
        PacketDistributor.sendToServer(new CleanNbtPayload());
        event.setCanceled(true); // 消耗該按鍵事件，防止觸發其他 GUI 預設行為
    }

    private static boolean isTyping(AbstractContainerScreen<?> screen) {
        var focused = screen.getFocused();
        if (focused instanceof EditBox editBox && editBox.canConsumeInput()) {
            return true;
        }
        // 調用我們先前寫好的配方搜尋框防呆判定
        return ClientTranslationInputHandler.JeiCompat.isTyping();
    }

    @EventBusSubscriber(modid = VTMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(CLEAN_NBT_KEY);
        }
    }
}