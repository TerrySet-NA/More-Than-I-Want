package com.vt.vt.mixin;

import com.vt.vt.VTMod;
import com.vt.vt.config.ConfigCommon;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BooleanSupplier;

@Mixin(ChunkMap.class)
public abstract class AggressiveChunkUnloadMixin {

    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
    @Shadow @Final private LongSet toDrop;

    private int tickCounter;
    private int sweepCount;

    private int[] playerAABBs = new int[64 * 4];
    private int spawnMinX, spawnMaxX, spawnMinZ, spawnMaxZ;
    private boolean hasSpawnProtection;

    @ModifyConstant(method = "processUnloads", constant = @Constant(intValue = 200), require = 0)
    private int vt$modifyProcessUnloadLimit(int original) {
        // 若配置尚未加載完成（如早期啟動），fallback 回原版 200
        if (ConfigCommon.MAX_PROCESS_UNLOADS_PER_TICK == null) {
            return original;
        }
        return ConfigCommon.MAX_PROCESS_UNLOADS_PER_TICK.get();
    }

    @Inject(method = "processUnloads", at = @At("HEAD"))
    private void vt$smoothChunkTicketDemotion(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        if (!ConfigCommon.ENABLE_ADAPTIVE_UNLOAD.get()) {
            return;
        }

        // 1. 若待寫盤佇列已有積壓，直接讓道給 I/O 線程，絕不加劇磁碟負載
        if (toDrop.size() > 128) {
            return;
        }

        int interval = ConfigCommon.SWEEP_INTERVAL_TICKS.get();
        if (++tickCounter % interval != 0) {
            return;
        }
        if (tickCounter >= 24000) tickCounter = 0;

        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long usedMem = runtime.totalMemory() - runtime.freeMemory();
        float memPressure = (float) usedMem / maxMem;

        // 未達到設定的記憶體壓力門檻時，全權交由原版狀態機自然輪轉
        if (memPressure < ConfigCommon.MEMORY_PRESSURE_THRESHOLD.get().floatValue()) {
            return;
        }

        List<ServerPlayer> players = level.players();
        int playerCount = players.size();
        if (playerCount == 0 || updatingChunkMap.isEmpty()) {
            return;
        }

        // 2. 建立安全 AABB 緩衝區（額外保護圈防抖動）
        int viewDist = level.getServer().getPlayerList().getViewDistance();
        int simDist = level.getServer().getPlayerList().getSimulationDistance();
        int threshold = Math.max(viewDist, simDist) + ConfigCommon.EXTRA_BUFFER_CHUNKS.get();

        int requiredArraySize = playerCount << 2;
        if (playerAABBs.length < requiredArraySize) {
            playerAABBs = new int[requiredArraySize << 1];
        }

        for (int i = 0; i < playerCount; i++) {
            ServerPlayer sp = players.get(i);
            int pcx = sp.getBlockX() >> 4;
            int pcz = sp.getBlockZ() >> 4;
            int base = i << 2;
            playerAABBs[base]     = pcx - threshold;
            playerAABBs[base + 1] = pcx + threshold;
            playerAABBs[base + 2] = pcz - threshold;
            playerAABBs[base + 3] = pcz + threshold;
        }

        updateSpawnProtection();

        int quota = ConfigCommon.MAX_UNLOAD_BATCH.get();
        LongSet forcedChunks = level.getForcedChunks();
        int demotedThisSweep = 0;

        ObjectIterator<Long2ObjectMap.Entry<ChunkHolder>> iterator =
                updatingChunkMap.long2ObjectEntrySet().fastIterator();

        while (iterator.hasNext() && demotedThisSweep < quota) {
            Long2ObjectMap.Entry<ChunkHolder> entry = iterator.next();
            long posKey = entry.getLongKey();

            if (toDrop.contains(posKey) || forcedChunks.contains(posKey)) {
                continue;
            }

            ChunkHolder holder = entry.getValue();

            // 若該區塊層級 <= 33，代表仍有有效實體或模擬票券，嚴格避開
            if (holder.getTicketLevel() <= 33) {
                continue;
            }

            int chunkX = ChunkPos.getX(posKey);
            int chunkZ = ChunkPos.getZ(posKey);

            if (hasSpawnProtection &&
                chunkX >= spawnMinX && chunkX <= spawnMaxX &&
                chunkZ >= spawnMinZ && chunkZ <= spawnMaxZ) {
                continue;
            }

            boolean insideAnyPlayer = false;
            for (int i = 0; i < playerCount; i++) {
                int base = i << 2;
                if (chunkX >= playerAABBs[base] && chunkX <= playerAABBs[base + 1] &&
                    chunkZ >= playerAABBs[base + 2] && chunkZ <= playerAABBs[base + 3]) {
                    insideAnyPlayer = true;
                    break;
                }
            }

            if (!insideAnyPlayer) {
                // 🎯 核心正解：不呼叫 scheduleUnload！
                // 透過 DistanceManager 移除該位置的暫時性票券，促使 ticketLevel 降到 45 以上
                // 讓 DistanceManager 在下一輪自然排入 toDrop，維持 C2ME 與非同步狀態機完整一致
                ChunkPos chunkPos = new ChunkPos(posKey);
                this.level.getChunkSource().removeRegionTicket(
                        net.minecraft.server.level.TicketType.UNKNOWN,
                        chunkPos,
                        33,
                        chunkPos
                );
                demotedThisSweep++;
            }
        }
        sweepCount++;

        if (sweepCount % 10 == 0 && demotedThisSweep > 0) {
            VTMod.LOGGER.info("[VT/Chunk] Smooth demote: {} chunks stepped down (Mem: {}%), active: {}, dropQueue: {}",
                    demotedThisSweep, (int) (memPressure * 100), updatingChunkMap.size(), toDrop.size());
        }
    }

    private void updateSpawnProtection() {
        if (level.dimension() != Level.OVERWORLD) {
            hasSpawnProtection = false;
            return;
        }

        int spawnRadius = level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS);
        if (spawnRadius <= 0) {
            hasSpawnProtection = false;
            return;
        }

        hasSpawnProtection = true;
        BlockPos spawnPos = level.getSharedSpawnPos();
        int scx = spawnPos.getX() >> 4;
        int scz = spawnPos.getZ() >> 4;
        this.spawnMinX = scx - spawnRadius;
        this.spawnMaxX = scx + spawnRadius;
        this.spawnMinZ = scz - spawnRadius;
        this.spawnMaxZ = scz + spawnRadius;
    }
}