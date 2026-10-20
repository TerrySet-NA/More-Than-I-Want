package com.vt.vt.chain;

import com.vt.vt.config.ConfigCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.*;
import java.util.function.ToIntFunction;

public class ChainSelector {

    public static int getEffectiveMaxCount(Player player, ChainMode mode, int clientMax, int serverMax, boolean preventToolBreak) {
        int configMax = Math.min(clientMax, serverMax);
        if (player.isCreative()) return configMax;

        if (mode.isMine()) {
            ItemStack tool = player.getMainHandItem();
            if (tool.isDamageableItem()) {
                int limit = preventToolBreak ? 1 : 0;
                int remaining = tool.getMaxDamage() - tool.getDamageValue() - limit;
                return Math.min(configMax, Math.max(0, remaining));
            }
        } else if (mode.isPlace()) {
            ItemStack main = player.getMainHandItem();
            ItemStack off = player.getOffhandItem();
            boolean mainIsBlock = !main.isEmpty() && main.getItem() instanceof BlockItem;
            boolean offIsBlock = !off.isEmpty() && off.getItem() instanceof BlockItem;
            if (mainIsBlock) return Math.min(configMax, main.getCount());
            else if (offIsBlock) return Math.min(configMax, off.getCount());
            else if (!main.isEmpty()) return Math.min(configMax, main.getCount());
        }
        return configMax;
    }

    /**
     * 核心判定：繼承微觀相對座標，保證台階、樓梯、方向方塊及實體碰撞對「每一個方塊」都完全正確
     */
    public static boolean canPlaceAt(Level level, BlockPos pos, Player player, Direction clickedFace, Vec3 relativeOffset) {
        if (!level.isLoaded(pos)) return false;
        BlockState currentState = level.getBlockState(pos);

        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        ItemStack stack = (!main.isEmpty() && main.getItem() instanceof BlockItem) ? main : off;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;

        InteractionHand hand = (stack == main) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        Direction face = (clickedFace == null) ? Direction.UP : clickedFace;

        // 將第一顆方塊命中點的微觀相對座標 (0.0~1.0) 映射至當前方塊
        Vec3 clickPos = (relativeOffset != null)
                ? Vec3.atLowerCornerOf(pos).add(relativeOffset)
                : Vec3.atCenterOf(pos);

        BlockHitResult hitResult = new BlockHitResult(clickPos, face, pos, false);
        BlockPlaceContext ctx = new BlockPlaceContext(player, hand, stack, hitResult);

        // 1. 基礎可替換性判定 (例如空氣、水、高草，或同種半磚向上合併)
        if (!currentState.canBeReplaced(ctx)) {
            return false;
        }

        // 2. 獲取該方塊放置後的實際狀態 (樓梯正反、原木朝向等)
        BlockState placementState = blockItem.getBlock().getStateForPlacement(ctx);
        if (placementState == null) {
            return false;
        }

        // 3. 檢查生存依賴 (防止紅石、門、火把在沒有地基的情況下憑空放置後瞬間破裂)
        if (!placementState.canSurvive(level, pos)) {
            return false;
        }

        // 4. 實體碰撞箱檢查 (防止方塊放置在玩家自身或生物身體內造成卡住窒息)
        CollisionContext collisionContext = (player != null) ? CollisionContext.of(player) : CollisionContext.empty();
        return level.isUnobstructed(placementState, pos, collisionContext);
    }

    public static boolean canPlaceAt(Level level, BlockPos pos, Player player, Direction clickedFace) {
        return canPlaceAt(level, pos, player, clickedFace, new Vec3(0.5, 0.5, 0.5));
    }

