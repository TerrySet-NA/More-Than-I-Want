package com.vt.vt.client;

import com.vt.vt.VTMod;
import com.vt.vt.config.ClientConfigSyncHelper;
import com.vt.vt.config.ConfigCommon;
import com.vt.vt.config.ConfigSyncManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = VTMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class ClientConfigScreenEvents {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen screen) || !ConfigCommon.ENABLE_CONFIG_SYNC.get()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        boolean isLocal = mc.isLocalServer();
        boolean isHost = ClientConfigSyncHelper.isHostUser(mc);

        int btnSize = 20;
        int btnX = screen.width - btnSize - 8;
        int btnY = 8;

        // 1. 純單人模式：只出現 R（清空外來覆寫，還原回乾淨的本地設定）
        if (isLocal && !isHost) {
            Button restoreButton = Button.builder(
                    Component.translatableWithFallback("vt.gui.config.restore", "§aR"),
                    btn -> {
                        ConfigSyncManager.clearSyncedValues();
                        if (mc.player != null) {
                            mc.player.displayClientMessage(Component.translatable("vt.gui.config.message.restored"), false);
                        }
                    }
            )
            .bounds(btnX, btnY, btnSize, btnSize)
            .tooltip(Tooltip.create(Component.translatable("vt.gui.config.restore.tooltip")))
            .build();

            event.addListener(restoreButton);
        }
        // 2. 多人模式 - 房主：只出現 S（將設定分發給在線房客）
        else if (isHost) {
            Button distributeButton = Button.builder(
                    Component.translatableWithFallback("vt.gui.config.redistribute", "§6S"),
                    btn -> ClientConfigSyncHelper.executeUploadAndDistribute(mc)
            )
            .bounds(btnX, btnY, btnSize, btnSize)
            .tooltip(Tooltip.create(Component.translatable("vt.gui.config.redistribute.tooltip")))
            .build();

            event.addListener(distributeButton);
        }
        // 3. 多人模式 - 房客：完全不產生任何按鈕
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ConfigSyncManager.clearSyncedValues();
    }
}