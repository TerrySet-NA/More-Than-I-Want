package com.vt.vt.client;

import com.vt.vt.VTMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public class VTClientMod {
    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(VTClientMod::registerScreens);
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(VTMod.TRASH_ROLLBACK_MENU.get(), TrashRollbackScreen::new);
    }
}