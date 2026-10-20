package com.vt.vt.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

public class TextOverrideManager {
    private static final Map<String, String> TEXT_OVERRIDES_MAP = new LinkedHashMap<>();
    private static volatile List<Map.Entry<String, String>> TEXT_OVERRIDES_ITERATOR = new ArrayList<>();
    
    public static class TemplateRecord {
        public final String template;
        public final String key;
        public final String regex;
        public final Pattern compiledPattern;

        public TemplateRecord(String template, String key, String regex) {
            this.template = template;
            this.key = key;
            this.regex = regex;
            Pattern p = null;
            try {
                p = Pattern.compile(regex);
            } catch (Exception ignored) {}
            this.compiledPattern = p;
        }
    }

    private static final List<TemplateRecord> WILDCARD_TRANSLATIONS = new CopyOnWriteArrayList<>();
    public static final ConcurrentHashMap<String, String> REVERSE_TRANSLATION_MAP = new ConcurrentHashMap<>();

    public static ItemStack lastActiveItemStack = ItemStack.EMPTY;
    public static List<Component> lastActiveTooltip = null;
    
    public static String hoveredKey = "";
    public static String hoveredTextString = "";
    public static boolean hoveredTextHasNativeKey = false;

    // 渲染快取與防記憶體洩漏閾值
    private static final int MAX_CACHE_SIZE = 10000;
    private static final ConcurrentHashMap<String, String> RENDER_CACHE = new ConcurrentHashMap<>();
    private static final String MARK_NO_REPLACEMENT = "__VT_SKIP__";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path TEXT_CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("vt_text_overrides.json");

    public static void load() {
        loadMap(TEXT_CONFIG_PATH, TEXT_OVERRIDES_MAP);
        updateIterators();
        invalidateCache(); 
        clearCache();      
    }

    public static void invalidateCache() { 
        RENDER_CACHE.clear(); 
    }

    private static void updateIterators() {
        synchronized (TEXT_OVERRIDES_MAP) {
            TEXT_OVERRIDES_ITERATOR = new ArrayList<>(TEXT_OVERRIDES_MAP.entrySet());
        }
    }

    public static void addWildcardTemplate(String template, String key, String regex) {
        WILDCARD_TRANSLATIONS.add(new TemplateRecord(template, key, regex));
    }

    public static String getFastPathText(String rawText) {
        if (rawText == null || rawText.isEmpty()) return rawText;

        String cached = RENDER_CACHE.get(rawText);
        if (cached != null) {
            return cached.equals(MARK_NO_REPLACEMENT) ? rawText : cached;
        }

        String processed = rawText;
        boolean isTargetMarked = false;

        List<Map.Entry<String, String>> currentTextOverrides = TEXT_OVERRIDES_ITERATOR;
        for (int i = currentTextOverrides.size() - 1; i >= 0; i--) {
            Map.Entry<String, String> entry = currentTextOverrides.get(i);
            String targetKey = entry.getKey();
            String replacement = entry.getValue();
            if (!targetKey.isEmpty() && processed.contains(targetKey)) {
                processed = processed.replace(targetKey, replacement);
                isTargetMarked = true;
            }
        }

        // 容量自我調節，防止動態文字（如座標、時間、計分板）導致記憶體溢位
        if (RENDER_CACHE.size() > MAX_CACHE_SIZE) {
            RENDER_CACHE.clear();
        }

        if (isTargetMarked) {
            RENDER_CACHE.put(rawText, processed);
            return processed;
        } else {
            RENDER_CACHE.put(rawText, MARK_NO_REPLACEMENT);
            return rawText;
        }
    }

    public static class DecodedResult {
        public final String key;
        public final String template;
        public final boolean isTranslation;

        public DecodedResult(String key, String template, boolean isTranslation) {
            this.key = key;
            this.template = template;
            this.isTranslation = isTranslation;
        }
    }

    public static DecodedResult decodeRenderedText(String renderedText) {
        if (renderedText == null || renderedText.isEmpty()) {
            return new DecodedResult("", "", false);
        }

        // 1. 還原文字代換
        String restored = renderedText;
        List<Map.Entry<String, String>> currentTextOverrides = TEXT_OVERRIDES_ITERATOR;
        for (int i = currentTextOverrides.size() - 1; i >= 0; i--) {
            Map.Entry<String, String> entry = currentTextOverrides.get(i);
            if (!entry.getValue().isEmpty() && restored.contains(entry.getValue())) {
                restored = restored.replace(entry.getValue(), entry.getKey());
            }
        }

        // 2. 查還原後文字
        String nativeKey = findNativeKeyForRenderedText(restored);
        if (nativeKey != null) {
            String vanillaTemplate = getRawTemplateOfKey(nativeKey);
            return new DecodedResult(nativeKey, vanillaTemplate.isEmpty() ? restored : vanillaTemplate, true);
        }

        // 3. 查原始畫面文字
        nativeKey = findNativeKeyForRenderedText(renderedText);
        if (nativeKey != null) {
            String vanillaTemplate = getRawTemplateOfKey(nativeKey);
            return new DecodedResult(nativeKey, vanillaTemplate.isEmpty() ? restored : vanillaTemplate, true);
        }

        return new DecodedResult(restored, restored, false);
    }

