package com.vt.vt.network;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vt.vt.VTMod;
import com.vt.vt.config.ConfigSyncManager;
import com.vt.vt.config.UniversalConfigTracker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public record SyncCommonConfigValuesPayload(byte[] compressedJson) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncCommonConfigValuesPayload> TYPE = 
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("vt", "sync_common_config_values"));

    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type MAP_TYPE = 
            new TypeToken<Map<String, Map<String, Object>>>(){}.getType();

    public static final StreamCodec<ByteBuf, SyncCommonConfigValuesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE_ARRAY, SyncCommonConfigValuesPayload::compressedJson,
            SyncCommonConfigValuesPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { 
        return TYPE; 
    }

    public static SyncCommonConfigValuesPayload fromValues(Map<String, Map<String, Object>> allValues) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            String json = GSON.toJson(allValues);
            gzos.write(json.getBytes(StandardCharsets.UTF_8));
            gzos.finish();
            return new SyncCommonConfigValuesPayload(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize common config payload", e);
        }
    }

    public static SyncCommonConfigValuesPayload createFromServer() {
        Map<String, Map<String, Object>> allValues = new HashMap<>();
        try {
            Collection<ModConfig> commonConfigs = UniversalConfigTracker.getAllCommonConfigs();

            if (!commonConfigs.isEmpty()) {
                TomlParser parser = new TomlParser();
                for (ModConfig config : commonConfigs) {
                    Path configPath = FMLPaths.CONFIGDIR.get().resolve(config.getFileName());
                    if (Files.exists(configPath)) {
                        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                            Config parsed = parser.parse(reader);
                            Map<String, Object> flatMap = new HashMap<>();
                            ConfigSyncManager.flatten("", parsed.valueMap(), flatMap);
                            if (!flatMap.isEmpty()) {
                                allValues.put(config.getFileName(), flatMap);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            VTMod.LOGGER.error("[VT/Sync-Server] Failed to collect common configs from server", t);
        }

        return fromValues(allValues);
    }

    public static void handle(SyncCommonConfigValuesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(payload.compressedJson());
                 GZIPInputStream gzis = new GZIPInputStream(bais);
                 InputStreamReader reader = new InputStreamReader(gzis, StandardCharsets.UTF_8)) {

                Map<String, Map<String, Object>> values = GSON.fromJson(reader, MAP_TYPE);
                if (values != null && !values.isEmpty()) {
                    ConfigSyncManager.applySyncedValues(values);
                }
            } catch (Throwable t) {
                VTMod.LOGGER.error("[VT/Sync-Client] Failed to parse sync common config payload", t);
            }
        });
    }
}