package com.vt.vt.config;

import com.vt.vt.VTMod;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UniversalConfigTracker {

    private static Field configSetsField;
    private static Field fileMapField;
    private static volatile boolean initialized = false;

    private static final Set<ModConfigSpec> COMMON_SPECS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<ModConfigSpec, String> SPEC_TO_FILE = new ConcurrentHashMap<>();

    private static synchronized void init() {
        if (initialized) return;

        try {
            configSetsField = ConfigTracker.class.getDeclaredField("configSets");
            configSetsField.setAccessible(true);
        } catch (Throwable t) {
            VTMod.LOGGER.debug("[VT/Tracker] ConfigTracker.configSets reflection not available: {}", t.getMessage());
        }

        try {
            fileMapField = ConfigTracker.class.getDeclaredField("fileMap");
            fileMapField.setAccessible(true);
        } catch (Throwable t) {
            VTMod.LOGGER.debug("[VT/Tracker] ConfigTracker.fileMap reflection not available: {}", t.getMessage());
        }

        initialized = true;
        refreshCache();
    }

    public static synchronized void refreshCache() {
        COMMON_SPECS.clear();
        SPEC_TO_FILE.clear();
        for (ModConfig cfg : getAllCommonConfigs()) {
            if (cfg.getSpec() instanceof ModConfigSpec spec) {
                COMMON_SPECS.add(spec);
                SPEC_TO_FILE.put(spec, cfg.getFileName());
            }
        }
    }

    public static boolean isCommonSpec(ModConfigSpec spec) {
        if (!initialized) init();
        if (COMMON_SPECS.contains(spec)) return true;

        // 若初次載入未命中（可能模組延遲註冊），嘗試刷新一次
        refreshCache();
        return COMMON_SPECS.contains(spec);
    }

    public static String getFileName(ModConfigSpec spec) {
        if (!initialized) init();
        String file = SPEC_TO_FILE.get(spec);
        if (file == null) {
            // 未命中時嘗試重刷一次，確保不遺漏後加載的設定
            refreshCache();
            file = SPEC_TO_FILE.get(spec);
        }
        return file;
    }

    @SuppressWarnings("unchecked")
    public static Collection<ModConfig> getAllCommonConfigs() {
        if (!initialized) init();

        // 優先嘗試 configSets (更直接)
        if (configSetsField != null) {
            try {
                Map<ModConfig.Type, Set<ModConfig>> configSets = 
                        (Map<ModConfig.Type, Set<ModConfig>>) configSetsField.get(ConfigTracker.INSTANCE);
                if (configSets != null) {
                    Set<ModConfig> commons = configSets.get(ModConfig.Type.COMMON);
                    if (commons != null && !commons.isEmpty()) {
                        // 回傳安全副本，防止遍歷時拋出 ConcurrentModificationException
                        return List.copyOf(commons);
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 次選 fileMap
        if (fileMapField != null) {
            try {
                Map<String, ModConfig> fileMap = 
                        (Map<String, ModConfig>) fileMapField.get(ConfigTracker.INSTANCE);
                if (fileMap != null) {
                    List<ModConfig> list = new ArrayList<>();
                    for (ModConfig cfg : fileMap.values()) {
                        if (cfg.getType() == ModConfig.Type.COMMON) {
                            list.add(cfg);
                        }
                    }
                    return list;
                }
            } catch (Throwable ignored) {}
        }

        return Collections.emptyList();
    }
}