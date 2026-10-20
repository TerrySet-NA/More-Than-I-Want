package com.vt.vt.mixin;

import com.vt.vt.config.ConfigSyncManager;
import com.vt.vt.config.UniversalConfigTracker;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Supplier;

@Mixin(value = ModConfigSpec.ConfigValue.class, remap = false)
public abstract class MixinConfigValue<T> {

    @Shadow @Final ModConfigSpec spec;
    @Shadow @Final List<String> path;
    @Shadow @Final Supplier<T> defaultSupplier;

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void vt$onGet(CallbackInfoReturnable<T> cir) {
        // 1. 最高優先級快速中斷：未啟動覆寫時（如單人、房主、伺服端）直接放行，保護全遊戲所有模組 Config 讀取效能
        if (!ConfigSyncManager.isOverrideActive()) {
            return;
        }

        // 2. 僅對 COMMON 設定進行覆寫攔截
        if (!UniversalConfigTracker.isCommonSpec(this.spec)) {
            return;
        }

        try {
            T override = ConfigSyncManager.getOverride(this.spec, this.path, this.defaultSupplier);
            if (override != null) {
                cir.setReturnValue(override);
            }
        } catch (Throwable ignored) {}
    }
}