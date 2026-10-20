package com.vt.vt.client;

import com.vt.vt.VTMod;
import com.vt.vt.mixin.OptionsSubScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = VTMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class KeybindRestoreButtonEvent {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof KeyBindsScreen screen)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        // 1. 安全初始化：檢查並建立初始備份
        KeybindGuardManager.initBackupIfNeeded();

        // 2. 尋找原版「全部重設」按鈕以錨定座標
        AbstractWidget resetButton = null;
        String resetText = Component.translatable("controls.resetAll").getString();

        for (var listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget widget) {
                String msg = widget.getMessage().getString();
                if (msg.equals(resetText) || msg.contains(resetText)) {
                    resetButton = widget;
                    break;
                }
            }
        }

        // 3. 自適應按鈕寬度（依語言文字長度計算，防止英文 "Restore" 破圖溢出）
        Component restoreText = Component.translatable("vt.options.restore");
        int textWidth = mc.font.width(restoreText);
        int buttonWidth = Math.max(40, textWidth + 12);
        int buttonHeight = 20;
        int x, y;

        if (resetButton != null) {
            x = Math.max(4, resetButton.getX() - buttonWidth - 4);
            y = resetButton.getY();
        } else {
            x = 4;
            y = screen.height - 29;
        }

        // 4. 建立 [還原] 按鈕，附帶 Tooltip 與無備份防呆
        Button restoreBtn = Button.builder(restoreText, btn -> {
            boolean success = KeybindGuardManager.restoreAllFromBackup();
            if (success) {
                Screen lastScreen = ((OptionsSubScreenAccessor) screen).getLastScreen();
                mc.setScreen(new KeyBindsScreen(lastScreen, mc.options));
            }
        })
        .bounds(x, y, buttonWidth, buttonHeight)
        .tooltip(Tooltip.create(Component.translatableWithFallback("vt.options.restore.tooltip", "還原為自訂/備份的按鍵設定")))
        .build();

        // 若無任何備份記錄，禁用按鈕
        restoreBtn.active = KeybindGuardManager.hasBackup();

        event.addListener(restoreBtn);
    }
}