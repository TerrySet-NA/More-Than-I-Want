package com.vt.vt.mixin;

import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.Queue;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class FixFabricAttachmentTaskMixin {

    @Shadow
    @Nullable
    private ConfigurationTask currentTask;

    @Shadow
    private Queue<ConfigurationTask> configurationTasks;

    @Shadow
    public abstract void finishCurrentTask(ConfigurationTask.Type type);

    /**
     * 在開始執行下一個配置任務前：
     * 1. 檢查隊列中或當前是否包含 Fabric 相關的 Attachment 同步任務
     * 2. 若有，直接從隊列拔除或自動標記完成，完全不觸發網路發送與 Dummy Codec
     */
    @Inject(
        method = "startNextTask",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vt$skipFabricAttachmentTask(CallbackInfo ci) {
        if (this.configurationTasks != null && !this.configurationTasks.isEmpty()) {
            this.configurationTasks.removeIf(task -> {
                if (task != null && task.type() != null) {
                    String taskId = task.type().id().toString();
                    return taskId.contains("accepted_attachments") || taskId.contains("fabric:attachment");
                }
                return false;
            });
        }
    }
}