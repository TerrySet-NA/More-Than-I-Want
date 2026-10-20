package com.vt.vt.mixin;

import com.mrcrayfish.configured.impl.neoforge.NeoForgeValue;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nullable;

@Mixin(value = NeoForgeValue.class, remap = false)
public abstract class MixinNeoForgeValue {

    @Shadow @Final protected ModConfigSpec.ValueSpec valueSpec;
    @Shadow public abstract String getTranslationKey();

    /**
     * @author TerrySet
     * @reason 修復 Configured 2.6.3 的極致邊界 Bug：
     *         當 "Allowed Values: " 或 "Range: " 位於開頭 (index == 0) 時，
     *         原程式碼直接 -1 導致 substring(-1) 崩潰。
     */
    @Overwrite
    public @Nullable Component getComment() {
        String rawComment = this.valueSpec.getComment();
        String key = this.getTranslationKey() + ".tooltip";

        if (!I18n.exists(key)) {
            return rawComment != null ? Component.literal(rawComment) : null;
        } else {
            MutableComponent comment = Component.translatable(key);
            if (rawComment != null) {
                int rangeIndex = rawComment.indexOf("Range: ");
                int allowedValIndex = rawComment.indexOf("Allowed Values: ");

                if (rangeIndex >= 0 || allowedValIndex >= 0) {
                    int targetIndex = Math.max(rangeIndex, allowedValIndex);

                    // 核心修復：防止 targetIndex == 0 時減 1 產生 -1
                    if (targetIndex == 0) {
                        comment.append(Component.literal("\n" + rawComment));
                    } else {
                        comment.append(Component.literal(rawComment.substring(targetIndex - 1)));
                    }
                }
            }
            return comment;
        }
    }
}