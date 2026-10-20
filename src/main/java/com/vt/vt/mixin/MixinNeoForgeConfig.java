package com.vt.vt.mixin;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.mrcrayfish.configured.Constants;
import com.mrcrayfish.configured.api.ActionResult;
import com.mrcrayfish.configured.api.ConfigType;
import com.mrcrayfish.configured.api.IConfigEntry;
import com.mrcrayfish.configured.api.IConfigValue;
import com.mrcrayfish.configured.impl.neoforge.NeoForgeConfig;
import com.mrcrayfish.configured.impl.neoforge.NeoForgeListValue;
import com.mrcrayfish.configured.impl.neoforge.NeoForgeValue;
import com.mrcrayfish.configured.util.ConfigHelper;
import com.mrcrayfish.configured.util.NeoForgeConfigHelper;
import com.vt.vt.VTMod;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Set;

@Mixin(value = NeoForgeConfig.class, remap = false)
public abstract class MixinNeoForgeConfig {

    @Shadow @Final protected ModConfig config;
    @Shadow public abstract ConfigType getType();
    @Shadow protected abstract void syncToServer();

    /**
     * @author TerrySet
     * @reason 修復 Configured 覆蓋抹除未修改設定檔項目的 Bug，改用路徑增量更新 (Incremental Update)
     */
    @Overwrite
    public ActionResult update(IConfigEntry entry) {
        CommentedConfig origConfig = NeoForgeConfigHelper.getConfigData(this.config);
        if (origConfig == null) {
            Constants.LOG.error("Unable to update config '{}' as it is not loaded", this.config.getFileName());
            return ActionResult.fail(Component.translatable("configured.gui.update_error.unloaded"));
        }

        Set<IConfigValue<?>> changedValues = ConfigHelper.getChangedValues(entry);

        if (!changedValues.isEmpty()) {
            // 核心修復：僅針對有變動的 Key 進行 set，保留所有未修改的節點與 TOML 註解
            changedValues.forEach(value -> {
                if (value instanceof NeoForgeValue<?> forge) {
                    List<String> path = forge.configValue.getPath();
                    Object valueToSet = value.get();

                    if (forge instanceof NeoForgeListValue<?> forgeList) {
                        List<?> converted = forgeList.getConverted();
                        if (converted != null) {
                            valueToSet = converted;
                        }
                    }

                    if (path != null && valueToSet != null) {
                        origConfig.set(path, valueToSet);
                    }
                }
            });

            NeoForgeConfigHelper.correctConfig(this.config, origConfig);
        }

        if (this.getType() == ConfigType.WORLD_SYNC && !ConfigHelper.isSingleplayer()) {
            if (!ConfigHelper.isPlayingGame()) {
                NeoForgeConfigHelper.saveConfig(this.config);
                NeoForgeConfigHelper.closeConfig(this.config);
            } else {
                this.syncToServer();
            }
        } else if (!changedValues.isEmpty()) {
            Constants.LOG.info("Saving config and sending reloading event for {}", this.config.getFileName());
            NeoForgeConfigHelper.resetConfigCache(this.config);
            NeoForgeConfigHelper.saveConfig(this.config);
        }

        VTMod.LOGGER.info("[VT-TextMatrix] Incremental config update applied for {}", this.config.getFileName());
        return ActionResult.success();
    }
}