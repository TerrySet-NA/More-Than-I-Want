package com.vt.vt.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vt.vt.VTMod;
import com.vt.vt.chain.ChainMode;
import com.vt.vt.config.ConfigClient;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.*;

@EventBusSubscriber(modid = VTMod.MODID, value = Dist.CLIENT)
public class ClientVisuals {

    private record Edge(int x, int y, int z, int axis) {}

    public static Set<BlockPos> cachedBlocks = Collections.emptySet();
    private static Set<Edge> cachedBoundaryEdges = Collections.emptySet();

    public static void receiveSyncShape(List<BlockPos> blocks) {
        if (blocks.isEmpty()) {
            cachedBlocks = Collections.emptySet();
            cachedBoundaryEdges = Collections.emptySet();
            return;
        }

        cachedBlocks = new LinkedHashSet<>(blocks);
        cachedBoundaryEdges = extractBoundaryEdges(cachedBlocks);
    }

    public static void receiveSpawnParticles(List<BlockPos> blocks, int stateId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ConfigClient.SPAWN_BREAK_PARTICLES.get()) {
            return;
        }

        for (BlockPos pos : blocks) {
            mc.level.levelEvent(2001, pos, stateId);
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !ClientChainInputHandler.isActive) {
            return;
        }

        ChainMode currentMode = ClientChainInputHandler.currentMode;
        Component modeName = currentMode.getDisplayName();
        Component chainText = Component.translatable("vt.gui.mode", modeName);
        int chainColor = currentMode.isPlace() ? 0x66CCFF : 0x55FF55;

        int screenWidth = event.getGuiGraphics().guiWidth();
        int screenHeight = event.getGuiGraphics().guiHeight();
        int y = screenHeight - 110; 

        int textWidth = mc.font.width(chainText);
        int x = (screenWidth - textWidth) / 2;
        event.getGuiGraphics().drawString(mc.font, chainText, x, y, chainColor, true);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        // 快速短路：未啟用或無邊界資料時，立即退出，完全不佔用任何 CPU/GPU 資源
        if (!ClientChainInputHandler.isActive || cachedBoundaryEdges.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();
        Vec3 cam = event.getCamera().getPosition();

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(2.5F);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        int r = ClientChainInputHandler.currentMode.isPlace() ? 0 : 0;
        int g = ClientChainInputHandler.currentMode.isPlace() ? 150 : 255;
        int b = ClientChainInputHandler.currentMode.isPlace() ? 255 : 0;
        int a = 255;

        for (Edge edge : cachedBoundaryEdges) {
            float x1 = (float) (edge.x() - cam.x());
            float y1 = (float) (edge.y() - cam.y());
            float z1 = (float) (edge.z() - cam.z());
            float x2 = (float) (edge.x() + (edge.axis() == 0 ? 1 : 0) - cam.x());
            float y2 = (float) (edge.y() + (edge.axis() == 1 ? 1 : 0) - cam.y());
            float z2 = (float) (edge.z() + (edge.axis() == 2 ? 1 : 0) - cam.z());

            buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
            buffer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        }

        MeshData meshData = buffer.build();
        if (meshData != null) {
            BufferUploader.drawWithShader(meshData);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    /**
     * 高效邊界萃取：使用 LongOpenHashSet 達成 0 BlockPos 物件分配
     */
    private static Set<Edge> extractBoundaryEdges(Set<BlockPos> blockSet) {
        LongSet posLookup = new LongOpenHashSet(blockSet.size());
        Set<Edge> candidates = new HashSet<>(blockSet.size() * 12);

        for (BlockPos pos : blockSet) {
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            posLookup.add(BlockPos.asLong(x, y, z));

            candidates.add(new Edge(x, y, z, 0));
            candidates.add(new Edge(x, y + 1, z, 0));
            candidates.add(new Edge(x, y, z + 1, 0));
            candidates.add(new Edge(x, y + 1, z + 1, 0));

            candidates.add(new Edge(x, y, z, 1));
            candidates.add(new Edge(x + 1, y, z, 1));
            candidates.add(new Edge(x, y, z + 1, 1));
            candidates.add(new Edge(x + 1, y, z + 1, 1));

            candidates.add(new Edge(x, y, z, 2));
            candidates.add(new Edge(x + 1, y, z, 2));
            candidates.add(new Edge(x, y + 1, z, 2));
            candidates.add(new Edge(x + 1, y + 1, z, 2));
        }

        Set<Edge> boundaryEdges = new HashSet<>(candidates.size() / 2);
        for (Edge edge : candidates) {
            if (isBoundaryEdge(posLookup, edge)) {
                boundaryEdges.add(edge);
            }
        }
        return boundaryEdges;
    }

    private static boolean isBoundaryEdge(LongSet set, Edge edge) {
        boolean in00, in01, in10, in11;
        int x = edge.x(), y = edge.y(), z = edge.z();

        // 零物件分配：直接以 long 雜湊查詢相鄰 4 格
        if (edge.axis() == 0) {
            in00 = set.contains(BlockPos.asLong(x, y - 1, z - 1));
            in01 = set.contains(BlockPos.asLong(x, y - 1, z));
            in10 = set.contains(BlockPos.asLong(x, y, z - 1));
            in11 = set.contains(BlockPos.asLong(x, y, z));
        } else if (edge.axis() == 1) {
            in00 = set.contains(BlockPos.asLong(x - 1, y, z - 1));
            in01 = set.contains(BlockPos.asLong(x - 1, y, z));
            in10 = set.contains(BlockPos.asLong(x, y, z - 1));
            in11 = set.contains(BlockPos.asLong(x, y, z));
        } else {
            in00 = set.contains(BlockPos.asLong(x - 1, y - 1, z));
            in01 = set.contains(BlockPos.asLong(x - 1, y, z));
            in10 = set.contains(BlockPos.asLong(x, y - 1, z));
            in11 = set.contains(BlockPos.asLong(x, y, z));
        }

        int count = (in00 ? 1 : 0) + (in01 ? 1 : 0) + (in10 ? 1 : 0) + (in11 ? 1 : 0);
        return count == 1 || count == 3 || (count == 2 && ((in00 && in11) || (in01 && in10)));
    }
}