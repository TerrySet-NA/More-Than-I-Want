package com.vt.vt.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModInfo;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class VTMixinPlugin implements IMixinConfigPlugin {

    public static final Logger LOGGER = LogUtils.getLogger();

    // Configured 目標版本常數
    private static final String CONFIGURED_MOD_ID = "configured";
    private static final String TARGET_CONFIGURED_VERSION = "2.6.3";
    private static final String TARGET_CONFIGURED_FILE = "configured-neoforge-1.21.1-2.6.3";

    // 萬花筒衝突目標版本常數
    private static final String TARGET_COOKERY_MOD_ID = "kaleidoscope_cookery";
    private static final String TARGET_COMPAT_MOD_ID = "kaleidoscope_compat";
    private static final String TARGET_COOKERY_VERSION = "1.6.0";
    private static final String TARGET_COMPAT_VERSION = "2.9.7-neoforge+mc1.21.1-Patch.jar";

    private static final String TARGET_MILLSTONE_CLASS = "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("MixinNeoForge")) {
            return shouldApplyConfiguredMixin();
        }

        if (mixinClassName.contains("FixKaleidoscopeMillstoneMixin")) {
            return shouldApplyMillstoneHotfix();
        }

        return true;
    }

    private boolean shouldApplyConfiguredMixin() {
        Optional<? extends ModInfo> configuredOpt = FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> mod.getModId().equals(CONFIGURED_MOD_ID))
                .findFirst();

        if (configuredOpt.isEmpty()) {
            LOGGER.info("[VT-TextMatrix] Configured mod NOT present. Skipping MixinNeoForgeConfig.");
            return false;
        }

        ModInfo modInfo = configuredOpt.get();
        String versionStr = modInfo.getVersion().toString();
        String fileName = modInfo.getOwningFile().getFile().getFileName();

        boolean isTargetConfigured = versionStr.contains(TARGET_CONFIGURED_VERSION)
                || fileName.contains(TARGET_CONFIGURED_FILE);

        if (isTargetConfigured) {
            LOGGER.info("[VT-TextMatrix] Detected target Configured mod [{}] (Version: {}). Applying MixinNeoForgeConfig safely.", fileName, versionStr);
            return true;
        }

        LOGGER.info("[VT-TextMatrix] Configured present but version mismatch (File: {}, Version: {}). Skipping MixinNeoForgeConfig.", fileName, versionStr);
        return false;
    }

    private boolean shouldApplyMillstoneHotfix() {
        var modList = FMLLoader.getLoadingModList().getMods();

        Optional<? extends ModInfo> cookeryOpt = modList.stream()
                .filter(mod -> mod.getModId().equals(TARGET_COOKERY_MOD_ID))
                .findFirst();

        Optional<? extends ModInfo> compatOpt = modList.stream()
                .filter(mod -> mod.getModId().equals(TARGET_COMPAT_MOD_ID))
                .findFirst();

        if (cookeryOpt.isEmpty() || compatOpt.isEmpty()) {
            return false;
        }

        ModInfo cookeryInfo = cookeryOpt.get();
        ModInfo compatInfo = compatOpt.get();

        String cookeryVer = cookeryInfo.getVersion().toString();
        String cookeryFile = cookeryInfo.getOwningFile().getFile().getFileName();

        String compatVer = compatInfo.getVersion().toString();
        String compatFile = compatInfo.getOwningFile().getFile().getFileName();

        boolean isTargetCookery = cookeryVer.contains(TARGET_COOKERY_VERSION) 
                || cookeryFile.contains(TARGET_COOKERY_VERSION);

        boolean isTargetCompat = compatVer.contains(TARGET_COMPAT_VERSION) 
                || compatFile.contains(TARGET_COMPAT_VERSION)
                || compatFile.contains("8920105");

        if (isTargetCookery && isTargetCompat) {
            LOGGER.warn("[VT-Compat] Detected collision between [{}] and [{}]. Applying FixKaleidoscopeMillstoneMixin.", cookeryFile, compatFile);
            return true;
        }

        LOGGER.info("[VT-Compat] Mod versions mismatch target collision pair (Cookery: {}, Compat: {}). Skipping hotfix.", cookeryFile, compatFile);
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 🎯 當目標是 MillstoneBlockEntity 且觸發衝突版本時，動態修正方法描述符
        if (TARGET_MILLSTONE_CLASS.equals(targetClassName) && shouldApplyMillstoneHotfix()) {
            if (targetClass != null && targetClass.methods != null) {
                for (MethodNode method : targetClass.methods) {
                    // 若存在新版簽名 canBindEntity(Lnet/minecraft/world/entity/LivingEntity;)Z
                    if ("canBindEntity".equals(method.name) && "(Lnet/minecraft/world/entity/LivingEntity;)Z".equals(method.desc)) {
                        // 暫時重命名原版方法為 canBindEntity$original，避免 kaleidoscope_compat 的模糊匹配碰撞
                        method.name = "canBindEntity$internal";
                        LOGGER.info("[VT-Compat] Renamed LivingEntity overload to avoid Mixin descriptor collision.");
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 🎯 在 kaleidoscope_compat 注入完成後，把原版方法名還原，確保原版邏輯完全正常
        if (TARGET_MILLSTONE_CLASS.equals(targetClassName) && shouldApplyMillstoneHotfix()) {
            if (targetClass != null && targetClass.methods != null) {
                for (MethodNode method : targetClass.methods) {
                    if ("canBindEntity$internal".equals(method.name)) {
                        method.name = "canBindEntity";
                        LOGGER.info("[VT-Compat] Restored LivingEntity overload name back to canBindEntity.");
                        break;
                    }
                }
            }
        }
    }
}