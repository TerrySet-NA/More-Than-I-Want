package com.vt.vt.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.vt.vt.VTMod;
import com.vt.vt.chain.ChainMode;
import com.vt.vt.config.ConfigClient;
import com.vt.vt.config.ConfigCommon;
import com.vt.vt.network.PacketRequestShape;
import com.vt.vt.network.PacketSyncState;
import com.vt.vt.util.LookUtil;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;

@EventBusSubscriber(modid = VTMod.MODID, value = Dist.CLIENT)
public class ClientChainInputHandler {

    public static final KeyMapping KEY_1 = new KeyMapping("key.vt.key1", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_GRAVE_ACCENT, "key.categories.vt");
    public static final KeyMapping KEY_2 = new KeyMapping("key.vt.key2", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_SHIFT, "key.categories.vt");

    public static boolean isActive = false;
    public static ChainMode currentMode = ChainMode.MINE_FREE;
    
    public static int shapeIndex = 0; 
    public static boolean isPlaceCategory = true;
    private static boolean categoryInitialized = false;

    private static boolean wasK1Down = false;
    private static boolean wasK2Down = false;
    
    private static boolean lastPtb = false;
    private static int lastCmc = -1;

    // 封包發送狀態快取
    private static BlockPos lastTargetPos = null;
    private static int lastFaceIdx = -1;
    private static byte lastDx = 0, lastDy = 0, lastDz = 0;
    private static ItemStack lastMainItem = ItemStack.EMPTY;
    private static ItemStack lastOffItem = ItemStack.EMPTY;
    private static long lastUpdateTick = 0;

    // 客戶端挖掘中鎖定方向
    private static Vec3 lockedMiningLook = null;

    public static ChainMode getMode(int idx, boolean place) {
        if (place) {
            return switch(idx) {
                case 0 -> ChainMode.PLACE_FILL;
                case 1 -> ChainMode.PLACE_SURFACE;
                case 2 -> ChainMode.PLACE_3X3;
                case 3 -> ChainMode.PLACE_LINE;
                case 4 -> ChainMode.PLACE_PLANE;
                default -> ChainMode.PLACE_FILL;
            };
        } else {
            return switch(idx) {
                case 0 -> ChainMode.MINE_FREE;
                case 1 -> ChainMode.MINE_DOWN;
                case 2 -> ChainMode.MINE_3X3;
                case 3 -> ChainMode.MINE_LINE;
                default -> ChainMode.MINE_FREE;
            };
        }
    }

    private static void sanitizeShapeIndex() {
        int max = isPlaceCategory ? 5 : 4;
        if (shapeIndex >= max || shapeIndex < 0) {
            shapeIndex = 0;
        }
    }

