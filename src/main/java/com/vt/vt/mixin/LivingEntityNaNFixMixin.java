package com.vt.vt.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityNaNFixMixin {

    /**
     * 攔截血量設定：防止 NaN 與正負無限大破壞生物實體狀態
     */
    @ModifyVariable(method = "setHealth", at = @At("HEAD"), argsOnly = true)
    private float sanitizeHealth(float health) {
        if (Float.isNaN(health) || Float.isInfinite(health)) {
            return 0.0F;
        }
        return health;
    }

    /**
     * 攔截傷害數值：防禦除零、負傷害與無限大算爆問題
     */
    @ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true)
    private float sanitizeDamageAmount(float amount) {
        if (Float.isNaN(amount) || Float.isInfinite(amount) || amount < 0.0F) {
            return 0.0F;
        }
        return amount;
    }
}