package com.vt.vt.cleaner;

import com.vt.vt.VTMod;
import com.vt.vt.config.ConfigCommon;
import com.vt.vt.network.TrashNetwork;
import com.vt.vt.player.DeathDropEvent;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CleanupManager {
    private static final Map<Integer, SharedTrashInventory> ACTIVE_SESSIONS = new ConcurrentHashMap<>();
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static File getBackupDirectory(MinecraftServer server) {
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("vt_cleaner_backups");
        File file = dir.toFile();
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    /**
     * 伺服器停止時清除記憶體快取
     */
    public static void cleanup() {
        ACTIVE_SESSIONS.clear();
    }

    /**
     * 儲存清理快照：以物品分類雜湊合併，顯著降低 GC 與比對複雜度
     */
    public static synchronized int saveSnapshot(MinecraftServer server, List<ItemEntity> items) {
        if (items.isEmpty()) {
            return 0;
        }

        HolderLookup.Provider lookupProvider = server.registryAccess();
        Map<Item, List<ItemStack>> itemBuckets = new HashMap<>();
        int totalStacks = 0;

        for (ItemEntity entity : items) {
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            // 利用 Map 分組，僅在相同 Item 實例中尋找可堆疊對象
            List<ItemStack> bucket = itemBuckets.computeIfAbsent(stack.getItem(), k -> new ArrayList<>());
            mergeIntoBucket(bucket, stack.copy());
        }

        List<ItemStack> mergedList = new ArrayList<>();
        for (List<ItemStack> bucket : itemBuckets.values()) {
            mergedList.addAll(bucket);
        }

        if (mergedList.isEmpty()) {
            return 0;
        }

        try {
            File dir = getBackupDirectory(server);
            String timestamp = LocalDateTime.now().format(FILE_DATE_FORMAT);
            File backupFile = new File(dir, "backup_" + timestamp + ".dat");

            CompoundTag root = new CompoundTag();
            ListTag listTag = new ListTag();

            for (ItemStack s : mergedList) {
                listTag.add(s.save(lookupProvider));
            }
            root.put("Items", listTag);
            root.putInt("TotalCount", mergedList.size());

            Path targetPath = backupFile.toPath();
            Path tempPath = targetPath.resolveSibling(backupFile.getName() + ".tmp");

            NbtIo.writeCompressed(root, tempPath);
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            rotateBackups(dir);
            ACTIVE_SESSIONS.clear();
        } catch (Exception e) {
            VTMod.LOGGER.error("[VT/Cleaner] Failed to write cleanup snapshot", e);
        }

        return mergedList.size();
    }

    /**
     * 🎯 真正的模擬拾取式一鍵歸位：
     * 將回溯倉庫中屬於該玩家的標籤物品洗掉 FailedOnce，
     * 直接移出並在玩家腳下生成 ItemEntity 原地丟出！
     * 後續所有穿戴、原位歸位、第一次失敗保留、第二次失敗轉普通拾取，
     * 全部無縫交由 DeathDropEvent.onItemPickup 完整接管處理！
     */
    public static synchronized void executeEquipAndRestore(MinecraftServer server, ServerPlayer player, int backupIndex) {
        SharedTrashInventory inv = getOrCreateSharedInventory(server, backupIndex);
        if (inv == null || inv.isEmpty()) {
            return;
        }

        String playerUUID = player.getStringUUID();
        int restoredCount = 0;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                continue;
            }

            net.minecraft.world.item.component.CustomData cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if (cd == null) continue;
            CompoundTag tag = cd.copyTag();

            // 僅處理屬於該玩家的死亡標籤物品
            if (!playerUUID.equals(tag.getString(com.vt.vt.player.DeathDropEvent.TAG_OWNER))) {
                continue;
            }

            // 🎯 核心重點：在丟出前，洗掉之前的 TAG_FAILED_ONCE 標記，賦予全新的一次嘗試機會
            if (tag.contains(com.vt.vt.player.DeathDropEvent.TAG_FAILED_ONCE)) {
                tag.remove(com.vt.vt.player.DeathDropEvent.TAG_FAILED_ONCE);
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            }

            ItemStack dropStack = stack.copy();
            inv.setItem(i, ItemStack.EMPTY); // 從回溯倉庫中扣除移除

            // 🎯 原地拋出掉落物：無初始動能、直接生成在玩家腳下中心，拾取冷卻設為 0 立即觸發判定
            ItemEntity itemEntity = new ItemEntity(player.serverLevel(), player.getX(), player.getY() + 0.1, player.getZ(), dropStack);
            itemEntity.setDeltaMovement(0, 0, 0);
            itemEntity.setPickUpDelay(0); // 設為 0，讓玩家腳下的碰撞箱在當前/下一 Tick 立即嘗試拾取歸位

            player.serverLevel().addFreshEntity(itemEntity);
            restoredCount++;
        }

        if (restoredCount > 0) {
            inv.setChanged(); // 觸發 SharedTrashInventory 的原子存檔
            broadcastInventoryUpdate(server, backupIndex); // 廣播最新清單給所有開啟該回溯介面的玩家
            player.containerMenu.broadcastChanges();

            player.displayClientMessage(
                    Component.translatable("vt.cleaner.message.equip_success", restoredCount),
                    true
            );
        }
    }

    private static void mergeIntoBucket(List<ItemStack> bucket, ItemStack toAdd) {
        for (ItemStack existing : bucket) {
            if (ItemStack.isSameItemSameComponents(existing, toAdd)) {
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space > 0) {
                    int transfer = Math.min(space, toAdd.getCount());
                    existing.grow(transfer);
                    toAdd.shrink(transfer);
                    if (toAdd.isEmpty()) {
                        return;
                    }
                }
            }
        }
        if (!toAdd.isEmpty()) {
            bucket.add(toAdd);
        }
    }

    private static void rotateBackups(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("backup_") && name.endsWith(".dat"));
        if (files == null || files.length == 0) {
            return;
        }

        // 檔名帶有標準 yyyyMMdd_HHmmss 時間戳，按字串由小到大排序即為由舊至新
        Arrays.sort(files, Comparator.comparing(File::getName));

        int max = ConfigCommon.CLEANER_MAX_BACKUP_FILES.get();
        if (files.length > max) {
            for (int i = 0; i < files.length - max; i++) {
                try {
                    Files.deleteIfExists(files[i].toPath());
                } catch (Exception ignored) {}
            }
        }
    }

    public static void broadcastInventoryUpdate(MinecraftServer server, int backupIndex) {
        SharedTrashInventory inv = ACTIVE_SESSIONS.get(backupIndex);
        if (inv == null) {
            return;
        }

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            items.add(inv.getItem(i).copy());
        }

        TrashNetwork.SyncTrashPayload payload = new TrashNetwork.SyncTrashPayload(backupIndex, items);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof TrashRollbackMenu menu && menu.backupIndex == backupIndex) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    public static synchronized SharedTrashInventory getOrCreateSharedInventory(MinecraftServer server, int index) {
        if (ACTIVE_SESSIONS.containsKey(index)) {
            return ACTIVE_SESSIONS.get(index);
        }

        File dir = getBackupDirectory(server);
        File[] files = dir.listFiles((d, name) -> name.startsWith("backup_") && name.endsWith(".dat"));
        if (files == null || files.length == 0) {
            return null;
        }

        // 倒序排序（名稱最大者為最新備份，index 1 對應 index 0）
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));

        int targetIdx = Math.max(1, index) - 1;
        if (targetIdx >= files.length) {
            return null;
        }

        File targetFile = files[targetIdx];
        try {
            CompoundTag root = NbtIo.readCompressed(targetFile.toPath(), NbtAccounter.unlimitedHeap());
            ListTag listTag = root.getList("Items", 10);
            HolderLookup.Provider lookup = server.registryAccess();

            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < listTag.size(); i++) {
                ItemStack.parse(lookup, listTag.getCompound(i)).ifPresent(items::add);
            }

            SharedTrashInventory inv = new SharedTrashInventory(items, targetFile, lookup);
            ACTIVE_SESSIONS.put(index, inv);
            return inv;
        } catch (Exception e) {
            VTMod.LOGGER.error("[VT/Cleaner] Failed to read rollback file: {}", targetFile.getName(), e);
            return null;
        }
    }
}