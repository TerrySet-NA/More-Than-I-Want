package com.vt.vt.config;

import com.vt.vt.VTMod;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.*;
import java.util.function.Supplier;

public class ConfigSyncManager {

    // 使用 volatile 不可變 Map 保證線程安全與原子切換，消除 clear() 與 putAll() 之間的讀取空窗期
    private static volatile Map<String, Map<String, Object>> syncedValues = Map.of();
    private static volatile boolean active = false;
    private static volatile boolean isApplyingSync = false;

    public static boolean isApplyingSync() {
        return isApplyingSync;
    }

    public static boolean isOverrideActive() {
        if (!active || syncedValues.isEmpty()) {
            return false;
        }
        // 隔離 Client 代碼調用，防止 Dedicated Server 載入崩潰
        if (FMLEnvironment.dist.isClient() && ClientConfigSyncHelper.isClientHost()) {
            return false;
        }
        return true;
    }

    public static <T> T getOverride(ModConfigSpec spec, List<String> path, Supplier<T> defaultSupplier) {
        String fileName = UniversalConfigTracker.getFileName(spec);
        if (fileName == null) return null;

        Map<String, Object> fileMap = syncedValues.get(fileName);
        if (fileMap == null || fileMap.isEmpty()) return null;

        // 高效路徑解析，避免頻繁調用 String.join 產生垃圾字串
        String key = fastJoinPath(path);
        Object rawValue = fileMap.get(key);
        return adaptType(rawValue, defaultSupplier);
    }

    public static void applySyncedValues(Map<String, Map<String, Object>> values) {
        // 房主端不套用覆寫
        if (FMLEnvironment.dist.isClient() && ClientConfigSyncHelper.isClientHost()) {
            return;
        }

        try {
            isApplyingSync = true;
            // 建立不可變快照並原子賦值
            Map<String, Map<String, Object>> snapshot = new HashMap<>();
            values.forEach((k, v) -> snapshot.put(k, Map.copyOf(v)));
            syncedValues = Map.copyOf(snapshot);
            active = true;

            notifyTargetModsReload(syncedValues.keySet());
            VTMod.LOGGER.info("[VT/Sync] 【房客端】已套用伺服端 Common 設定覆寫 (共 {} 個設定檔)", values.size());
        } finally {
            isApplyingSync = false;
        }
    }

    public static void clearSyncedValues() {
        if (!active && syncedValues.isEmpty()) return;

        try {
            isApplyingSync = true;
            Set<String> affectedFiles = new HashSet<>(syncedValues.keySet());
            syncedValues = Map.of();
            active = false;

            notifyTargetModsReload(affectedFiles);
            VTMod.LOGGER.info("[VT/Sync] 【房客端】已還原本地預設。");
        } finally {
            isApplyingSync = false;
        }
    }

    private static void notifyTargetModsReload(Set<String> modifiedFiles) {
        try {
            for (ModConfig cfg : UniversalConfigTracker.getAllCommonConfigs()) {
                if (modifiedFiles.contains(cfg.getFileName())) {
                    ModList.get().getModContainerById(cfg.getModId()).ifPresent(container -> {
                        try {
                            container.getEventBus().post(new ModConfigEvent.Reloading(cfg));
                        } catch (Throwable ignored) {}
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 高頻調用優化：針對 Minecraft 設定檔絕大多數為 1~2 層路徑進行極速拼接
     */
    private static String fastJoinPath(List<String> path) {
        int size = path.size();
        if (size == 0) return "";
        if (size == 1) return path.get(0);
        if (size == 2) return path.get(0) + "." + path.get(1);
        return String.join(".", path);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T adaptType(Object raw, Supplier<T> defaultSupplier) {
        if (raw == null) return null;
        if (defaultSupplier == null) return (T) raw;

        T defaultVal = defaultSupplier.get();
        if (defaultVal == null) return (T) raw;

        Class<?> targetClass = defaultVal.getClass();
        if (targetClass.isInstance(raw)) return (T) raw;

        if (raw instanceof Number num) {
            if (targetClass == Integer.class) return (T) Integer.valueOf(num.intValue());
            if (targetClass == Double.class) return (T) Double.valueOf(num.doubleValue());
            if (targetClass == Long.class) return (T) Long.valueOf(num.longValue());
            if (targetClass == Float.class) return (T) Float.valueOf(num.floatValue());
            if (targetClass == Short.class) return (T) Short.valueOf(num.shortValue());
            if (targetClass == Byte.class) return (T) Byte.valueOf(num.byteValue());
        }

        if (targetClass == Boolean.class && raw instanceof String str) {
            return (T) Boolean.valueOf(Boolean.parseBoolean(str));
        }

        if (targetClass == String.class) return (T) raw.toString();

        if (Enum.class.isAssignableFrom(targetClass)) {
            try {
                return (T) Enum.valueOf((Class<Enum>) targetClass, raw.toString());
            } catch (Throwable ignored) {
                return null;
            }
        }

        if (defaultVal instanceof List<?> && raw instanceof List<?> rawList) {
            return (T) rawList;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    public static void flatten(String prefix, Map<String, Object> source, Map<String, Object> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof com.electronwill.nightconfig.core.UnmodifiableConfig sub) {
                flatten(key, sub.valueMap(), target);
            } else if (value instanceof Map<?, ?> subMap) {
                flatten(key, (Map<String, Object>) subMap, target);
            } else if (value instanceof Enum<?> e) {
                target.put(key, e.name());
            } else if (value != null) {
                target.put(key, value);
            }
        }
    }
}