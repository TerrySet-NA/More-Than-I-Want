package com.vt.vt.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ConfigClient {
    public static final ModConfigSpec SPEC;

    // --- 連鎖挖掘設定 (Chain) ---
    public static final ModConfigSpec.BooleanValue HOLD_TO_ACTIVATE;
    public static final ModConfigSpec.BooleanValue HOLD_TO_TOGGLE_CATEGORY;
    public static final ModConfigSpec.BooleanValue USE_BLOCK_FACE;
    public static final ModConfigSpec.BooleanValue PREVENT_TOOL_BREAK;
    public static final ModConfigSpec.BooleanValue SPAWN_BREAK_PARTICLES;
    public static final ModConfigSpec.BooleanValue SINGLE_KEY_MODE;
    public static final ModConfigSpec.BooleanValue REVERSE_KEYS;
    public static final ModConfigSpec.IntValue MAX_CHAIN_BLOCKS;
    public static final ModConfigSpec.BooleanValue AXIS_MODES_USE_BLOCK_FACE;

    // --- 按鍵防護設定 (Keybind Guard) ---
    public static final ModConfigSpec.BooleanValue AUTO_RESTORE_KEYBINDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // ------------------ Chain 分組 ------------------
        builder.translation("vt.config.client.group.chain").push("chain");
        HOLD_TO_ACTIVATE = builder
                .translation("vt.config.client.holdToActivate")
                .define("holdToActivate", true);

        HOLD_TO_TOGGLE_CATEGORY = builder
                .translation("vt.config.client.holdToToggleCategory")
                .define("holdToToggleCategory", true);

        USE_BLOCK_FACE = builder
                .translation("vt.config.client.useBlockFace")
                .define("useBlockFace", false);

        PREVENT_TOOL_BREAK = builder
                .translation("vt.config.client.preventToolBreak")
                .define("preventToolBreak", true);

        SPAWN_BREAK_PARTICLES = builder
                .translation("vt.config.client.spawnBreakParticles")
                .define("spawnBreakParticles", false);

        SINGLE_KEY_MODE = builder
                .translation("vt.config.client.singleKeyMode")
                .define("singleKeyMode", true);

        REVERSE_KEYS = builder
                .translation("vt.config.client.reverseKeys")
                .define("reverseKeys", false);

        MAX_CHAIN_BLOCKS = builder
                .translation("vt.config.client.localMaxChainBlocks")
                .defineInRange("localMaxChainBlocks", 64, 1, 1024);

        AXIS_MODES_USE_BLOCK_FACE = builder
                .translation("vt.config.client.axisModesUseBlockFace")
                .define("axisModesUseBlockFace", true);
        builder.pop();

        // ------------------ Keybind Guard 分組 ------------------
        builder.translation("vt.config.client.group.keybind").push("keybind");
        AUTO_RESTORE_KEYBINDS = builder
                .translation("vt.config.client.autoRestoreKeybinds")
                .comment("Whether to automatically restore keybinds from vt_keybinds.json on game startup")
                .define("autoRestoreKeybinds", true); // 預設為 false，可依需求改為 true
        builder.pop();

        SPEC = builder.build();
    }
}