package com.vt.vt.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.vt.vt.client.KeybindGuardManager;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyMapping.class)
public abstract class KeyMappingSetKeyMixin {

    /**
     * 🎯 當按鍵變更（包含更改組合鍵）時，傳入 KeyMapping 實例進行完整儲存
     */
    @Inject(method = "setKey", at = @At("RETURN"))
    private void onKeyChanged(InputConstants.Key key, CallbackInfo ci) {
        KeyMapping self = (KeyMapping) (Object) this;
        KeybindGuardManager.saveSingleKey(self);
    }
}