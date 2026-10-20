package com.vt.vt.translation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TranslationExporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranslationExporter.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final Path GAME_DIR = FMLPaths.GAMEDIR.get();
    public static final Path EXPORT_ROOT_DIR = GAME_DIR.resolve("translation_export");
    
    public static final Path RESOURCE_PACKS_ROOT = GAME_DIR.resolve("resourcepacks");
    public static final Path RESOURCE_PACK_DIR = RESOURCE_PACKS_ROOT.resolve("Auto_Translations");
    public static final String PACK_ID = "file/Auto_Translations";

    private static final Map<String, String> PURE_RESTORE_TABLE = new ConcurrentHashMap<>();
    private static final Map<String, String> OTHER_RESTORE_TABLE = new ConcurrentHashMap<>();
    private static final Map<String, String> ALL_KEY_TO_NAMESPACE_CACHE = new ConcurrentHashMap<>();

    private static String LAST_SCANNED_LANGUAGE = "";

    public static String getCurrentLanguage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null && mc.options.languageCode != null) {
            return mc.options.languageCode;
        }
        return "zh_tw"; 
    }

    public static Path getExportSingleFile() {
        return EXPORT_ROOT_DIR.resolve(getCurrentLanguage() + ".json");
    }

    public static Path getExportOtherFile() {
        return getCurrentLanguage().equals("zh_tw") ? EXPORT_ROOT_DIR.resolve("other.json") : EXPORT_ROOT_DIR.resolve("other_" + getCurrentLanguage() + ".json");
    }

    public static Path getExportUnknownFile() {
        return getCurrentLanguage().equals("zh_tw") ? EXPORT_ROOT_DIR.resolve("unknown.json") : EXPORT_ROOT_DIR.resolve("unknown_" + getCurrentLanguage() + ".json");
    }

    public static Path getExportNewFile() {
        return getCurrentLanguage().equals("zh_tw") ? EXPORT_ROOT_DIR.resolve("new.json") : EXPORT_ROOT_DIR.resolve("new_" + getCurrentLanguage() + ".json");
    }

    /**
     * 輕量記憶體狀態校驗：僅在切換語言或初次載入時觸發，不在高頻渲染查詢中做磁碟 I/O
     */
    public static void ensureLoaded() {
        String currentLang = getCurrentLanguage();
        if (PURE_RESTORE_TABLE.isEmpty() || !currentLang.equals(LAST_SCANNED_LANGUAGE)) {
            rebuildRestoreTable();
        }
    }

    /**
     * 磁碟與檔案級別的完整防護：在使用者開啟 UI、儲存修改或熱重載時觸發
     */
    public static void checkAndRebuildIfNeeded() {
        String currentLang = getCurrentLanguage();
        boolean memEmpty = PURE_RESTORE_TABLE.isEmpty() && OTHER_RESTORE_TABLE.isEmpty();
        boolean langChanged = !currentLang.equals(LAST_SCANNED_LANGUAGE);
        boolean exportMissing = !Files.exists(getExportSingleFile());

        if (memEmpty || langChanged || exportMissing) {
            if (langChanged && !LAST_SCANNED_LANGUAGE.isEmpty()) {
                LOGGER.info("[VT Translation] 偵測到語言切換 ({} -> {})，重建字典快取...", LAST_SCANNED_LANGUAGE, currentLang);
            } else if (exportMissing && !memEmpty) {
                LOGGER.info("[VT Translation] 偵測到翻譯檔 ({}) 缺失，重建字典快取...", getExportSingleFile().getFileName());
            }
            rebuildRestoreTable();
        }
    }

    public static String getOriginalTranslation(String key) {
        if (key == null || key.isEmpty()) return "";
        // 核心修正：使用輕量檢查，消滅每秒數千次的 Files.exists()
        ensureLoaded();
        return PURE_RESTORE_TABLE.getOrDefault(key, OTHER_RESTORE_TABLE.getOrDefault(key, key));
    }

    private static boolean isModLoaded(String namespace) {
        if (namespace == null) return false;
        if (namespace.equals("minecraft") || namespace.equals("neoforge") || namespace.equals("realms") || namespace.equals("vt")) {
            return true;
        }
        return ModList.get().isLoaded(namespace);
    }

    public static synchronized void rebuildRestoreTable() {
        Map<String, String> newPure = new ConcurrentHashMap<>();
        Map<String, String> newOther = new ConcurrentHashMap<>();
        Map<String, String> newNamespaceCache = new ConcurrentHashMap<>();
        
        LAST_SCANNED_LANGUAGE = getCurrentLanguage();

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getResourceManager() != null) {
            List<String> langsToScan = new ArrayList<>();
            langsToScan.add("en_us"); 
            
            if ("zh_tw".equalsIgnoreCase(LAST_SCANNED_LANGUAGE)) {
                langsToScan.add("zh_cn"); 
            }
            
            if (!"en_us".equalsIgnoreCase(LAST_SCANNED_LANGUAGE)) {
                langsToScan.add(LAST_SCANNED_LANGUAGE); 
            }

            for (String lang : langsToScan) {
                for (String namespace : mc.getResourceManager().getNamespaces()) {
                    boolean loaded = isModLoaded(namespace);
                    try {
                        // 使用現代化的 fromNamespaceAndPath 避免字串解析
                        ResourceLocation resLoc = ResourceLocation.fromNamespaceAndPath(namespace, "lang/" + lang + ".json");
                        List<Resource> resources = mc.getResourceManager().getResourceStack(resLoc);

                        for (Resource resource : resources) {
                            if (!PACK_ID.equals(resource.sourcePackId())) {
                                try (InputStream is = resource.open();
                                     InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                                    JsonObject json = JsonParser.parseReader(isr).getAsJsonObject();
                                    json.entrySet().forEach(e -> {
                                        if (e.getValue().isJsonPrimitive()) {
                                            String k = e.getKey();
                                            String v = e.getValue().getAsString();
                                            if (v.isEmpty()) return; 

                                            if (loaded) newPure.put(k, v);
                                            else newOther.put(k, v);
                                            newNamespaceCache.put(k, namespace);
                                        }
                                    });
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        
        pullNamespaceMappingsFromDisk(newNamespaceCache);

        // 原子切換，防止其他線程在重建途中讀取到空資料
        PURE_RESTORE_TABLE.clear();
        PURE_RESTORE_TABLE.putAll(newPure);
        OTHER_RESTORE_TABLE.clear();
        OTHER_RESTORE_TABLE.putAll(newOther);
        ALL_KEY_TO_NAMESPACE_CACHE.clear();
        ALL_KEY_TO_NAMESPACE_CACHE.putAll(newNamespaceCache);
        
        LOGGER.info("[VT Translation] 字典建置完成！目前語言: {}，載入模組字典 {} 筆。", LAST_SCANNED_LANGUAGE, PURE_RESTORE_TABLE.size());
    }

    private static void pullNamespaceMappingsFromDisk(Map<String, String> targetCache) {
        ModList.get().getMods().forEach(mod -> {
            var modFile = ModList.get().getModFileById(mod.getModId());
            if (modFile != null) {
                try {
                    Path rootPath = modFile.getFile().getSecureJar().getRootPath();
                    pullFromAssets(rootPath, targetCache);
                } catch (Exception ignored) {}
            }
        });

        if (Files.exists(RESOURCE_PACKS_ROOT)) {
            try (var stream = Files.list(RESOURCE_PACKS_ROOT)) {
                stream.forEach(packPath -> {
                    String fileName = packPath.getFileName().toString();
                    if (fileName.equals("Auto_Translations") || fileName.startsWith(".")) return;

                    if (Files.isDirectory(packPath)) {
                        pullFromAssets(packPath, targetCache);
                    } else if (fileName.endsWith(".zip") || fileName.endsWith(".jar")) {
                        try (FileSystem zipFs = FileSystems.newFileSystem(packPath, (ClassLoader) null)) {
                            pullFromAssets(zipFs.getPath("/"), targetCache);
                        } catch (Exception ignored) {}
                    }
                });
            } catch (Exception ignored) {}
        }
    }

    private static void pullFromAssets(Path rootPath, Map<String, String> targetCache) {
        Path assetsDir = rootPath.resolve("assets");
        if (!Files.exists(assetsDir) || !Files.isDirectory(assetsDir)) return;

        try (var namespaceStream = Files.list(assetsDir)) {
            namespaceStream.filter(Files::isDirectory).forEach(namespaceDir -> {
                String namespace = namespaceDir.getFileName().toString().toLowerCase(Locale.ROOT);
                Path langDir = namespaceDir.resolve("lang");
                if (Files.exists(langDir) && Files.isDirectory(langDir)) {
                    try (var langStream = Files.list(langDir)) {
                        langStream.filter(p -> p.toString().endsWith(".json")).forEach(langPath -> {
                            try {
                                JsonObject json = readJsonFromPath(langPath);
                                if (json != null) {
                                    json.entrySet().forEach(e -> {
                                        if (e.getValue().isJsonPrimitive()) {
                                            String k = e.getKey();
                                            String v = e.getValue().getAsString();
                                            if (!v.isEmpty()) {
                                                targetCache.putIfAbsent(k, namespace);
                                            }
                                        }
                                    });
                                }
                            } catch (Exception ignored) {}
                        });
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }

    public static String getCustomOverride(String key) {
        try {
            Path singleFile = getExportSingleFile();
            if (Files.exists(singleFile)) {
                JsonObject json = readJsonFromPath(singleFile);
                if (json != null && json.has(key)) {
                    String val = json.get(key).getAsString();
                    String orig = PURE_RESTORE_TABLE.getOrDefault(key, OTHER_RESTORE_TABLE.get(key));
                    if (!val.isEmpty() && !val.equals(orig)) return val;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static boolean hasCustomOverride(String key) {
        return !getCustomOverride(key).isEmpty();
    }

    public static void ensurePackExistsAndScanned() {
        try {
            checkAndRebuildIfNeeded();
            
            if (!Files.exists(RESOURCE_PACK_DIR.resolve("pack.mcmeta"))) {
                Files.createDirectories(EXPORT_ROOT_DIR);
                Files.createDirectories(RESOURCE_PACK_DIR);
                createPackMcmeta();
                executeFullSyncAndExport();
            }
        } catch (Exception ignored) {}
    }

    public static void exportAllTranslations() {
        ensurePackExistsAndScanned();
        executeFullSyncAndExport();
    }

    public static void syncExportToResourcePack() {
        checkAndRebuildIfNeeded();
        executeFullSyncAndExport();
    }

    private static void executeFullSyncAndExport() {
        Map<String, String> loadedData = new TreeMap<>();
        Map<String, String> otherData = new TreeMap<>();
        Map<String, String> unknownData = new TreeMap<>();
        Map<String, String> newData = new TreeMap<>();
        boolean hasError = false;

        Path singleFile = getExportSingleFile();
        hasError |= !readDataSafe(singleFile, loadedData);
        hasError |= !readDataSafe(getExportOtherFile(), otherData);
        hasError |= !readDataSafe(getExportUnknownFile(), unknownData);
        hasError |= !readDataSafe(getExportNewFile(), newData);

        Map<String, String> outLoaded = new TreeMap<>();
        Map<String, String> outOther = new TreeMap<>();
        Map<String, String> outUnknown = new TreeMap<>();
        Map<String, String> outNew = new TreeMap<>();
        Map<String, String> resourcePackData = new TreeMap<>();

        Set<String> processedKeys = new HashSet<>();

        loadedData.forEach((k, v) -> { routeKey(k, v, outLoaded, outOther, outUnknown, outNew, resourcePackData); processedKeys.add(k); });
        otherData.forEach((k, v) -> { routeKey(k, v, outLoaded, outOther, outUnknown, outNew, resourcePackData); processedKeys.add(k); });
        unknownData.forEach((k, v) -> { routeKey(k, v, outLoaded, outOther, outUnknown, outNew, resourcePackData); processedKeys.add(k); });
        newData.forEach((k, v) -> { routeKey(k, v, outLoaded, outOther, outUnknown, outNew, resourcePackData); processedKeys.add(k); });

        PURE_RESTORE_TABLE.forEach((k, v) -> { if (!processedKeys.contains(k)) outNew.put(k, v); });
        OTHER_RESTORE_TABLE.forEach((k, v) -> { if (!processedKeys.contains(k)) outOther.put(k, v); });

        try {
            if (hasError) {
                LOGGER.warn("[VT Translation] 偵測到 JSON 格式損毀，啟動沙盒備份防止覆蓋！");
                writeMasterJson(EXPORT_ROOT_DIR.resolve("new_" + getCurrentLanguage() + ".json"), outLoaded);
                writeMasterJson(EXPORT_ROOT_DIR.resolve("new_other_" + getCurrentLanguage() + ".json"), outOther);
                writeMasterJson(EXPORT_ROOT_DIR.resolve("new_unknown_" + getCurrentLanguage() + ".json"), outUnknown);
                writeMasterJson(EXPORT_ROOT_DIR.resolve("new_new_" + getCurrentLanguage() + ".json"), outNew);
            } else {
                writeMasterJson(singleFile, outLoaded);
                writeMasterJson(getExportOtherFile(), outOther);
                writeMasterJson(getExportUnknownFile(), outUnknown);
                writeMasterJson(getExportNewFile(), outNew);

                distributeToResourcePack(resourcePackData);
                enableAndPrioritizeResourcePack();
            }
            LOGGER.info("[VT Translation] 同步完成！檔案已路由寫入完畢。");
        } catch (Exception ignored) {}
    }

    private static void routeKey(String k, String v, Map<String, String> outLoaded, Map<String, String> outOther, Map<String, String> outUnknown, Map<String, String> outNew, Map<String, String> resourcePackData) {
        String pure = PURE_RESTORE_TABLE.getOrDefault(k, OTHER_RESTORE_TABLE.get(k));
        if (pure == null) pure = k;

        String ns = ALL_KEY_TO_NAMESPACE_CACHE.get(k);
        boolean isLoaded = isModLoaded(ns);
        boolean diff = !v.equals(pure);

        if (diff) {
            if (ns == null) outUnknown.put(k, v);
            else if (isLoaded) outLoaded.put(k, v);
            else outOther.put(k, v);
            
            if (ns != null) resourcePackData.put(k, v);
        } else {
            if (ns == null) {
                outUnknown.put(k, v);
            } else if (!isLoaded) {
                outOther.put(k, v); 
            } else {
                outNew.put(k, v); 
            }
        }
    }

    private static boolean readDataSafe(Path path, Map<String, String> map) {
        if (!Files.exists(path)) return true;
        try {
            JsonObject json = readJsonFromPath(path);
            if (json != null) {
                String currentNamespace = null;
                for (Map.Entry<String, com.google.gson.JsonElement> e : json.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        String k = e.getKey();
                        String v = e.getValue().getAsString();
                        
                        if (k.startsWith("vt.mod.id.")) {
                            currentNamespace = k.substring("vt.mod.id.".length());
                        } else {
                            if (v.isEmpty()) continue;

                            map.put(k, v);
                            if (currentNamespace != null) {
                                ALL_KEY_TO_NAMESPACE_CACHE.put(k, currentNamespace);
                            }
                        }
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void distributeToResourcePack(Map<String, String> modifiedData) {
        Map<String, JsonObject> namespaceToOutput = new HashMap<>();
        modifiedData.forEach((key, val) -> {
            String ns = ALL_KEY_TO_NAMESPACE_CACHE.get(key);
            if (ns == null) ns = "minecraft"; 
            namespaceToOutput.computeIfAbsent(ns, k -> new JsonObject()).addProperty(key, val);
        });

        Path assetsDir = RESOURCE_PACK_DIR.resolve("assets");
        try {
            if (Files.exists(assetsDir)) {
                try (var walk = Files.walk(assetsDir)) {
                    walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
                }
            }
        } catch (Exception ignored) {}

        namespaceToOutput.forEach((namespace, json) -> {
            Path targetPath = assetsDir.resolve(namespace).resolve("lang").resolve(getCurrentLanguage() + ".json");
            try {
                Files.createDirectories(targetPath.getParent());
                writeJson(targetPath, json);
            } catch (Exception ignored) {}
        });
    }

    private static void writeMasterJson(Path outputPath, Map<String, String> data) throws Exception {
        if (data.isEmpty()) {
            Files.deleteIfExists(outputPath);
            return;
        }

        Map<String, Map<String, String>> grouped = new TreeMap<>();
        List<String> unknownKeys = new ArrayList<>();

        data.forEach((k, v) -> {
            String ns = ALL_KEY_TO_NAMESPACE_CACHE.get(k);
            if (ns == null) unknownKeys.add(k);
            else grouped.computeIfAbsent(ns, x -> new TreeMap<>()).put(k, v);
        });

        LinkedHashMap<String, String> orderedMap = new LinkedHashMap<>();
        grouped.forEach((ns, keys) -> {
            orderedMap.put("vt.mod.id." + ns, ns);
            orderedMap.putAll(keys);
        });

        if (!unknownKeys.isEmpty()) {
            orderedMap.put("vt.mod.id.unknown", "unknown");
            Collections.sort(unknownKeys);
            for (String uk : unknownKeys) orderedMap.put(uk, data.get(uk));
        }

        String jsonStr = GSON.toJson(orderedMap);
        jsonStr = jsonStr.replace("  \"vt.mod.id.", "\n  \"vt.mod.id.");
        jsonStr = jsonStr.replaceFirst("\\{\n\n  \"vt.mod.id.", "{\n  \"vt.mod.id.");

        Path parent = outputPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        // 安全暫存原子替換
        Path tempPath = outputPath.resolveSibling(outputPath.getFileName().toString() + ".tmp");
        Files.writeString(tempPath, jsonStr, StandardCharsets.UTF_8);
        Files.move(tempPath, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void enableAndPrioritizeResourcePack() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    PackRepository repo = mc.getResourcePackRepository();
                    Pack targetPack = repo.getPack(PACK_ID);
                    if (targetPack != null) {
                        List<String> selectedIds = new ArrayList<>(repo.getSelectedIds());
                        if (!selectedIds.isEmpty() && selectedIds.get(selectedIds.size() - 1).equals(PACK_ID)) {
                            return; 
                        }
                        selectedIds.remove(PACK_ID);
                        selectedIds.add(PACK_ID);
                        repo.setSelected(selectedIds);
                        mc.options.resourcePacks = new ArrayList<>(selectedIds);
                        mc.options.save();
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private static void createPackMcmeta() throws Exception {
        Path mcmetaPath = RESOURCE_PACK_DIR.resolve("pack.mcmeta");
        JsonObject pack = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("pack_format", 34);
        meta.addProperty("description", "§aVT 自動多語言分流翻譯包");
        pack.add("pack", meta);
        writeJson(mcmetaPath, pack);
    }

    private static JsonObject readJsonFromPath(Path path) throws Exception {
        try (InputStream is = Files.newInputStream(path);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, JsonObject.class);
        }
    }

    private static void writeJson(Path path, JsonObject json) throws Exception {
        Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
        Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void updateSingleKeyAndSave(String key, String value) {
        if (key == null || key.isEmpty()) return;
        try {
            checkAndRebuildIfNeeded();

            String pure = PURE_RESTORE_TABLE.getOrDefault(key, OTHER_RESTORE_TABLE.get(key));
            if (pure == null || pure.isEmpty()) pure = key;

            if (value == null || value.isEmpty()) {
                value = pure;
            }

            Path singleFile = getExportSingleFile();
            Map<String, String> loadedD = new TreeMap<>(); readDataSafe(singleFile, loadedD);
            Map<String, String> otherD = new TreeMap<>(); readDataSafe(getExportOtherFile(), otherD);
            Map<String, String> unknownD = new TreeMap<>(); readDataSafe(getExportUnknownFile(), unknownD);
            Map<String, String> newD = new TreeMap<>(); readDataSafe(getExportNewFile(), newD);

            loadedD.remove(key); 
            otherD.remove(key); 
            unknownD.remove(key); 
            newD.remove(key);

            boolean diff = !value.equals(pure);
            String ns = ALL_KEY_TO_NAMESPACE_CACHE.get(key);
            boolean isLoaded = isModLoaded(ns);

            if (diff) {
                if (ns == null) unknownD.put(key, value);
                else if (isLoaded) loadedD.put(key, value);
                else otherD.put(key, value);
            } else {
                if (ns == null) unknownD.put(key, value);
                else if (!isLoaded) otherD.put(key, value);
                else newD.put(key, value);
            }

            writeMasterJson(singleFile, loadedD);
            writeMasterJson(getExportOtherFile(), otherD);
            writeMasterJson(getExportUnknownFile(), unknownD);
            writeMasterJson(getExportNewFile(), newD);

            Path modPackPath = getModPackLangPath(key);
            JsonObject modJson = Files.exists(modPackPath) ? readJsonFromPath(modPackPath) : new JsonObject();
            if (modJson == null) modJson = new JsonObject();

            if (diff) {
                modJson.addProperty(key, value);
            } else {
                modJson.remove(key);
            }

            if (modJson.size() > 0) {
                Files.createDirectories(modPackPath.getParent());
                writeJson(modPackPath, modJson);
            } else {
                Files.deleteIfExists(modPackPath);
            }
        } catch (Exception ignored) {}
    }

    private static Path getModPackLangPath(String key) {
        String namespace = ALL_KEY_TO_NAMESPACE_CACHE.get(key);
        if (namespace == null) namespace = "minecraft"; 
        return RESOURCE_PACK_DIR.resolve("assets").resolve(namespace).resolve("lang").resolve(getCurrentLanguage() + ".json");
    }

    public static void removeSingleKey(String key) {
        if (key == null || key.isEmpty()) return;
        checkAndRebuildIfNeeded();
        String pureValue = PURE_RESTORE_TABLE.getOrDefault(key, OTHER_RESTORE_TABLE.get(key));
        if (pureValue == null || pureValue.isEmpty()) pureValue = key; 
        updateSingleKeyAndSave(key, pureValue);
    }
}