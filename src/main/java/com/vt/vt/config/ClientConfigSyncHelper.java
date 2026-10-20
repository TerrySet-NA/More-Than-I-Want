package com.vt.vt.config;

import com.vt.vt.network.UploadAndDistributePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 客戶端專用輔助類別，僅在 FMLEnvironment.dist.isClient() 為真時被載入
 */
public class ClientConfigSyncHelper {

    public static void handleClientConfigReload() {
        Minecraft mc = Minecraft.getInstance();
        if (isHostUser(mc)) {
            executeUploadAndDistribute(mc);
        }
    }

    public static boolean isHostUser(Minecraft mc) {
        if (mc.isLocalServer()) {
            MinecraftServer s = mc.getSingleplayerServer();
            return s != null && s.isPublished();
        }
        return false;
    }

    public static void executeUploadAndDistribute(Minecraft mc) {
        if (!ConfigCommon.ENABLE_CONFIG_SYNC.get()) return;

        Map<String, String> localConfigs = new HashMap<>();
        Path configDir = FMLPaths.CONFIGDIR.get();

        Collection<ModConfig> commonConfigs = UniversalConfigTracker.getAllCommonConfigs();
        for (ModConfig cfg : commonConfigs) {
            String fileName = cfg.getFileName();
            Path path = configDir.resolve(fileName);
            if (Files.exists(path)) {
                try {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    localConfigs.put(fileName, content);
                    CommonConfigSyncHooks.updateHash(fileName, CommonConfigSyncHooks.computeFileHash(path));
                } catch (Exception ignored) {}
            }
        }

        if (!localConfigs.isEmpty()) {
            PacketDistributor.sendToServer(UploadAndDistributePayload.fromMap(localConfigs));
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("vt.gui.config.message.auto_redistributed"), false);
            }
        }
    }

    public static boolean isClientHost() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc.isLocalServer() && mc.getSingleplayerServer() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}