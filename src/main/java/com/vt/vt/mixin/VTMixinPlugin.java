package com.vt.vt.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
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

    // Mod ID 常數定義
    private static final String NEOFORGE_MOD_ID = "neoforge";
    private static final String CONFIGURED_MOD_ID = "configured";
    private static final String FORGE_CONFIG_API_PORT_MOD_ID = "forgeconfigapiport";
    private static final String TARGET_COOKERY_MOD_ID = "kaleidoscope_cookery";
    private static final String TARGET_COMPAT_MOD_ID = "kaleidoscope_compat";

    // 完全匹配的目標 Jar 檔名
    private static final String TARGET_CONFIGURED_FILE = "configured-neoforge-1.21.1-2.6.3.jar";
    private static final String TARGET_FORGE_FILE = "ForgeConfigAPIPort-v21.1.6-1.21.1-NeoForge.jar";
    private static final String TARGET_COOKERY_FILE = "kaleidoscopecookery-1.6.0-neoforge+mc1.21.1.jar";
    private static final String TARGET_COMPAT_FILE = "kaleidoscope_compat-2.9.7-neoforge+mc1.21.1-Patch.jar";

    private static final String TARGET_MILLSTONE_CLASS = "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity";

    private static final ArtifactVersion BREAKING_NEOFORGE_VERSION = new DefaultArtifactVersion("21.1.26");

    /**
     * 檢查當前 NeoForge 版本是否大於等於 21.1.26
     */
    private boolean isNeoForgeAboveOrEqual26() {
        Optional<? extends ModInfo> neoMod = FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> mod.getModId().equals(NEOFORGE_MOD_ID))
                .findFirst();

        if (neoMod.isEmpty()) {
            LOGGER.warn("[VT-Compat] Failed to locate NeoForge mod info in loading list.");
            return false;
        }

        String rawVer = neoMod.get().getVersion().toString();
        if (rawVer.startsWith("1.21.1-")) {
            rawVer = rawVer.substring("1.21.1-".length());
        }

        ArtifactVersion currentVersion = new DefaultArtifactVersion(rawVer);
        return currentVersion.compareTo(BREAKING_NEOFORGE_VERSION) >= 0;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("FixFabricAttachmentTaskMixin")) {
            return shouldApplyFabricAttachmentsFix();
        }

        if (mixinClassName.contains("MixinNeoForge")) {
            return shouldApplyConfiguredMixin();
        }

        if (mixinClassName.contains("FixKaleidoscopeMillstoneMixin")) {
            return shouldApplyMillstoneHotfix();
        }

        return true;
    }

    private boolean shouldApplyFabricAttachmentsFix() {
        // 1. 版本門檻：NeoForge >= 21.1.26
        if (!isNeoForgeAboveOrEqual26()) {
            return false;
        }

        // 2. 只有在載入了這顆內包 fabric_networking 的 ForgeConfigAPIPort 時才啟動攔截
        Optional<? extends ModInfo> forgePortOpt = FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> mod.getModId().equals(FORGE_CONFIG_API_PORT_MOD_ID))
                .findFirst();

        if (forgePortOpt.isEmpty()) {
            return false;
        }

        String forgePortFile = forgePortOpt.get().getOwningFile().getFile().getFileName();
        boolean isTargetForgePort = TARGET_FORGE_FILE.equalsIgnoreCase(forgePortFile);

        if (isTargetForgePort) {
            LOGGER.info("[VT-Compat] Target ForgeConfigAPIPort detected on NeoForge >= 21.1.26. Applying FixFabricAttachmentTaskMixin.");
            return true;
        }

        return false;
    }

    /**
     * 🎯 針對 Configured 模組本體的 TextMatrix / GUI 修復
     */
    private boolean shouldApplyConfiguredMixin() {
        Optional<? extends ModInfo> configuredOpt = FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> mod.getModId().equals(CONFIGURED_MOD_ID))
                .findFirst();

        if (configuredOpt.isEmpty()) {
            return false;
        }

        String configuredFile = configuredOpt.get().getOwningFile().getFile().getFileName();
        boolean isTargetConfigured = TARGET_CONFIGURED_FILE.equalsIgnoreCase(configuredFile);

        if (isTargetConfigured) {
            LOGGER.info("[VT-TextMatrix] Detected exact Configured file [{}]. Applying MixinNeoForgeConfig safely.", configuredFile);
            return true;
        }

        LOGGER.info("[VT-TextMatrix] Configured file mismatch (Found: {}, Expected: {}). Skipping.", configuredFile, TARGET_CONFIGURED_FILE);
        return false;
    }

    /**
     * 🎯 研磨機衝突修復（完全精確比對 Cookery 與 Compat 兩者檔名）
     */
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

        String cookeryFile = cookeryOpt.get().getOwningFile().getFile().getFileName();
        String compatFile = compatOpt.get().getOwningFile().getFile().getFileName();

        boolean isTargetCookery = TARGET_COOKERY_FILE.equalsIgnoreCase(cookeryFile);
        boolean isTargetCompat = TARGET_COMPAT_FILE.equalsIgnoreCase(compatFile);

        if (isTargetCookery && isTargetCompat) {
            LOGGER.warn("[VT-Compat] Detected collision between exact files [{}] and [{}]. Applying FixKaleidoscopeMillstoneMixin.",
                    cookeryFile, compatFile);
            return true;
        }

        LOGGER.info("[VT-Compat] Mod files mismatch target collision pair (Cookery: {} [Exp: {}], Compat: {} [Exp: {}]). Skipping hotfix.",
                cookeryFile, TARGET_COOKERY_FILE, compatFile, TARGET_COMPAT_FILE);
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
        if (TARGET_MILLSTONE_CLASS.equals(targetClassName) && shouldApplyMillstoneHotfix()) {
            if (targetClass != null && targetClass.methods != null) {
                for (MethodNode method : targetClass.methods) {
                    if ("canBindEntity".equals(method.name) && "(Lnet/minecraft/world/entity/LivingEntity;)Z".equals(method.desc)) {
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