package com.vt.vt.mixin;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = MillstoneBlockEntity.class, priority = 500)
public abstract class FixKaleidoscopeMillstoneMixin {

    @Shadow(remap = false)
    public abstract boolean canBindEntity(LivingEntity entity);

    /**
     * 補回 canBindEntity(Mob) 舊簽名
     */
    public boolean canBindEntity(Mob mob) {
        return this.canBindEntity((LivingEntity) mob);
    }
}