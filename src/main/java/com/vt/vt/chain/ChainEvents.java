package com.vt.vt.chain;

import com.vt.vt.VTMod;
import com.vt.vt.config.ConfigCommon;
import com.vt.vt.network.NetworkHandler;
import com.vt.vt.network.PacketSpawnParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@EventBusSubscriber(modid = VTMod.MODID)
public class ChainEvents {

    private static final ThreadLocal<Boolean> IS_SIMULATING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<BlockPos> CURRENT_TARGET_POS = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<List<ItemStack>> COLLECTED_ITEMS = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Integer> COLLECTED_XP = ThreadLocal.withInitial(() -> 0);

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (event.getEntity() == null) return;
        Player player = event.getEntity();
        Level level = player.level();
        
        BlockPos startPos = event.getPosition().orElse(null);
        if (startPos == null) return;
        
        Set<BlockPos> connectedBlocks = Collections.emptySet();
        
        if (level.isClientSide()) {
            if (com.vt.vt.client.ClientChainInputHandler.isActive && com.vt.vt.client.ClientChainInputHandler.currentMode.isMine()) {
                connectedBlocks = com.vt.vt.client.ClientVisuals.cachedBlocks;
            }
        } else {
            NetworkHandler.PlayerChainState state = NetworkHandler.SERVER_STATES.get(player.getUUID());
            if (state != null && state.isActive() && state.mode().isMine()) {
                if (state.cachedTarget() != null && state.cachedTarget().equals(startPos) && state.cachedShape() != null) {
                    connectedBlocks = state.cachedShape();
                }
            }
        }

