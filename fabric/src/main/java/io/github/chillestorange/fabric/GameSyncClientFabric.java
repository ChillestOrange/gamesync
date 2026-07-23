package io.github.chillestorange.fabric;

import io.github.chillestorange.client.AutosaveSyncListener;
import io.github.chillestorange.config.GameSyncConfig;
import io.github.chillestorange.fabric.client.ui.FabricSyncHud;
import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.GameSyncService;
import net.fabricmc.api.ClientModInitializer;

public final class GameSyncClientFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        GameSyncConfig.load();
        GameSyncLogger.setDebugEnabled(GameSyncConfig.debugMode());
        FabricSyncHud.register();
        AutosaveSyncListener.register();
        GameSyncService.initialize(
                GameSyncConfig.providerType(),
                GameSyncConfig.credentials(),
                GameSyncConfig.remoteFolderId(),
                GameSyncConfig.configDir()
        );
    }
}