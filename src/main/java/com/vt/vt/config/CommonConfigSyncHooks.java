package com.vt.vt.config;

import com.vt.vt.VTMod;
import com.vt.vt.cleaner.TrashCleanerService;
import com.vt.vt.network.SyncCommonConfigValuesPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class CommonConfigSyncHooks {

    private static final Map<String, String> LAST_KNOWN_HASHES = new ConcurrentHashMap<>();

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ConfigCommon.ENABLE_CONFIG_SYNC.get()) {
            return;
        }

        MinecraftServer server = player.server;
        if (server.isSingleplayerOwner(player.getGameProfile())) {
            return;
        }

        VTMod.LOGGER.info("[VT/Sync] 房客 {} 加入，發送全模組 Common 設定...", player.getScoreboardName());
        try {
            PacketDistributor.sendToPlayer(player, SyncCommonConfigValuesPayload.createFromServer());
        } catch (Throwable t) {
            VTMod.LOGGER.error("[VT/Sync] 發送 Common 設定失敗", t);
        }
    }

    public static void onAnyModConfigReload(ModConfigEvent.Reloading event) {
        ModConfig modConfig = event.getConfig();
        if (modConfig.getType() != ModConfig.Type.COMMON) {
            return;
        }

        // 若是 VT 自身的 Common 設定變更，同步刷新掉落物快取
        if (modConfig.getSpec() == ConfigCommon.SPEC) {
            TrashCleanerService.reloadConfigCache();
        }

        if (!ConfigCommon.ENABLE_CONFIG_SYNC.get() || ConfigSyncManager.isApplyingSync()) {
            return;
        }

        String fileName = modConfig.getFileName();
        Path filePath = FMLPaths.CONFIGDIR.get().resolve(fileName);
        if (!Files.exists(filePath)) {
            return;
        }

        String currentHash = computeFileHash(filePath);
        String lastHash = LAST_KNOWN_HASHES.get(fileName);

        // 雜湊一致代表內容未變更，終止分發
        if (Objects.equals(currentHash, lastHash)) {
            return;
        }

        VTMod.LOGGER.info("[VT/Sync] 偵測到 Common 設定檔 [{}] 內容變動，啟動同步更新...", fileName);
        LAST_KNOWN_HASHES.put(fileName, currentHash);

        // 客戶端邏輯委派給隔離類別，防止 Dedicated Server 出現 NoClassDefFoundError
        if (FMLEnvironment.dist.isClient()) {
            ClientConfigSyncHelper.handleClientConfigReload();
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> broadcastServerConfigs(server));
        }
    }

    public static void broadcastServerConfigs(MinecraftServer server) {
        try {
            SyncCommonConfigValuesPayload payload = SyncCommonConfigValuesPayload.createFromServer();
            int count = 0;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!server.isSingleplayerOwner(player.getGameProfile()) && player.connection != null) {
                    PacketDistributor.sendToPlayer(player, payload);
                    count++;
                }
            }
            VTMod.LOGGER.info("[VT/Sync-Server] 已將最新設定分發給 {} 位房客", count);
        } catch (Throwable t) {
            VTMod.LOGGER.error("[VT/Sync-Server] 廣播更新失敗", t);
        }
    }

    public static void updateHash(String fileName, String hash) {
        LAST_KNOWN_HASHES.put(fileName, hash);
    }

    /**
     * 高效計算 SHA-256 雜湊（Java 21 原生 HexFormat，零多餘物件分配）
     */
    public static String computeFileHash(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }
}