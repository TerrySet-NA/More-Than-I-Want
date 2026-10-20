package com.vt.vt.translation;

import com.vt.vt.mixin.ClientLanguageAccessor;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;

import java.util.HashMap;
import java.util.Map;

public class LanguageHotInjector {

    /**
     * 修改翻譯鍵時調用：複製 -> 修改 -> 產生不可變 Map 寫回 ClientLanguage (0ms 記憶體即時生效且 100% 線程安全)
     */
    public static void updateKeyInMemory(String key, String newValue) {
        if (key == null || key.isEmpty()) return;

        Language currentLang = Language.getInstance();
        if (currentLang instanceof ClientLanguage clientLang) {
            ClientLanguageAccessor accessor = (ClientLanguageAccessor) clientLang;
            
            Map<String, String> newStorage = new HashMap<>(accessor.getStorage());

            if (newValue == null || newValue.isEmpty()) {
                newStorage.remove(key);
            } else {
                newStorage.put(key, newValue);
            }

            // 使用不可變 Map 寫回，杜絕其他非同步線程讀取語言時拋出 ConcurrentModificationException
            accessor.setStorage(Map.copyOf(newStorage));
        }
    }
}