package com.vt.vt.chain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;

public class ChainBFS {

    public static Set<BlockPos> findConnectedBlocks(Level level, BlockPos startPos, int maxCount) {
        Set<BlockPos> result = new LinkedHashSet<>();
        if (!level.isLoaded(startPos) || maxCount <= 0) return result;

        Queue<BlockPos> queue = new ArrayDeque<>();
        BlockState startState = level.getBlockState(startPos);
        if (startState.isAir()) return result;

        Block targetBlock = startState.getBlock();

        queue.add(startPos);
        result.add(startPos);

        while (!queue.isEmpty() && result.size() < maxCount) {
            BlockPos current = queue.poll();

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        BlockPos neighbor = current.offset(dx, dy, dz);
                        // 防護：未載入的 Chunk 絕不讀取，防止觸發伺服端同步卡頓
                        if (!result.contains(neighbor) && level.isLoaded(neighbor)) {
                            BlockState neighborState = level.getBlockState(neighbor);

                            if (neighborState.is(targetBlock)) {
                                result.add(neighbor);
                                queue.add(neighbor);

                                if (result.size() >= maxCount) {
                                    return result;
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
}