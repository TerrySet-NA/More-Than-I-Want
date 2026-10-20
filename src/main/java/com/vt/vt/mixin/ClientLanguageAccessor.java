package com.vt.vt.mixin;

import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ClientLanguage.class)
public interface ClientLanguageAccessor {
    @Accessor("storage")
    Map<String, String> getStorage();

    // 🎯 加入 @Mutable 允許寫回 final/不可變欄位
    @Accessor("storage")
    @Mutable
    void setStorage(Map<String, String> storage);
}