        if (!connectedBlocks.isEmpty()) {
            float maxHardness = 0.0f;
            for (BlockPos p : connectedBlocks) {
                if (level.isLoaded(p)) {
                    float h = level.getBlockState(p).getDestroySpeed(level, p);
                    if (h > maxHardness) maxHardness = h;
                }
            }

            if (maxHardness > 0) {
                float baseMult = ConfigCommon.BASE_TIME_MULTIPLIER.get().floatValue();
                float perBlockMult = ConfigCommon.PER_BLOCK_TIME_MULTIPLIER.get().floatValue();
                
                int extraBlocks = Math.max(0, connectedBlocks.size() - 1);
                float targetHardness = (maxHardness * baseMult) + (maxHardness * perBlockMult * extraBlocks);
                targetHardness = Math.max(0.001F, targetHardness);
                
                float currentHardness = event.getState().getDestroySpeed(level, startPos);
                event.setNewSpeed(event.getNewSpeed() * (currentHardness / targetHardness));
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!IS_SIMULATING.get()) return;
        BlockPos currentPos = CURRENT_TARGET_POS.get();
        if (currentPos == null) return;
        if (event.getEntity().position().distanceToSqr(Vec3.atCenterOf(currentPos)) > 16.0) return;

        if (event.getEntity() instanceof ItemEntity itemEntity) {
            event.setCanceled(true);
            COLLECTED_ITEMS.get().add(itemEntity.getItem().copy());
        } else if (event.getEntity() instanceof ExperienceOrb xpOrb) {
            event.setCanceled(true);
            COLLECTED_XP.set(COLLECTED_XP.get() + xpOrb.getValue());
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide() || IS_SIMULATING.get()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        NetworkHandler.PlayerChainState state = NetworkHandler.SERVER_STATES.get(player.getUUID());
        if (state == null || !state.isActive() || state.mode().isPlace()) return;

        event.setCanceled(true);
        BlockPos startPos = event.getPos();
        Set<BlockPos> connectedBlocks;

        if (state.cachedTarget() != null && state.cachedTarget().equals(startPos) && state.cachedShape() != null) {
            connectedBlocks = state.cachedShape();
        } else {
            int serverMax = ConfigCommon.MAX_CHAIN_BLOCKS.get();
            int maxCount = ChainSelector.getEffectiveMaxCount(player, state.mode(), state.clientMaxCount(), serverMax, state.preventToolBreak());
            connectedBlocks = ChainSelector.selectBlocks((Level) event.getLevel(), player, startPos, null, state.mode(), maxCount, state.dx(), state.dy(), state.dz());
        }

        executeChainMine((Level) event.getLevel(), player, startPos, connectedBlocks, state.mode(), state.preventToolBreak());
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || IS_SIMULATING.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        NetworkHandler.PlayerChainState state = NetworkHandler.SERVER_STATES.get(player.getUUID());
        // 核心：連鎖啟用時（無論挖掘模式或放置模式），均能觸發右鍵連鎖互動
        if (state == null || !state.isActive()) return;

        Level level = event.getLevel();
        BlockHitResult hitResult = event.getHitVec();
        BlockPos startPos = hitResult.getBlockPos();
        Direction clickedFace = hitResult.getDirection();
        InteractionHand hand = event.getHand();
        BlockState blockState = level.getBlockState(startPos);

        // 如果點到有介面的方塊（箱子、工作台、熔爐等），放行原版開啟選單
        if (blockState.getMenuProvider(level, startPos) != null || blockState.hasBlockEntity()) return;

        // 計算命中點在方塊內的精準微觀座標 (0.0 ~ 1.0)
        Vec3 hitLoc = hitResult.getLocation();
        Vec3 relativeOffset = new Vec3(
            Mth.clamp(hitLoc.x - startPos.getX(), 0.0, 1.0),
            Mth.clamp(hitLoc.y - startPos.getY(), 0.0, 1.0),
            Mth.clamp(hitLoc.z - startPos.getZ(), 0.0, 1.0)
        );

        Set<BlockPos> connectedBlocks;
        if (state.cachedTarget() != null && state.cachedTarget().equals(startPos) && state.cachedShape() != null) {
            connectedBlocks = state.cachedShape();
        } else {
            int serverMax = ConfigCommon.MAX_CHAIN_BLOCKS.get();
            int maxCount = ChainSelector.getEffectiveMaxCount(player, state.mode(), state.clientMaxCount(), serverMax, state.preventToolBreak());
            connectedBlocks = ChainSelector.selectBlocks(level, player, startPos, clickedFace, state.mode(), maxCount, state.dx(), state.dy(), state.dz(), relativeOffset);
        }

        // 嘗試執行連鎖右鍵互動（放置方塊或工具連鎖互動）
        boolean handled = executeChainRightClick(level, player, startPos, hitResult, hand, connectedBlocks, state.mode(), state.preventToolBreak(), relativeOffset);

        // 若操作成功，取消原版後續重複觸發；若此物品對此方塊無任何效果，絕不取消事件，放行原版吃食物/拉弓
        if (handled) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * 右鍵專用連鎖執行器：全模式支援（包含挖掘模式工具互動與放置模式方塊放置）
     */
    private static boolean executeChainRightClick(Level level, ServerPlayer player, BlockPos startPos, BlockHitResult originalHit, InteractionHand hand, Set<BlockPos> connectedBlocks, ChainMode mode, boolean preventToolBreak, Vec3 relativeOffset) {
        if (connectedBlocks.isEmpty()) return false;

        ItemStack initialStack = player.getItemInHand(hand);
        if (initialStack.isEmpty()) return false;
        if (mode.isMine() && isToolBroken(initialStack, preventToolBreak)) return false;

        Direction face = originalHit.getDirection();
        boolean isInside = originalHit.isInside();

        // 1. 先在模擬模式下嘗試第一顆方塊
        BlockPos firstPos = connectedBlocks.iterator().next();
        Vec3 firstHitVec = Vec3.atLowerCornerOf(firstPos).add(relativeOffset);
        BlockHitResult firstHit = new BlockHitResult(firstHitVec, face, firstPos, isInside);

        IS_SIMULATING.set(true);
        InteractionResult firstResult;
        try {
            firstResult = player.gameMode.useItemOn(player, level, initialStack, hand, firstHit);
        } finally {
            IS_SIMULATING.set(false);
        }

        // 若第一顆方塊完全不消耗動作（代表物品對該方塊無右鍵效果），直接退出，不攔截原版
        if (!firstResult.consumesAction()) {
            return false;
        }

        // 2. 第一顆成功！將該右鍵互動重複套用到集合中其餘的所有方塊上
        IS_SIMULATING.set(true);
        try {
            boolean isFirst = true;
            for (BlockPos targetPos : connectedBlocks) {
                if (isFirst) {
                    isFirst = false; // 第一顆已執行完畢，跳過
                    continue;
                }

                ItemStack currentStack = player.getItemInHand(hand);
                if (currentStack.isEmpty()) break;
                if (mode.isMine() && isToolBroken(currentStack, preventToolBreak)) break;

                CURRENT_TARGET_POS.set(targetPos);

                Vec3 simLocation = Vec3.atLowerCornerOf(targetPos).add(relativeOffset);
                BlockHitResult simulatedHit = new BlockHitResult(simLocation, face, targetPos, isInside);

                InteractionResult result = player.gameMode.useItemOn(player, level, currentStack, hand, simulatedHit);
                if (!result.consumesAction()) {
                    continue;
                }
            }
        } finally {
            CURRENT_TARGET_POS.set(null);
            IS_SIMULATING.set(false);
        }

        return true;
    }

    /**
     * 左鍵破壞專用連鎖執行器
     */
    private static void executeChainMine(Level level, ServerPlayer player, BlockPos startPos, Set<BlockPos> connectedBlocks, ChainMode mode, boolean preventToolBreak) {
        IS_SIMULATING.set(true);
        COLLECTED_ITEMS.get().clear();
        COLLECTED_XP.set(0);

        List<BlockPos> brokenBlocks = new ArrayList<>();
        int stateId = 0;

        try {
            for (BlockPos targetPos : connectedBlocks) {
                ItemStack currentStack = player.getMainHandItem();
                if (isToolBroken(currentStack, preventToolBreak)) break;

                CURRENT_TARGET_POS.set(targetPos);

                BlockState targetState = level.getBlockState(targetPos);
                if (stateId == 0) stateId = Block.getId(targetState);
                if (!targetPos.equals(startPos)) brokenBlocks.add(targetPos);

                player.gameMode.destroyBlock(targetPos);
            }
        } finally {
            CURRENT_TARGET_POS.set(null);

            // 飢餓度消耗
            if (connectedBlocks.size() > 1 && !player.isCreative()) {
                int extraBlocks = connectedBlocks.size() - 1;
                float exhaustMult = ConfigCommon.EXHAUSTION_MULTIPLIER.get().floatValue();
                player.causeFoodExhaustion(0.005F * extraBlocks * Math.max(0.0F, exhaustMult));
            }

            // 廣播連鎖粒子效果
            if (!brokenBlocks.isEmpty() && level instanceof ServerLevel sl) {
                PacketSpawnParticles packet = new PacketSpawnParticles(brokenBlocks, stateId);
                PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(startPos), packet);
            }

            if (level instanceof ServerLevel serverLevel) {
                flushCollectedDrops(serverLevel, startPos);
            }
            IS_SIMULATING.set(false);
        }
    }

    private static boolean isToolBroken(ItemStack stack, boolean preventToolBreak) {
        if (!stack.isDamageableItem()) return false;
        int limit = preventToolBreak ? 1 : 0;
        return stack.getDamageValue() >= stack.getMaxDamage() - limit;
    }

    private static void flushCollectedDrops(ServerLevel level, BlockPos spawnPos) {
        List<ItemStack> rawItems = COLLECTED_ITEMS.get();
        int totalXp = COLLECTED_XP.get();
        Vec3 dropPos = Vec3.atCenterOf(spawnPos);

        if (!rawItems.isEmpty()) {
            for (ItemStack stack : rawItems) {
                ItemEntity itemEntity = new ItemEntity(level, dropPos.x, dropPos.y, dropPos.z, stack);
                itemEntity.setDefaultPickUpDelay();
                level.addFreshEntity(itemEntity);
            }
            rawItems.clear();
        }
        if (totalXp > 0) {
            ExperienceOrb.award(level, dropPos, totalXp);
            COLLECTED_XP.set(0);
        }
    }
}