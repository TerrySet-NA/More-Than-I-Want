package com.vt.vt.client;

import com.vt.vt.cleaner.TrashRollbackMenu;
import com.vt.vt.network.TrashNetwork;
import net.minecraft.client.Minecraft;

public class ClientTrashNetworkHandler {
    public static void handleSync(TrashNetwork.SyncTrashPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu instanceof TrashRollbackMenu menu && menu.backupIndex == payload.backupIndex()) {
            menu.clientTrashItems = payload.items();
            if (mc.screen instanceof TrashRollbackScreen screen) {
                screen.updateTrashItems(payload.items());
            }
        }
    }
}