package com.vt.vt.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.vt.vt.util.TextOverrideManager;
import java.util.List;
import java.util.Optional;

@Mixin(GuiGraphics.class)
public class GuiGraphicsMixin {
    
    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"))
    private void onRenderItemStackTooltip(Font font, ItemStack stack, int x, int y, CallbackInfo ci) {
        if (stack != null && !stack.isEmpty()) {
            TextOverrideManager.lastActiveItemStack = stack;
        }
    }

    @Inject(method = "renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V", at = @At("HEAD"))
    private void onRenderComponentTooltip(Font font, List<Component> text, int x, int y, CallbackInfo ci) {
        if (text != null && !text.isEmpty()) {
            TextOverrideManager.lastActiveTooltip = text;
        }
    }

    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V", at = @At("HEAD"))
    private void onRenderTooltipList(Font font, List<Component> text, Optional<TooltipComponent> imageComponent, int x, int y, CallbackInfo ci) {
        if (text != null && !text.isEmpty()) {
            TextOverrideManager.lastActiveTooltip = text;
        }
    }

    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;II)V", at = @At("HEAD"))
    private void onRenderTooltipSingle(Font font, Component text, int x, int y, CallbackInfo ci) {
        if (text != null) {
            TextOverrideManager.lastActiveTooltip = List.of(text);
        }
    }
}