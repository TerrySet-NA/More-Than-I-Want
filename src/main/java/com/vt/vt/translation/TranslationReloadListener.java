package com.vt.vt.translation;

import com.vt.vt.util.TextOverrideManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class TranslationReloadListener implements PreparableReloadListener {

    @SuppressWarnings("null")
    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager, 
                                           ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler, 
                                           Executor backgroundExecutor, Executor gameExecutor) {
        return CompletableFuture.runAsync(() -> {
            // 1. 重新實體比對，將 translation_export/zh_tw.json 寫回資源包並置頂
            TranslationExporter.syncExportToResourcePack();

            // 2. 重新自硬碟載入 vt_text_overrides.json 與 vt_item_overrides.json
            TextOverrideManager.load();
        }, backgroundExecutor).thenCompose(barrier::wait);
    }
}