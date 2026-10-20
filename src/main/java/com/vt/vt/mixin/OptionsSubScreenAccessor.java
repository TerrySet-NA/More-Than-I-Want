package com.vt.vt.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccessor {

    /**
     * 🎯 透過 Mixin Accessor 讀取 protected 的 lastScreen 欄位
     */
    @Accessor("lastScreen")
    Screen getLastScreen();
}