package com.vt.vt.network;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.vt.vt.VTMod;
import com.vt.vt.config.CommonConfigSyncHooks;
import com.vt.vt.config.ConfigSyncManager;
import com.vt.vt.config.UniversalConfigTracker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public record UploadAndDistributePayload(byte[] compressedData) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UploadAndDistributePayload> TYPE = 
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("vt", "upload_and_distribute"));

    public static final StreamCodec<ByteBuf, UploadAndDistributePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE_ARRAY, UploadAndDistributePayload::compressedData,
            UploadAndDistributePayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { 
        return TYPE; 
    }

    public static UploadAndDistributePayload fromMap(Map<String, String> configs) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzos = new GZIPOutputStream(baos);
             DataOutputStream dos = new DataOutputStream(gzos)) {

            dos.writeInt(configs.size());
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                dos.writeUTF(entry.getKey());
                byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
            gzos.finish();
            return new UploadAndDistributePayload(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to compress config upload payload", e);
        }
    }

    public Map<String, String> toMap() {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
             GZIPInputStream gzis = new GZIPInputStream(bais);
             DataInputStream dis = new DataInputStream(gzis)) {

            int count = dis.readInt();
            Map<String, String> map = new HashMap<>(count);
            for (int i = 0; i < count; i++) {
                String fileName = dis.readUTF();
                int len = dis.readInt();
                byte[] bytes = new byte[len];
                dis.readFully(bytes);
                map.put(fileName, new String(bytes, StandardCharsets.UTF_8));
            }
            return map;
        } catch (IOException e) {
            throw new RuntimeException("Failed to decompress config upload payload", e);
        }
    }

    public static void handle(UploadAndDistributePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            boolean isHost = player.server.isSingleplayerOwner(player.getGameProfile());
            if (!isHost) {
                player.sendSystemMessage(Component.translatable("vt.gui.config.message.noperm"));
                return;
            }

            // 白名單校驗：只允許修改當前環境已知的 Common 設定檔名稱
            Set<String> allowedFileNames = new HashSet<>();
            for (ModConfig cfg : UniversalConfigTracker.getAllCommonConfigs()) {
                allowedFileNames.add(cfg.getFileName());
            }

            Map<String, String> uploadedConfigs = payload.toMap();
            Path configDir = FMLPaths.CONFIGDIR.get().normalize().toAbsolutePath();
            TomlParser parser = new TomlParser();
            Map<String, Map<String, Object>> allValues = new HashMap<>();

            for (Map.Entry<String, String> entry : uploadedConfigs.entrySet()) {
                String fileName = entry.getKey();
                String tomlContent = entry.getValue();

                // 核心安全檢查：防止路徑穿越與非法附檔名
                if (!allowedFileNames.contains(fileName) || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                    VTMod.LOGGER.warn("[VT/Sync-Server] Rejected unauthorized config filename: {}", fileName);
                    continue;
                }

                Path filePath = configDir.resolve(fileName).normalize();
                if (!filePath.startsWith(configDir)) {
                    VTMod.LOGGER.warn("[VT/Sync-Server] Detected path traversal attempt: {}", fileName);
                    continue;
                }

                try {
                    boolean needWrite = true;
                    if (Files.exists(filePath)) {
                        String currentDiskContent = Files.readString(filePath, StandardCharsets.UTF_8);
                        if (currentDiskContent.equals(tomlContent)) {
                            needWrite = false;
                        }
                    }

                    if (needWrite) {
                        Files.writeString(filePath, tomlContent, StandardCharsets.UTF_8);
                        CommonConfigSyncHooks.updateHash(fileName, CommonConfigSyncHooks.computeFileHash(filePath));
                    }

                    Config parsed = parser.parse(new StringReader(tomlContent));
                    Map<String, Object> flatMap = new HashMap<>();
                    ConfigSyncManager.flatten("", parsed.valueMap(), flatMap);
                    allValues.put(fileName, flatMap);
                } catch (Exception e) {
                    VTMod.LOGGER.error("[VT/Sync-Server] Failed to process config file: {}", fileName, e);
                }
            }

            SyncCommonConfigValuesPayload syncPayload = SyncCommonConfigValuesPayload.fromValues(allValues);
            int count = 0;
            for (ServerPlayer p : player.server.getPlayerList().getPlayers()) {
                if (!player.server.isSingleplayerOwner(p.getGameProfile()) && p.connection != null) {
                    PacketDistributor.sendToPlayer(p, syncPayload);
                    count++;
                }
            }

            player.sendSystemMessage(Component.translatable("vt.gui.config.message.redistributed"));
            VTMod.LOGGER.info("[VT/Sync-Server] Config redistributed to {} guest(s)", count);
        });
    }
}