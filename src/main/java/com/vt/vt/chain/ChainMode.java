package com.vt.vt.chain;

import net.minecraft.network.chat.Component;

public enum ChainMode {
    MINE_FREE("vt.mode.mine_free", false),
    MINE_DOWN("vt.mode.mine_down", false),
    MINE_3X3("vt.mode.mine_3x3", false),
    MINE_LINE("vt.mode.mine_line", false),
    PLACE_PLANE("vt.mode.place_plane", true),
    PLACE_SURFACE("vt.mode.place_surface", true),
    PLACE_FILL("vt.mode.place_fill", true),
    PLACE_3X3("vt.mode.place_3x3", true),
    PLACE_LINE("vt.mode.place_line", true);

    private final String translationKey;
    private final boolean isPlace;

    ChainMode(String translationKey, boolean isPlace) {
        this.translationKey = translationKey;
        this.isPlace = isPlace;
    }

    public String getTranslationKey() { 
        return translationKey; 
    }

    public Component getDisplayName() {
        return Component.translatable(translationKey);
    }

    public boolean isPlace() { 
        return isPlace; 
    }

    public boolean isMine() { 
        return !isPlace; 
    }
}