    public static String getTrulyOriginalText(String renderedText) {
        return decodeRenderedText(renderedText).template;
    }

    public static String findNativeKeyForRenderedText(String renderedText) {
        if (renderedText == null || renderedText.isEmpty()) return null;

        if (REVERSE_TRANSLATION_MAP.containsKey(renderedText)) {
            return REVERSE_TRANSLATION_MAP.get(renderedText);
        }

        // 使用原版優化方法剝除顏色代碼，避免 regex 反覆編譯
        String cleanRendered = ChatFormatting.stripFormatting(renderedText);
        if (cleanRendered == null) return null;
        cleanRendered = cleanRendered.trim();

        if (REVERSE_TRANSLATION_MAP.containsKey(cleanRendered)) {
            return REVERSE_TRANSLATION_MAP.get(cleanRendered);
        }

        List<TemplateRecord> currentWildcards = WILDCARD_TRANSLATIONS;
        for (int i = currentWildcards.size() - 1; i >= 0; i--) {
            TemplateRecord record = currentWildcards.get(i);
            if (record.compiledPattern != null) {
                try {
                    // 使用預編譯好的 Pattern 比對，零額外編譯耗時
                    if (record.compiledPattern.matcher(cleanRendered).matches()) {
                        return record.key;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public static String getRawTemplateOfKey(String key) {
        if (key == null || key.isEmpty()) return "";
        List<TemplateRecord> currentWildcards = WILDCARD_TRANSLATIONS;
        for (int i = currentWildcards.size() - 1; i >= 0; i--) {
            TemplateRecord record = currentWildcards.get(i);
            if (record.key.equals(key)) return record.template;
        }
        for (Map.Entry<String, String> entry : REVERSE_TRANSLATION_MAP.entrySet()) {
            if (entry.getValue().equals(key)) return entry.getKey();
        }
        return "";
    }

    public static String getTextOverride(String rawText) {
        synchronized (TEXT_OVERRIDES_MAP) { 
            return TEXT_OVERRIDES_MAP.getOrDefault(rawText, rawText); 
        }
    }

    public static void putTextOverride(String rawText, String val) {
        if (rawText == null || rawText.isEmpty()) return;
        Map<String, String> snapshot;
        synchronized (TEXT_OVERRIDES_MAP) {
            if (val == null || val.isEmpty()) {
                TEXT_OVERRIDES_MAP.remove(rawText);
            } else {
                TEXT_OVERRIDES_MAP.put(rawText, val);
            }
            snapshot = new LinkedHashMap<>(TEXT_OVERRIDES_MAP);
        }
        updateIterators();
        invalidateCache();
        // 移至鎖外進行安全硬碟寫入，避免阻塞渲染管線
        saveMap(TEXT_CONFIG_PATH, snapshot);
    }

    public static boolean hasTextOverride(String rawText) {
        synchronized (TEXT_OVERRIDES_MAP) { 
            return rawText != null && TEXT_OVERRIDES_MAP.containsKey(rawText); 
        }
    }

    public static int getTextOverrideCount() { 
        synchronized (TEXT_OVERRIDES_MAP) { 
            return TEXT_OVERRIDES_MAP.size(); 
        } 
    }
    
    public static int getCacheSize() { 
        return RENDER_CACHE.size(); 
    }

    private static void loadMap(Path path, Map<String, String> map) {
        File file = path.toFile();
        if (!file.exists()) return;
        try (Reader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            LinkedHashMap<String, String> loaded = GSON.fromJson(reader, new TypeToken<LinkedHashMap<String, String>>(){}.getType());
            if (loaded != null) {
                synchronized (map) {
                    map.clear();
                    map.putAll(loaded);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 原子寫入：先寫入臨時檔再替換，徹底防止遊戲當機導致覆寫設定檔變為 0 位元組空檔案
     */
    private static void saveMap(Path path, Map<String, String> map) {
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                GSON.toJson(map, writer);
            }
            Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {}
    }

    public static void clearCache() {
        hoveredKey = "";
        hoveredTextString = "";
        hoveredTextHasNativeKey = false;
    }
}