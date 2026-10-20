package com.vt.vt.network;

import com.vt.vt.VTMod;
import com.vt.vt.chain.ChainMode;
import com.vt.vt.chain.ChainSelector;
import com.vt.vt.client.ClientVisuals;
import com.vt.vt.config.ConfigCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = VTMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public class NetworkHandler {

    public record PlayerChainState(
        boolean isActive, ChainMode mode, boolean preventToolBreak, int clientMaxCount, 
        BlockPos cachedTarget, Set<BlockPos> cachedShape, byte dx, byte dy, byte dz
    ) {}

    public static final Map<UUID, PlayerChainState> SERVER_STATES = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VTMod.MODID).versioned("1.0.0");
        registrar.playToServer(PacketSyncState.TYPE, PacketSyncState.STREAM_CODEC, NetworkHandler::handleSyncState);
        registrar.playToServer(PacketRequestShape.TYPE, PacketRequestShape.STREAM_CODEC, NetworkHandler::handleRequestShape);
        registrar.playToClient(PacketSyncShape.TYPE, PacketSyncShape.STREAM_CODEC, NetworkHandler::handleSyncShape);
        registrar.playToClient(PacketSpawnParticles.TYPE, PacketSpawnParticles.STREAM_CODEC, NetworkHandler::handleSpawnParticles);
    }

    private static void handleSyncState(PacketSyncState packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                int modeIdx = Math.floorMod(packet.mode(), ChainMode.values().length);
                ChainMode mode = ChainMode.values()[modeIdx];

                PlayerChainState old = SERVER_STATES.get(player.getUUID());
                BlockPos target = old != null ? old.cachedTarget() : null;
                Set<BlockPos> shape = old != null ? old.cachedShape() : null;
                byte dx = old != null ? old.dx() : 0;
                byte dy = old != null ? old.dy() : 0;
                byte dz = old != null ? old.dz() : 0;

                int maxCount = Math.max(1, Math.min(1024, packet.clientMaxCount()));

                SERVER_STATES.put(player.getUUID(), new PlayerChainState(
                    packet.isActive(), mode, packet.preventToolBreak(), maxCount, target, shape, dx, dy, dz
                ));
            }
        });
    }

    private static void handleRequestShape(PacketRequestShape packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PlayerChainState state = SERVER_STATES.get(player.getUUID());
                if (state == null || !state.isActive() || !ConfigCommon.ENABLE_CHAIN.get()) {
                    return;
                }

                int serverMax = ConfigCommon.MAX_CHAIN_BLOCKS.get();
                int effectiveMax = ChainSelector.getEffectiveMaxCount(player, state.mode(), state.clientMaxCount(), serverMax, state.preventToolBreak());

                Direction clickedFace = null;
                if (packet.faceIdx() >= 0 && packet.faceIdx() < Direction.values().length) {
                    clickedFace = Direction.values()[packet.faceIdx()];
                }

                byte safeDx = (byte) Math.max(-1, Math.min(1, packet.dx()));
                byte safeDy = (byte) Math.max(-1, Math.min(1, packet.dy()));
                byte safeDz = (byte) Math.max(-1, Math.min(1, packet.dz()));

                // 單次提取微觀相對座標，保證放置預覽與實體判定的精確性
                Vec3 eye = player.getEyePosition();
                Vec3 look = player.getLookAngle();
                double reach = player.blockInteractionRange();
                Vec3 end = eye.add(look.scale(reach));
                net.minecraft.world.level.ClipContext clipCtx = new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player);
                net.minecraft.world.phys.BlockHitResult realHit = player.level().clip(clipCtx);
                
                Vec3 relativeOffset;
                if (realHit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    BlockPos bp = realHit.getBlockPos();
                    relativeOffset = new Vec3(
                        Mth.clamp(realHit.getLocation().x - bp.getX(), 0.0, 1.0),
                        Mth.clamp(realHit.getLocation().y - bp.getY(), 0.0, 1.0),
                        Mth.clamp(realHit.getLocation().z - bp.getZ(), 0.0, 1.0)
                    );
                } else {
                    relativeOffset = new Vec3(0.5, 0.5, 0.5);
                }

                Set<BlockPos> shape = ChainSelector.selectBlocks(
                    player.serverLevel(), player, packet.pos(), clickedFace, state.mode(), effectiveMax, safeDx, safeDy, safeDz, relativeOffset
                );

                SERVER_STATES.put(player.getUUID(), new PlayerChainState(
                    state.isActive(), state.mode(), state.preventToolBreak(), state.clientMaxCount(), 
                    packet.pos(), shape, safeDx, safeDy, safeDz
                ));

                context.reply(new PacketSyncShape(new ArrayList<>(shape)));
            }
        });
    }

    private static void handleSyncShape(PacketSyncShape packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientVisuals.receiveSyncShape(packet.blocks()));
    }
    
    private static void handleSpawnParticles(PacketSpawnParticles packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientVisuals.receiveSpawnParticles(packet.blocks(), packet.stateId()));
    }

    // 監聽玩家離線事件，徹底消除玩家斷線造成的記憶體洩漏
    @EventBusSubscriber(modid = VTMod.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static class ForgeBusEvents {
        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            SERVER_STATES.remove(event.getEntity().getUUID());
        }
    }
}