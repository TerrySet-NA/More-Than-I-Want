package com.vt.vt;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.vt.vt.cleaner.TrashCleanerService;
import com.vt.vt.cleaner.TrashRollbackMenu;
import com.vt.vt.cleaner.VTCommandRegistry;
import com.vt.vt.client.VTClientMod;
import com.vt.vt.config.CommonConfigSyncHooks;
import com.vt.vt.config.ConfigClient;
import com.vt.vt.config.ConfigCommon;
import com.vt.vt.network.CleanNbtPayload;
import com.vt.vt.network.RequestSyncPayload;
import com.vt.vt.network.RollbackEquipPayload;
import com.vt.vt.network.SyncCommonConfigValuesPayload;
import com.vt.vt.network.TrashNetwork;
import com.vt.vt.network.UploadAndDistributePayload;
import com.vt.vt.player.DeathDropEvent;
import com.vt.vt.player.InventoryCleaner;
import com.vt.vt.util.TextOverrideManager;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(VTMod.MODID)
public class VTMod {
    public static final String MODID = "vt";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredHolder<MenuType<?>, MenuType<TrashRollbackMenu>> TRASH_ROLLBACK_MENU = 
            MENUS.register("trash_rollback_menu", () -> IMenuTypeExtension.create(TrashRollbackMenu::new));

    public VTMod(IEventBus modEventBus, ModContainer modContainer) {
        // 註冊設定檔
        modContainer.registerConfig(ModConfig.Type.CLIENT, ConfigClient.SPEC);
        modContainer.registerConfig(ModConfig.Type.COMMON, ConfigCommon.SPEC);

        TextOverrideManager.load();
        
        // 註冊 Mod Bus 事件
        MENUS.register(modEventBus);
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::registerPayloads);

        // 註冊 Forge/NeoForge 全域 Bus 事件
        IEventBus forgeBus = NeoForge.EVENT_BUS;
        forgeBus.addListener(this::onServerStarted);
        forgeBus.addListener(this::onServerStopped); // 避免 Server 物件洩漏
        forgeBus.addListener(this::onServerTick);
        forgeBus.addListener(this::onRegisterCommands);
        forgeBus.addListener(CommonConfigSyncHooks::onPlayerLoggedIn);
        forgeBus.addListener(DeathDropEvent::onPlayerDeathDrops);

        // 若在客戶端環境，註冊客戶端專用邏輯（徹底隔離伺服器端的 Classloading）
        if (FMLEnvironment.dist.isClient()) {
            VTClientMod.init(modEventBus);
        }

        // 扁平化遍歷所有模組的 EventBus，監聽 Config Reload
        ModList.get().forEachModContainer((modId, container) -> {
            IEventBus bus = container.getEventBus();
            if (bus != null) {
                bus.addListener(CommonConfigSyncHooks::onAnyModConfigReload);
            }
        });
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        LOGGER.info("VT Mod Config Loaded: {}", event.getConfig().getFileName());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        // 明確指定命名空間與版本號
        PayloadRegistrar registrar = event.registrar(MODID).versioned("1.0.0");

        registrar.playToClient(
            TrashNetwork.SyncTrashPayload.TYPE, 
            TrashNetwork.SyncTrashPayload.STREAM_CODEC, 
            TrashNetwork.SyncTrashPayload::handle
        );
        registrar.playToServer(
            TrashNetwork.RequestTakeTrashPayload.TYPE, 
            TrashNetwork.RequestTakeTrashPayload.STREAM_CODEC, 
            TrashNetwork.RequestTakeTrashPayload::handle
        );

        registrar.playToClient(
            SyncCommonConfigValuesPayload.TYPE, 
            SyncCommonConfigValuesPayload.STREAM_CODEC, 
            SyncCommonConfigValuesPayload::handle
        );
        registrar.playToServer(
            UploadAndDistributePayload.TYPE, 
            UploadAndDistributePayload.STREAM_CODEC, 
            UploadAndDistributePayload::handle
        );
        registrar.playToServer(
            RequestSyncPayload.TYPE, 
            RequestSyncPayload.STREAM_CODEC, 
            RequestSyncPayload::handle
        );

        registrar.playToServer(
            TrashNetwork.RequestEquipTrashPayload.TYPE,
            TrashNetwork.RequestEquipTrashPayload.STREAM_CODEC,
            TrashNetwork.RequestEquipTrashPayload::handle
        );

        registrar.playToServer(
            CleanNbtPayload.TYPE,
            CleanNbtPayload.STREAM_CODEC,
            (payload, context) -> {
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        InventoryCleaner.cleanAndRestack(serverPlayer);
                    }
                });
            }
        );
    }

    private void onServerStarted(ServerStartedEvent event) {
        TrashCleanerService.init(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        // 釋放伺服器參考與快取，防止單人遊戲切換存檔發生記憶體洩漏
        TrashCleanerService.cleanup();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        TrashCleanerService.onServerTick(event.getServer());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        VTCommandRegistry.register(event.getDispatcher());
    }
}