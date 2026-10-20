package com.vt.vt.config;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public class ConfigCommon {
    public static final ModConfigSpec SPEC;

    // --- 連鎖放置設定 (Chain) ---
    public static final ModConfigSpec.BooleanValue ENABLE_CHAIN;
    public static final ModConfigSpec.IntValue MAX_CHAIN_BLOCKS;
    public static final ModConfigSpec.DoubleValue EXHAUSTION_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue BASE_TIME_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue PER_BLOCK_TIME_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue DIAGONAL_DETECTION;
    public static final ModConfigSpec.BooleanValue ALLOW_AIR_PLACE;

    // --- 掉落物清理與回溯設定 (Cleaner) ---
    public static final ModConfigSpec.BooleanValue CLEANER_ENABLED;
    public static final ModConfigSpec.IntValue CLEANER_INTERVAL_SECONDS;
    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> CLEANER_COUNTDOWN_SECONDS;
    public static final ModConfigSpec.IntValue CLEANER_MAX_BACKUP_FILES;
    
    public static final ModConfigSpec.BooleanValue CLEAN_UNPICKUPABLE_ITEMS;
    public static final ModConfigSpec.BooleanValue CLEAN_INFINITE_LIFETIME_ITEMS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NEVER_DESPAWN_ITEM_IDS;
    public static final ModConfigSpec.EnumValue<PermissionLevel> CLEANER_PERMISSION;

    // --- 玩家設定 (Player) ---
    public static final ModConfigSpec.BooleanValue PREVENT_DEATH_DROP_SCATTER;

    // --- 同步設定 (Sync) ---
    public static final ModConfigSpec.BooleanValue ENABLE_CONFIG_SYNC;

    public static final ModConfigSpec.IntValue MAX_PROCESS_UNLOADS_PER_TICK;
    public static final ModConfigSpec.BooleanValue ENABLE_ADAPTIVE_UNLOAD;
    public static final ModConfigSpec.IntValue SWEEP_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue MEMORY_PRESSURE_THRESHOLD;
    public static final ModConfigSpec.IntValue MAX_UNLOAD_BATCH;
    public static final ModConfigSpec.IntValue EXTRA_BUFFER_CHUNKS;

    public enum PermissionLevel {
        HOST("vt.config.common.permission.host", "Host"),
        OP("vt.config.common.permission.op", "OP"),
        ALL("vt.config.common.permission.all", "All"),
        HOST_AND_OP("vt.config.common.permission.host_and_op", "Host & OP");

        private final String translationKey;
        private final String fallback;

        PermissionLevel(String translationKey, String fallback) {
            this.translationKey = translationKey;
            this.fallback = fallback;
        }

        public String getTranslationKey() {
            return translationKey;
        }

        public Component getDisplayName() {
            return Component.translatableWithFallback(translationKey, fallback);
        }

        public boolean hasPermission(CommandSourceStack src) {
            if (!src.isPlayer()) return true;
            ServerPlayer player = src.getPlayer();
            if (player == null) return true;

            MinecraftServer server = src.getServer();
            GameProfile profile = player.getGameProfile();

            boolean isHost = server.isSingleplayerOwner(profile);
            boolean isOp = src.hasPermission(2);

            return switch (this) {
                case ALL -> true;
                case HOST -> isHost || (!server.isDedicatedServer() && isOp);
                case OP -> isOp;
                case HOST_AND_OP -> isHost || isOp;
            };
        }
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // ------------------ Chain 分組 ------------------
        builder.translation("vt.config.common.group.chain").push("chain");
        ENABLE_CHAIN = builder
                .translation("vt.config.common.enableChain")
                .define("enableChain", true);
        MAX_CHAIN_BLOCKS = builder
                .translation("vt.config.common.maxChainBlocks")
                .defineInRange("maxChainBlocks", 64, 1, 1024);
        EXHAUSTION_MULTIPLIER = builder
                .translation("vt.config.common.exhaustionMultiplier")
                .defineInRange("exhaustionMultiplier", 1.0, 0.0, 10.0);
        BASE_TIME_MULTIPLIER = builder
                .translation("vt.config.common.baseTimeMultiplier")
                .defineInRange("baseTimeMultiplier", 1.0, 0.1, 10.0);
        PER_BLOCK_TIME_MULTIPLIER = builder
                .translation("vt.config.common.perBlockTimeMultiplier")
                .defineInRange("perBlockTimeMultiplier", 0.0125, 0.0, 2.0);
        DIAGONAL_DETECTION = builder
                .translation("vt.config.common.diagonalDetection")
                .define("diagonalDetection", true);
        ALLOW_AIR_PLACE = builder
                .translation("vt.config.common.allowAirPlace")
                .define("allowAirPlace", true);
        builder.pop();

        // ------------------ Cleaner 分組 ------------------
        builder.translation("vt.config.common.group.cleaner").push("cleaner");

        CLEANER_ENABLED = builder
                .translation("vt.config.common.cleaner.enabled")
                .define("enabled", true);

        CLEANER_INTERVAL_SECONDS = builder
                .translation("vt.config.common.cleaner.intervalSeconds")
                .defineInRange("intervalSeconds", 300, 10, 86400);

        CLEANER_COUNTDOWN_SECONDS = builder
                .translation("vt.config.common.cleaner.countdownSeconds")
                .defineListAllowEmpty("countdownSeconds", List.of(60, 30, 10, 5, 3, 2, 1), o -> o instanceof Integer);

        CLEANER_MAX_BACKUP_FILES = builder
                .translation("vt.config.common.cleaner.maxBackupFiles")
                .defineInRange("maxBackupFiles", 5, 1, 100);

        CLEAN_UNPICKUPABLE_ITEMS = builder
                .translation("vt.config.common.cleaner.cleanUnpickupableItems")
                .define("cleanUnpickupableItems", false);

        CLEAN_INFINITE_LIFETIME_ITEMS = builder
                .translation("vt.config.common.cleaner.cleanInfiniteLifetimeItems")
                .define("cleanInfiniteLifetimeItems", true);

        NEVER_DESPAWN_ITEM_IDS = builder
                .translation("vt.config.common.cleaner.neverDespawnItemIds")
                .defineListAllowEmpty("neverDespawnItemIds", List.of(), o -> o instanceof String);

        CLEANER_PERMISSION = builder
                .translation("vt.config.common.cleaner.permission")
                .defineEnum("permission", PermissionLevel.HOST_AND_OP);

        builder.pop();

        // ------------------ Player 分組 ------------------
        builder.translation("vt.config.common.group.player").push("player");
        PREVENT_DEATH_DROP_SCATTER = builder
                .translation("vt.config.common.player.preventDeathDropScatter")
                .comment("Prevent player death items from scattering (Neat Death Drops)")
                .define("preventDeathDropScatter", true);
        builder.pop();

        // ------------------ Sync 分組 ------------------
        builder.translation("vt.config.common.group.sync").push("sync");
        ENABLE_CONFIG_SYNC = builder
                .translation("vt.config.common.sync.enableConfigSync")
                .comment("Enable automatic sync for all common configs across connected players")
                .define("enableConfigSync", true);
        builder.pop();

        builder.translation("vt.config.common.group.chunkManagement").push("chunk_management");

        // 🎯 新增：原版 processUnloads 單 Tick 卸載上限配置
        MAX_PROCESS_UNLOADS_PER_TICK = builder
                .translation("vt.config.common.chunkManagement.maxProcessUnloadsPerTick")
                .comment("Max chunks processed in ChunkMap.processUnloads per tick (Vanilla default: 200)")
                .defineInRange("maxProcessUnloadsPerTick", 200, 50, 2048);

        ENABLE_ADAPTIVE_UNLOAD = builder
                .translation("vt.config.common.chunkManagement.enableAdaptiveUnload")
                .comment("Whether to enable adaptive distant chunk demotion sweep")
                .define("enableAdaptiveUnload", true);

        SWEEP_INTERVAL_TICKS = builder
                .translation("vt.config.common.chunkManagement.sweepIntervalTicks")
                .comment("Sweep interval in ticks. Recommended: 200-600 ticks (10-30s)")
                .defineInRange("sweepIntervalTicks", 400, 40, 2400);

        MEMORY_PRESSURE_THRESHOLD = builder
                .translation("vt.config.common.chunkManagement.memoryPressureThreshold")
                .comment("Memory pressure threshold (0.0 - 1.0). Recommended >= 0.90 for ZGC")
                .defineInRange("memoryPressureThreshold", 0.92, 0.50, 0.99);

        MAX_UNLOAD_BATCH = builder
                .translation("vt.config.common.chunkManagement.maxUnloadBatch")
                .comment("Max chunk demotion quota per sweep to prevent I/O spikes")
                .defineInRange("maxUnloadBatch", 64, 16, 256);

        EXTRA_BUFFER_CHUNKS = builder
                .translation("vt.config.common.chunkManagement.extraBufferChunks")
                .comment("Extra buffer chunk radius outside view/simulation distance to prevent chunk trashing")
                .defineInRange("extraBufferChunks", 3, 1, 8);

        builder.pop();

        SPEC = builder.build();
    }
}