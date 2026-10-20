package com.vt.vt.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class FixEarlyMouseInputMixin {

    /**
     * @author TerrySet
     * @reason 防止玩家在遊戲完全加載好（或正在渲染錯誤/恢復畫面）之前點擊視窗，
     * 導致未註冊完成的 KeyMapping (如 Quark AutoWalk/Hotbar) 被提前呼叫引發 NPE。
     */
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void onEarlyMousePress(long window, int button, int action, int mods, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        
        // 如果 Minecraft 還沒 New 出來，或者畫面還卡在加載/恐慌恢復 Overlay 階段，直接攔截滑鼠輸入
        if (mc == null || mc.getOverlay() != null) {
            ci.cancel();
        }
    }
}