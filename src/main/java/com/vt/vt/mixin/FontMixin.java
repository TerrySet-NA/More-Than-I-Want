package com.vt.vt.mixin;

import com.vt.vt.client.TextEditScreen;
import com.vt.vt.client.TooltipSelectionScreen;
import com.vt.vt.util.TextOverrideManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public class FontMixin {

    @ModifyVariable(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true)
    private String onWidthString(String text) {
        return TextOverrideManager.getFastPathText(text);
    }

    @ModifyVariable(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I", at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence onWidthCharSequence(FormattedCharSequence text) {
        if (text == null) return null;

        StringBuilder sb = new StringBuilder();
        text.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });

        String plainText = sb.toString();
        String replacedText = TextOverrideManager.getFastPathText(plainText);

        if (!replacedText.equals(plainText)) {
            final Style[] baseStyle = {Style.EMPTY};
            text.accept((index, style, codePoint) -> {
                baseStyle[0] = style;
                return false;
            });
            return FormattedCharSequence.forward(replacedText, baseStyle[0]);
        }
        return text;
    }

    @ModifyVariable(
        method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private String onDrawString(String text, String originalText, float x, float y, int color, boolean dropShadow, Matrix4f matrix, net.minecraft.client.renderer.MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor, int packedLightCoords) {
        if (originalText == null || originalText.isEmpty()) return text;

        String replaced = TextOverrideManager.getFastPathText(originalText);
        vt$checkFontRaycast(replaced, originalText, x, y, matrix);
        return replaced;
    }

    @ModifyVariable(
        method = "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
        at = @At("HEAD"),
        argsOnly = true
    )
    private FormattedCharSequence onDrawCharSequence(FormattedCharSequence text, FormattedCharSequence originalText, float x, float y, int color, boolean dropShadow, Matrix4f matrix, net.minecraft.client.renderer.MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor, int packedLightCoords) {
        if (originalText == null) return text;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen instanceof TextEditScreen || mc.screen instanceof TooltipSelectionScreen) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        originalText.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });

        String plainText = sb.toString();
        if (plainText.replaceAll("§.", "").trim().isEmpty()) {
            return text;
        }

        String replacedText = TextOverrideManager.getFastPathText(plainText);
        if (!replacedText.equals(plainText)) {
            final Style[] baseStyle = {Style.EMPTY};
            originalText.accept((index, style, codePoint) -> {
                baseStyle[0] = style;
                return false;
            });
            FormattedCharSequence replacedSeq = FormattedCharSequence.forward(replacedText, baseStyle[0]);
            vt$checkFontRaycast(replacedText, plainText, x, y, matrix);
            return replacedSeq;
        }

        vt$checkFontRaycast(plainText, plainText, x, y, matrix);
        return text;
    }

    /**
     * 高效文字射線檢測：純純量計算，消除每秒數萬次 new Vector4f 的記憶體配置
     */
    private void vt$checkFontRaycast(String currentRendered, String originalKey, float x, float y, Matrix4f matrix) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.mouseHandler == null || mc.getWindow() == null) return;

        try {
            float screenX = (matrix != null) ? (matrix.m00() * x + matrix.m10() * y + matrix.m30()) : x;
            float screenY = (matrix != null) ? (matrix.m01() * x + matrix.m11() * y + matrix.m31()) : y;

            double guiScale = mc.getWindow().getGuiScale();
            double mouseX = mc.mouseHandler.xpos() / guiScale;
            double mouseY = mc.mouseHandler.ypos() / guiScale;

            Font fontInstance = (Font) (Object) this;
            int textWidth = fontInstance.width(currentRendered);

            if (mouseX >= screenX && mouseX <= screenX + textWidth &&
                mouseY >= screenY && mouseY <= screenY + fontInstance.lineHeight) {

                String trulyOriginal = TextOverrideManager.getTrulyOriginalText(originalKey);
                String nativeKey = TextOverrideManager.findNativeKeyForRenderedText(trulyOriginal);

                if (nativeKey != null) {
                    TextOverrideManager.hoveredKey = nativeKey;
                    TextOverrideManager.hoveredTextHasNativeKey = true;
                    TextOverrideManager.hoveredTextString = TextOverrideManager.getRawTemplateOfKey(nativeKey);
                } else {
                    TextOverrideManager.hoveredKey = trulyOriginal;
                    TextOverrideManager.hoveredTextHasNativeKey = false;
                    TextOverrideManager.hoveredTextString = trulyOriginal;
                }
            }
        } catch (Throwable ignored) {}
    }
}