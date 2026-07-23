package io.github.chillestorange.neoforge;

import io.github.chillestorange.GameSyncConstants;
import io.github.chillestorange.client.AutosaveSyncListener;
import io.github.chillestorange.config.GameSyncConfig;
import io.github.chillestorange.config.GameSyncConfigScreen;
import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.GameSyncService;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(GameSyncConstants.MOD_ID)
public class GameSyncClientNeoForge {

    public GameSyncClientNeoForge(IEventBus modEventBus) {
        if (FMLEnvironment.getDist() != Dist.CLIENT) {
            throw new IllegalStateException(GameSyncConstants.MOD_ID + " is a client-only mod and must not run on a dedicated server.");
        }

        ModLoadingContext.get().registerExtensionPoint(
                IConfigScreenFactory.class,
                () -> (client, parent) -> GameSyncConfigScreen.createScreen(parent)
        );

        modEventBus.addListener(GameSyncClientNeoForge::onClientSetup);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        GameSyncConfig.load();
        GameSyncLogger.setDebugEnabled(GameSyncConfig.debugMode());
        AutosaveSyncListener.register();
        GameSyncService.initialize(
                GameSyncConfig.providerType(),
                GameSyncConfig.credentials(),
                GameSyncConfig.remoteFolderId(),
                GameSyncConfig.configDir()
        );
    }
}
