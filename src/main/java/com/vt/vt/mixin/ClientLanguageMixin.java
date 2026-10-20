package com.vt.vt.mixin;

import com.vt.vt.util.TextOverrideManager;
import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.regex.Pattern;

@Mixin(ClientLanguage.class)
public class ClientLanguageMixin {

    @Inject(method = "getOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"))
    private void onGetOrDefault(String id, String defaultValue, CallbackInfoReturnable<String> cir) {
        String vanillaResult = cir.getReturnValue();

        if (vanillaResult != null && !vanillaResult.isEmpty() && id != null && !id.startsWith("vt.")) {
            if (!TextOverrideManager.REVERSE_TRANSLATION_MAP.containsKey(vanillaResult)) {
                TextOverrideManager.REVERSE_TRANSLATION_MAP.put(vanillaResult, id);

                if (vanillaResult.contains("%")) {
                    String cleanTemplate = vanillaResult.replaceAll("§.", "").trim();
                    String literalPart = cleanTemplate.replaceAll("%[0-9]*\\$[a-zA-Z]", "").replaceAll("%[a-zA-Z]", "").trim();

                    if (!literalPart.isEmpty()) {
                        try {
                            String regex = Pattern.quote(cleanTemplate)
                                    .replaceAll("%[0-9]*\\$[a-zA-Z]", "\\\\E.*\\\\Q")
                                    .replaceAll("%[a-zA-Z]", "\\\\E.*\\\\Q");
                            regex = "^" + regex + "$";
                            regex = regex.replace("\\Q\\E", "");

                            TextOverrideManager.addWildcardTemplate(vanillaResult, id, regex);
                        } catch (Exception ignored) {
                            // 防禦特殊語系字元導致正規表示式編譯崩潰
                        }
                    }
                }
            }
        }
    }
}