    private static boolean hasSupport(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            if (level.isLoaded(neighborPos) && !level.getBlockState(neighborPos).canBeReplaced()) {
                return true;
            }
        }
        return false;
    }

    public static Set<BlockPos> selectBlocks(Level level, Player player, BlockPos startPos, Direction clickedFace, ChainMode mode, int maxCount, byte dx, byte dy, byte dz) {
        return selectBlocks(level, player, startPos, clickedFace, mode, maxCount, dx, dy, dz, new Vec3(0.5, 0.5, 0.5));
    }

    public static Set<BlockPos> selectBlocks(Level level, Player player, BlockPos startPos, Direction clickedFace, ChainMode mode, int maxCount, byte dx, byte dy, byte dz, Vec3 relativeOffset) {
        Set<BlockPos> result = new LinkedHashSet<>();
        if (!level.isLoaded(startPos) || maxCount <= 0) return result;

        Direction resolvedDir6 = Direction.getNearest(dx, dy, dz);

        if (mode.isPlace()) {
            if (clickedFace == null) clickedFace = Direction.UP;
            boolean isReplaceable = canPlaceAt(level, startPos, player, clickedFace, relativeOffset);
            BlockPos origin = isReplaceable ? startPos : startPos.relative(clickedFace);
            if (!level.isLoaded(origin)) return result;

            Block targetSupportBlock = null;
            if (mode == ChainMode.PLACE_SURFACE) {
                BlockPos supportPos = isReplaceable ? startPos.relative(clickedFace.getOpposite()) : startPos;
                if (level.isLoaded(supportPos)) {
                    targetSupportBlock = level.getBlockState(supportPos).getBlock();
                }
            }

            switch (mode) {
                case PLACE_PLANE -> selectPlacePlane(level, player, origin, resolvedDir6, clickedFace, maxCount, relativeOffset, result);
                case PLACE_SURFACE -> selectPlaceSurface(level, player, origin, resolvedDir6, clickedFace, targetSupportBlock, maxCount, relativeOffset, result);
                case PLACE_FILL -> { selectPlaceFill(level, player, origin, resolvedDir6, clickedFace, maxCount, relativeOffset, result); return result; }
                case PLACE_3X3 -> selectPlace3x3(level, player, origin, resolvedDir6, clickedFace, relativeOffset, result);
                case PLACE_LINE -> { selectPlaceLine(level, player, origin, dx, dy, dz, clickedFace, maxCount, relativeOffset, result); return result; }
                default -> {}
            }
        } else {
            BlockState startState = level.getBlockState(startPos);
            if (startState.isAir()) return result;

            Block targetBlock = startState.getBlock();

            switch (mode) {
                case MINE_FREE -> selectFree(level, startPos, targetBlock, maxCount, result);
                case MINE_DOWN -> selectMineDown(level, player, startPos, targetBlock, resolvedDir6, maxCount, result); 
                case MINE_3X3 -> selectMine3x3(level, player, startPos, targetBlock, resolvedDir6, result);
                case MINE_LINE -> { selectMineLine(level, player, startPos, targetBlock, dx, dy, dz, maxCount, result); return result; }
                default -> {}
            }
        }

        List<BlockPos> sorted = new ArrayList<>(result);
        sorted.sort(Comparator.comparingDouble(p -> p.distSqr(startPos)));
        result.clear();
        result.addAll(sorted);
        return result;
    }

    private static void selectMineDown(Level level, Player player, BlockPos startPos, Block targetBlock, Direction resolvedDir6, int maxCount, Set<BlockPos> result) {
        Direction.Axis axis = resolvedDir6.getAxis();
        Direction upDir = resolvedDir6.getOpposite(); 
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> p.distSqr(startPos)));
        Set<BlockPos> visited = new HashSet<>();
        queue.add(startPos); 
        visited.add(startPos);
        
        while (!queue.isEmpty() && result.size() < maxCount) {
            BlockPos current = queue.poll();
            result.add(current);
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    if (i == 0 && j == 0) continue;
                    for (int k = 1; k >= -1; k--) {
                        BlockPos neighbor;
                        if (axis == Direction.Axis.Y) neighbor = current.offset(i, k * upDir.getStepY(), j);
                        else if (axis == Direction.Axis.X) neighbor = current.offset(k * upDir.getStepX(), i, j);
                        else neighbor = current.offset(i, j, k * upDir.getStepZ());
                        
                        if (!visited.contains(neighbor) && level.isLoaded(neighbor) && level.getBlockState(neighbor).is(targetBlock)) {
                            BlockPos neighborAbove = neighbor.relative(upDir);
                            if (level.isLoaded(neighborAbove) && level.getBlockState(neighborAbove).canBeReplaced()) {
                                visited.add(neighbor); 
                                queue.add(neighbor); 
                                break; 
                            }
                        }
                    }
                }
            }
        }
    }

    private static void selectPlaceFill(Level level, Player player, BlockPos origin, Direction resolvedDir6, Direction clickedFace, int maxCount, Vec3 relativeOffset, Set<BlockPos> result) {
        Direction depthDir = resolvedDir6; 
        ToIntFunction<BlockPos> getDepth = p -> (p.getX() - origin.getX()) * depthDir.getStepX() + (p.getY() - origin.getY()) * depthDir.getStepY() + (p.getZ() - origin.getZ()) * depthDir.getStepZ();
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingInt(getDepth).reversed().thenComparingDouble(p -> p.distSqr(origin)));
        Set<BlockPos> visited = new HashSet<>();
        if (canPlaceAt(level, origin, player, clickedFace, relativeOffset)) { 
            queue.add(origin); 
            visited.add(origin); 
        }
        List<BlockPos> collected = new ArrayList<>();
        while (!queue.isEmpty() && collected.size() < maxCount) {
            BlockPos current = queue.poll();
            collected.add(current); 
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (getDepth.applyAsInt(neighbor) >= 0 && !visited.contains(neighbor) && canPlaceAt(level, neighbor, player, clickedFace, relativeOffset)) {
                            visited.add(neighbor); 
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
        collected.sort(Comparator.comparingInt(getDepth).reversed().thenComparingDouble(p -> p.distSqr(origin)));
        result.addAll(collected);
    }

    private static void selectPlacePlane(Level level, Player player, BlockPos origin, Direction resolvedDir6, Direction clickedFace, int maxCount, Vec3 relativeOffset, Set<BlockPos> result) {
        Direction.Axis axis = resolvedDir6.getAxis();
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> p.distSqr(origin)));
        Set<BlockPos> visited = new HashSet<>();
        if (canPlaceAt(level, origin, player, clickedFace, relativeOffset)) { 
            queue.add(origin); 
            visited.add(origin); 
        }
        while (!queue.isEmpty() && result.size() < maxCount) {
            BlockPos current = queue.poll();
            result.add(current);
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    if (i == 0 && j == 0) continue;
                    BlockPos neighbor;
                    if (axis == Direction.Axis.Y) neighbor = current.offset(i, 0, j); 
                    else if (axis == Direction.Axis.X) neighbor = current.offset(0, i, j); 
                    else neighbor = current.offset(i, j, 0);

                    if (!visited.contains(neighbor) && canPlaceAt(level, neighbor, player, clickedFace, relativeOffset)) { 
                        visited.add(neighbor); 
                        queue.add(neighbor); 
                    }
                }
            }
        }
    }

    private static void selectPlaceSurface(Level level, Player player, BlockPos origin, Direction resolvedDir6, Direction clickedFace, Block targetSupportBlock, int maxCount, Vec3 relativeOffset, Set<BlockPos> result) {
        if (targetSupportBlock == null) return;
        Direction.Axis axis = resolvedDir6.getAxis();
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> p.distSqr(origin)));
        Set<BlockPos> visited = new HashSet<>();
        if (canPlaceAt(level, origin, player, clickedFace, relativeOffset)) { 
            queue.add(origin); 
            visited.add(origin); 
        }
        
        while (!queue.isEmpty() && result.size() < maxCount) {
            BlockPos current = queue.poll();
            result.add(current);
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    if (i == 0 && j == 0) continue;
                    BlockPos step;
                    if (axis == Direction.Axis.Y) step = current.offset(i, 0, j); 
                    else if (axis == Direction.Axis.X) step = current.offset(0, i, j); 
                    else step = current.offset(i, j, 0);

                    BlockPos[] candidates = new BlockPos[]{ step, step.relative(resolvedDir6), step.relative(resolvedDir6.getOpposite()) };
                    for (BlockPos neighbor : candidates) {
                        if (!visited.contains(neighbor) && canPlaceAt(level, neighbor, player, clickedFace, relativeOffset)) {
                            BlockPos supportPos = neighbor.relative(resolvedDir6);
                            if (level.isLoaded(supportPos) && level.getBlockState(supportPos).is(targetSupportBlock)) { 
                                visited.add(neighbor); 
                                queue.add(neighbor); 
                                break; 
                            }
                        }
                    }
                }
            }
        }
    }

    private static void selectPlace3x3(Level level, Player player, BlockPos origin, Direction resolvedDir6, Direction clickedFace, Vec3 relativeOffset, Set<BlockPos> result) {
        Direction.Axis axis = resolvedDir6.getAxis();
        int[] dxs = axis == Direction.Axis.X ? new int[]{0} : new int[]{-1, 0, 1};
        int[] dys = axis == Direction.Axis.Y ? new int[]{0} : new int[]{-1, 0, 1};
        int[] dzs = axis == Direction.Axis.Z ? new int[]{0} : new int[]{-1, 0, 1};
        for (int dx : dxs) {
            for (int dy : dys) {
                for (int dz : dzs) {
                    BlockPos target = origin.offset(dx, dy, dz);
                    if (canPlaceAt(level, target, player, clickedFace, relativeOffset)) result.add(target);
                }
            }
        }
    }

    private static void selectPlaceLine(Level level, Player player, BlockPos origin, int dx, int dy, int dz, Direction clickedFace, int maxCount, Vec3 relativeOffset, Set<BlockPos> result) {
        boolean allowAir = ConfigCommon.ALLOW_AIR_PLACE.get();
        boolean isDiagonal = (Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) > 1;

        for (int i = 0; i < maxCount; i++) {
            BlockPos target = origin.offset(dx * i, dy * i, dz * i);
            if (i > 0 && isDiagonal && !allowAir) {
                if (!hasSupport(level, target)) break;
            }

            if (canPlaceAt(level, target, player, clickedFace, relativeOffset)) {
                result.add(target); 
            } else {
                break;
            }
        }
    }

    private static void selectMine3x3(Level level, Player player, BlockPos startPos, Block targetBlock, Direction resolvedDir6, Set<BlockPos> result) {
        Direction.Axis axis = resolvedDir6.getAxis();
        int[] dxs = axis == Direction.Axis.X ? new int[]{0} : new int[]{-1, 0, 1};
        int[] dys = axis == Direction.Axis.Y ? new int[]{0} : new int[]{-1, 0, 1};
        int[] dzs = axis == Direction.Axis.Z ? new int[]{0} : new int[]{-1, 0, 1};
        for (int dx : dxs) {
            for (int dy : dys) {
                for (int dz : dzs) {
                    BlockPos target = startPos.offset(dx, dy, dz);
                    if (canPlayerHarvest(level, player, target, targetBlock)) result.add(target);
                }
            }
        }
    }

    private static void selectMineLine(Level level, Player player, BlockPos startPos, Block targetBlock, int dx, int dy, int dz, int maxCount, Set<BlockPos> result) {
        for (int i = 0; i < maxCount; i++) {
            BlockPos target = startPos.offset(dx * i, dy * i, dz * i);
            if (canPlayerHarvest(level, player, target, targetBlock)) {
                result.add(target); 
            } else {
                break;
            }
        }
    }

    private static void selectFree(Level level, BlockPos startPos, Block targetBlock, int maxCount, Set<BlockPos> result) {
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> p.distSqr(startPos)));
        Set<BlockPos> visited = new HashSet<>();
        queue.add(startPos); 
        visited.add(startPos);
        
        boolean allowDiagonal = ConfigCommon.DIAGONAL_DETECTION.get();

        while (!queue.isEmpty() && result.size() < maxCount) {
            BlockPos current = queue.poll();
            result.add(current);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        if (!allowDiagonal && (Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) > 1) continue;

                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (!visited.contains(neighbor) && level.isLoaded(neighbor) && level.getBlockState(neighbor).is(targetBlock)) {
                            visited.add(neighbor); 
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
    }

    private static boolean canPlayerHarvest(Level level, Player player, BlockPos pos, Block targetBlock) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return false; 
        
        if (state.is(targetBlock)) return true;
        return !state.requiresCorrectToolForDrops() || player.hasCorrectToolForDrops(state);
    }
}