    @EventBusSubscriber(modid = VTMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void registerBindings(RegisterKeyMappingsEvent event) {
            event.register(KEY_1);
            event.register(KEY_2);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null || !ConfigCommon.ENABLE_CHAIN.get()) {
            return;
        }

        if (!categoryInitialized) {
            isPlaceCategory = !ConfigClient.REVERSE_KEYS.get();
            sanitizeShapeIndex();
            currentMode = getMode(shapeIndex, isPlaceCategory);
            categoryInitialized = true;
        }

        boolean k1 = KEY_1.isDown();
        boolean k2 = KEY_2.isDown();
        boolean reverse = ConfigClient.REVERSE_KEYS.get();
        boolean k1Pressed = k1 && !wasK1Down;
        boolean k2Pressed = k2 && !wasK2Down;
        
        boolean changed = false;
        boolean isSingleKeyMode = ConfigClient.SINGLE_KEY_MODE.get();
        boolean activateHold = ConfigClient.HOLD_TO_ACTIVATE.get();
        boolean categoryHold = ConfigClient.HOLD_TO_TOGGLE_CATEGORY.get();

        if (!isSingleKeyMode) { 
            if (activateHold) {
                boolean newActive = k1 || k2;
                if (isActive != newActive) { 
                    isActive = newActive; 
                    changed = true; 
                }
                if (isActive) {
                    if (k1 && !k2 && !isPlaceCategory) { 
                        isPlaceCategory = true; 
                        changed = true; 
                    } else if (k2 && !k1 && isPlaceCategory) { 
                        isPlaceCategory = false; 
                        sanitizeShapeIndex();
                        changed = true; 
                    }
                }
            } else {
                if (k1Pressed) { 
                    if (isActive && isPlaceCategory) { 
                        isActive = false; 
                    } else { 
                        isActive = true; 
                        isPlaceCategory = true; 
                    } 
                    changed = true; 
                }
                if (k2Pressed) { 
                    if (isActive && !isPlaceCategory) { 
                        isActive = false; 
                    } else { 
                        isActive = true; 
                        isPlaceCategory = false; 
                        sanitizeShapeIndex();
                    } 
                    changed = true; 
                }
            }
        } else { 
            if (categoryHold) {
                boolean targetPlace = reverse ? k2 : !k2;
                if (isPlaceCategory != targetPlace) { 
                    isPlaceCategory = targetPlace; 
                    sanitizeShapeIndex();
                    changed = true; 
                }
            } else {
                if (k2Pressed) { 
                    isPlaceCategory = !isPlaceCategory; 
                    sanitizeShapeIndex();
                    changed = true; 
                }
            }
            if (activateHold) { 
                if (isActive != k1) { 
                    isActive = k1; 
                    changed = true; 
                } 
            } else { 
                if (k1Pressed) { 
                    isActive = !isActive; 
                    changed = true; 
                } 
            }
        }

        wasK1Down = k1; 
        wasK2Down = k2;
        sanitizeShapeIndex();

        ChainMode newMode = getMode(shapeIndex, isPlaceCategory);
        if (currentMode != newMode) { 
            currentMode = newMode; 
            changed = true; 
        }

        boolean ptb = ConfigClient.PREVENT_TOOL_BREAK.get();
        int cmc = ConfigClient.MAX_CHAIN_BLOCKS.get();
        if (ptb != lastPtb || cmc != lastCmc) { 
            changed = true; 
            lastPtb = ptb; 
            lastCmc = cmc; 
        }
        
        if (changed) {
            PacketDistributor.sendToServer(new PacketSyncState(isActive, currentMode.ordinal(), ptb, cmc));
            if (!isActive) {
                lastTargetPos = null;
                lockedMiningLook = null;
                ClientVisuals.receiveSyncShape(Collections.emptyList());
                return;
            }
        }

        if (isActive) {
            HitResult hit = mc.hitResult;
            BlockPos targetPos = (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) ? bhr.getBlockPos() : null;

            if (targetPos != null) {
                boolean isMining = mc.gameMode != null && mc.gameMode.isDestroying();
                int currentFaceIdx = ((BlockHitResult) hit).getDirection().ordinal();

                boolean needs26Dir = (currentMode == ChainMode.MINE_LINE || currentMode == ChainMode.PLACE_LINE);
                LookUtil.LookType lookType = needs26Dir ? LookUtil.LookType.DIR_26 : LookUtil.LookType.DIR_6;

                // 決定參考視角方向向量
                Vec3 baseLook;
                if (isMining) {
                    if (lockedMiningLook == null) {
                        lockedMiningLook = mc.player.getLookAngle();
                    }
                    baseLook = lockedMiningLook;
                } else {
                    lockedMiningLook = null;
                    boolean useBlockFace = ConfigClient.USE_BLOCK_FACE.get();
                    if ((currentMode == ChainMode.MINE_DOWN || currentMode == ChainMode.PLACE_SURFACE) && ConfigClient.AXIS_MODES_USE_BLOCK_FACE.get()) {
                        useBlockFace = true;
                    }

                    if (useBlockFace) {
                        Direction opp = ((BlockHitResult) hit).getDirection().getOpposite();
                        baseLook = new Vec3(opp.getStepX(), opp.getStepY(), opp.getStepZ());
                    } else {
                        baseLook = mc.player.getLookAngle();
                    }
                }

                Vec3 gridDir = LookUtil.getGridDirection(baseLook, lookType);
                byte dx = (byte) gridDir.x; 
                byte dy = (byte) gridDir.y; 
                byte dz = (byte) gridDir.z;

                if (dx == 0 && dy == 0 && dz == 0) { 
                    Direction fallback = LookUtil.toDirection(mc.player.getLookAngle());
                    dx = (byte) fallback.getStepX(); 
                    dy = (byte) fallback.getStepY(); 
                    dz = (byte) fallback.getStepZ(); 
                }

                long currentTick = mc.player.level().getGameTime();
                ItemStack currentMain = mc.player.getMainHandItem();
                ItemStack currentOff = mc.player.getOffhandItem();
                
                // 使用 1.21.1 原生 matches 檢查數量與 Components 變化
                boolean mainChanged = !ItemStack.matches(currentMain, lastMainItem);
                boolean offChanged = !ItemStack.matches(currentOff, lastOffItem);
                
                boolean forceUpdate = (currentTick - lastUpdateTick) >= 10 && !isMining;

                if (forceUpdate || !targetPos.equals(lastTargetPos) || currentFaceIdx != lastFaceIdx 
                        || dx != lastDx || dy != lastDy || dz != lastDz || mainChanged || offChanged || changed) {
                    
                    lastTargetPos = targetPos.immutable(); 
                    lastFaceIdx = currentFaceIdx; 
                    lastDx = dx; 
                    lastDy = dy; 
                    lastDz = dz; 
                    lastMainItem = currentMain.copy(); 
                    lastOffItem = currentOff.copy(); 
                    lastUpdateTick = currentTick;

                    PacketDistributor.sendToServer(new PacketRequestShape(targetPos, currentFaceIdx, dx, dy, dz));
                }
            } else {
                if (lastTargetPos != null) {
                    lastTargetPos = null;
                    lockedMiningLook = null;
                    ClientVisuals.receiveSyncShape(Collections.emptyList());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (isActive && mc.player != null && mc.screen == null) {
            double delta = event.getScrollDeltaY();
            if (delta != 0) {
                int maxModes = isPlaceCategory ? 5 : 4;
                int dir = delta > 0 ? 1 : -1;
                // 正確的同餘運算，徹底杜絕越界與非對稱滾動
                shapeIndex = Math.floorMod(shapeIndex + dir, maxModes);
                
                currentMode = getMode(shapeIndex, isPlaceCategory);
                PacketDistributor.sendToServer(new PacketSyncState(isActive, currentMode.ordinal(), lastPtb, lastCmc));
                event.setCanceled(true);
            }
        }
    }
}