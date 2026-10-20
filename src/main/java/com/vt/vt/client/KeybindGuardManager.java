package com.vt.vt.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.vt.vt.VTMod;
import com.vt.vt.config.ConfigClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.settings.KeyModifier;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public class KeybindGuardManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path BACKUP_PATH = FMLPaths.CONFIGDIR.get().resolve("vt_keybinds.json");

    private static final Map<String, KeyData> MEMORY_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean isCacheLoaded = false;
    private static boolean initialScanDone = false;
    private static boolean startupRestored = false;

    public static class KeyData {
        public String key;
        public String modifier;

        public KeyData() {}

        public KeyData(String key, String modifier) {
            this.key = key;
            this.modifier = modifier;
        }
    }

    /**
     * 遊戲啟動時自動還原邏輯
     */
    public static synchronized void autoRestoreOnStartup() {
        if (startupRestored) return;
        startupRestored = true;

        if (!ConfigClient.AUTO_RESTORE_KEYBINDS.get()) {
            return;
        }

        if (hasBackup()) {
            boolean success = restoreAllFromBackup();
            if (success) {
                VTMod.LOGGER.info("[VT/KeybindGuard] Automatically restored keybinds from backup on startup.");
            }
        } else {
            initBackupIfNeeded();
        }
    }

    public static boolean hasBackup() {
        ensureCacheLoaded();
        return !MEMORY_CACHE.isEmpty() || Files.exists(BACKUP_PATH);
    }

    public static synchronized void saveSingleKey(KeyMapping key) {
        if (key == null) return;

        Minecraft mc = Minecraft.getInstance();
        // 核心防護：未進入介面（讀取 options.txt 中）時不覆寫備份
        if (mc == null || mc.screen == null) {
            return;
        }

        try {
            ensureCacheLoaded();

            KeyModifier mod = key.getKeyModifier();
            String modStr = (mod != null) ? mod.name() : "NONE";

            MEMORY_CACHE.put(key.getName(), new KeyData(key.saveString(), modStr));
            saveCacheToFile();
        } catch (Exception e) {
            VTMod.LOGGER.error("[VT/KeybindGuard] Failed to save keybind: {}", key.getName(), e);
        }
    }

    public static synchronized void initBackupIfNeeded() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;

        if (initialScanDone && Files.exists(BACKUP_PATH)) {
            return;
        }

        try {
            ensureCacheLoaded();
            boolean needSave = false;

            for (KeyMapping key : mc.options.keyMappings) {
                if (key == null) continue;
                String keyId = key.getName();
                String currentBound = key.saveString();
                String defaultBound = key.getDefaultKey().getName();

                KeyModifier mod = key.getKeyModifier();
                String modStr = (mod != null) ? mod.name() : "NONE";

                if (!MEMORY_CACHE.containsKey(keyId)) {
                    if (!currentBound.equals(defaultBound) || (mod != null && mod != KeyModifier.NONE)) {
                        MEMORY_CACHE.put(keyId, new KeyData(currentBound, modStr));
                        needSave = true;
                    }
                }
            }

            if (needSave || !Files.exists(BACKUP_PATH)) {
                saveCacheToFile();
            }
            initialScanDone = true;
        } catch (Exception e) {
            VTMod.LOGGER.error("[VT/KeybindGuard] Failed to init backup", e);
        }
    }

    public static boolean restoreAllFromBackup() {
        ensureCacheLoaded();
        if (MEMORY_CACHE.isEmpty()) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return false;

        boolean remapped = false;

        for (KeyMapping key : mc.options.keyMappings) {
            if (key == null) continue;
            String keyId = key.getName();

            if (MEMORY_CACHE.containsKey(keyId)) {
                KeyData savedData = MEMORY_CACHE.get(keyId);
                if (savedData != null && savedData.key != null) {
                    try {
                        InputConstants.Key inputKey = InputConstants.getKey(savedData.key);

                        KeyModifier modifier = KeyModifier.NONE;
                        if (savedData.modifier != null && !savedData.modifier.isEmpty()) {
                            try {
                                modifier = KeyModifier.valueFromString(savedData.modifier);
                            } catch (Exception ignored) {
                                try {
                                    modifier = KeyModifier.valueOf(savedData.modifier.toUpperCase(Locale.ROOT));
                                } catch (Exception ignored2) {}
                            }
                        }
                        if (modifier == null) modifier = KeyModifier.NONE;

                        key.setKeyModifierAndCode(modifier, inputKey);
                        remapped = true;
                    } catch (Exception e) {
                        VTMod.LOGGER.warn("[VT/KeybindGuard] Skipping unknown key during restore: {}", savedData.key);
                    }
                }
            }
        }

        if (remapped) {
            KeyMapping.resetMapping();
            mc.options.save();
            return true;
        }

        return false;
    }

    private static void ensureCacheLoaded() {
        if (!isCacheLoaded) {
            synchronized (MEMORY_CACHE) {
                if (!isCacheLoaded) {
                    MEMORY_CACHE.putAll(loadMapFromDisk());
                    isCacheLoaded = true;
                }
            }
        }
    }

    private static Map<String, KeyData> loadMapFromDisk() {
        Map<String, KeyData> result = new HashMap<>();
        if (!Files.exists(BACKUP_PATH)) return result;

        try (Reader reader = Files.newBufferedReader(BACKUP_PATH, StandardCharsets.UTF_8)) {
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();

            for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                String keyId = entry.getKey();
                JsonElement element = entry.getValue();

                if (element.isJsonObject()) {
                    KeyData data = GSON.fromJson(element, KeyData.class);
                    if (data != null) result.put(keyId, data);
                } else if (element.isJsonPrimitive()) {
                    result.put(keyId, new KeyData(element.getAsString(), "NONE"));
                }
            }
        } catch (Exception e) {
            VTMod.LOGGER.error("[VT/KeybindGuard] Failed to read keybind backup file", e);
        }
        return result;
    }

    private static synchronized void saveCacheToFile() {
        try {
            Path parent = BACKUP_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Path tempPath = BACKUP_PATH.resolveSibling(BACKUP_PATH.getFileName().toString() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                GSON.toJson(MEMORY_CACHE, writer);
            }
            Files.move(tempPath, BACKUP_PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            VTMod.LOGGER.error("[VT/KeybindGuard] Failed to write keybind backup file", e);
        }
    }

    // 透過 Mod 總線監聽客戶端設置完畢事件，在背景自動觸發還原
    @EventBusSubscriber(modid = VTMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientSetupEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(KeybindGuardManager::autoRestoreOnStartup);
        }
    }
}