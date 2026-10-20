package com.vt.vt.cleaner;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.vt.vt.config.ConfigCommon;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

public class VTCommandRegistry {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("vt")
                // 1. 手動立即清理：/vt clean
                .then(Commands.literal("clean")
                    .requires(src -> ConfigCommon.CLEANER_PERMISSION.get().hasPermission(src))
                    .executes(ctx -> {
                        int count = TrashCleanerService.executeCleanup(ctx.getSource().getServer());
                        ctx.getSource().sendSuccess(() -> Component.translatable("vt.command.clean.success", count), true);
                        return count;
                    })
                )
                // 2. 查看回檔倉庫：/vt rollback [回檔編號]
                .then(Commands.literal("rollback")
                    .requires(src -> ConfigCommon.CLEANER_PERMISSION.get().hasPermission(src))
                    .executes(ctx -> handleRollbackCommand(ctx.getSource(), 1))
                    .then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int idx = IntegerArgumentType.getInteger(ctx, "index");
                            return handleRollbackCommand(ctx.getSource(), idx);
                        })
                    )
                )
        );
    }

    private static int handleRollbackCommand(CommandSourceStack source, int index) {
        if (!source.isPlayer()) {
            source.sendFailure(Component.translatable("vt.command.no_permission"));
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }

        return openRollbackMenu(player, index);
    }

    private static int openRollbackMenu(ServerPlayer player, int index) {
        SharedTrashInventory inventory = CleanupManager.getOrCreateSharedInventory(player.getServer(), index);
        if (inventory == null) {
            player.sendSystemMessage(Component.translatable("vt.command.rollback.not_found", index));
            return 0;
        }

        player.openMenu(
            new SimpleMenuProvider(
                (id, pInv, p) -> new TrashRollbackMenu(id, pInv, index),
                Component.translatable("vt.gui.rollback.title", index)
            ),
            buffer -> buffer.writeInt(index)
        );

        // 開啟後將當前快照內的物品資料發送給該玩家客戶端
        CleanupManager.broadcastInventoryUpdate(player.getServer(), index);
        return 1;
    }
}