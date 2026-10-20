package com.vt.vt.cleaner;

import com.vt.vt.config.ConfigCommon;
import com.vt.vt.mixin.AccessorItemEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TrashCleanerService {
    private static int ticksRemaining = 0;

    // 快取白名單與倒數秒數，消除所有運行時字串建立與 O(N) 遍歷
    private static Set<Item> cachedItemWhitelist = Collections.emptySet();
    private static Set<Integer> cachedCountdownSeconds = Collections.emptySet();

    public static final String ROLLBACK_COMMAND = "/vt rollback";

    /**
     * 伺服器啟動時初始化
     */
    public static void init(MinecraftServer server) {
        reloadConfigCache();
        resetTimer();
    }

    /**
     * 伺服器關閉時釋放快取，避免記憶體洩漏
     */
    public static void cleanup() {
        ticksRemaining = 0;
        cachedItemWhitelist = Collections.emptySet();
        cachedCountdownSeconds = Collections.emptySet();
        CleanupManager.cleanup(); // 釋放回溯目錄快照記憶體
    }

    /**
     * 重設倒數計時（加入防呆，最低間隔為 1 秒）
     */
    public static void resetTimer() {
        int intervalSeconds = Math.max(1, ConfigCommon.CLEANER_INTERVAL_SECONDS.get());
        ticksRemaining = intervalSeconds * 20;
    }

    /**
     * 快取解析：當 Config 載入或熱重載時呼叫此方法
     */
    public static void reloadConfigCache() {
        // 快取倒數秒數 Set
        cachedCountdownSeconds = new HashSet<>(ConfigCommon.CLEANER_COUNTDOWN_SECONDS.get());

        // 快取白名單 Item 實例（指針比較速度極快）
        Set<Item> newWhitelist = new HashSet<>();
        for (String idStr : ConfigCommon.NEVER_DESPAWN_ITEM_IDS.get()) {
            ResourceLocation rl = ResourceLocation.tryParse(idStr.trim());
            if (rl != null) {
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item != Items.AIR) {
                    newWhitelist.add(item);
                }
            }
        }
        cachedItemWhitelist = Set.copyOf(newWhitelist);
    }

    public static void onServerTick(MinecraftServer server) {
        if (!ConfigCommon.CLEANER_ENABLED.get()) return;

        ticksRemaining--;

        // 每秒檢查一次是否需要發送倒數廣播
        if (ticksRemaining % 20 == 0) {
            int secondsLeft = ticksRemaining / 20;
            if (cachedCountdownSeconds.contains(secondsLeft)) {
                broadcastCountdown(server, secondsLeft);
            }
        }

        if (ticksRemaining <= 0) {
            executeCleanup(server);
            resetTimer();
        }
    }

    private static void broadcastCountdown(MinecraftServer server, int seconds) {
        Component message = Component.translatable("vt.cleaner.broadcast.countdown", seconds);
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    public static int executeCleanup(MinecraftServer server) {
        List<ItemEntity> itemsToClean = new ArrayList<>();
        boolean allowCleanUnpickupable = ConfigCommon.CLEAN_UNPICKUPABLE_ITEMS.get();
        boolean allowCleanInfinite = ConfigCommon.CLEAN_INFINITE_LIFETIME_ITEMS.get();

        // 遍歷所有維度的實體
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof ItemEntity itemEntity) || !itemEntity.isAlive()) {
                    continue;
                }

                ItemStack stack = itemEntity.getItem();
                if (stack.isEmpty()) {
                    continue;
                }

                // 1. 白名單防護：O(1) 指針雜湊比對，零字串分配
                if (cachedItemWhitelist.contains(stack.getItem())) {
                    continue;
                }

                // 2. 無敵實體防護
                if (itemEntity.isInvulnerable()) {
                    continue;
                }

                // 3. 模組機制防護：不可被玩家拾取的物品（傳送帶、祭壇、合成中物品等）
                int pickupDelay = ((AccessorItemEntity) itemEntity).vt$getPickupDelay();
                boolean isUnpickupable = pickupDelay >= 32767;
                if (!allowCleanUnpickupable && isUnpickupable) {
                    continue;
                }

                // 4. 無限壽命防護（原版 setUnlimitedLifetime() 將 Age 設為 -32768）
                boolean isInfiniteLifetime = itemEntity.getAge() <= -32768;
                if (!allowCleanInfinite && isInfiniteLifetime) {
                    continue;
                }

                itemsToClean.add(itemEntity);
            }
        }

        int count = itemsToClean.size();
        
        // 儲存回溯快照並移除地表實體
        int snapshotSaved = CleanupManager.saveSnapshot(server, itemsToClean);
        for (ItemEntity e : itemsToClean) {
            e.discard();
        }

        // 發送廣播通知與回溯指令連結
        Component clickLink = Component.translatable("vt.cleaner.broadcast.click_link")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, ROLLBACK_COMMAND))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("vt.cleaner.broadcast.click_hover")))
                );

        Component finalMessage = Component.translatable("vt.cleaner.broadcast.cleaned", count, snapshotSaved)
                .append(clickLink);

        server.getPlayerList().broadcastSystemMessage(finalMessage, false);
        return count;
    }
}