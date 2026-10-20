package com.vt.vt.translation;

import com.vt.vt.VTMod;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

// 🎯 注意：必須為 Dist.CLIENT 且使用 MOD 事件總線 (Bus.MOD)
@SuppressWarnings("removal")
@EventBusSubscriber(modid = VTMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModEventHandlers {

    /**
     * 監聽用戶端資源包重載 (F3 + T / 切換資源包 / 變更語言)
     */
    @SubscribeEvent
    public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        // 註冊我們的資源包重載監聽器
        event.registerReloadListener(new TranslationReloadListener());
    }
}