package io.github.chillestorange.client;

import io.github.chillestorange.config.GameSyncConfig;
import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.platform.PlatformServices;
import io.github.chillestorange.service.GameSyncService;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

public final class AutosaveSyncListener {

    private static final int CONFIG_RELOAD_INTERVAL_TICKS = 100;

    private AutosaveSyncListener() {
    }

    public static void register() {
        PlatformServices.EVENTS.registerServerTickEnd(AutosaveSyncListener::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        if (!(server instanceof IntegratedServer)) {
            return;
        }

        if (server.getTickCount() % CONFIG_RELOAD_INTERVAL_TICKS == 0) {
            GameSyncConfig.load();
        }

        GameSyncConfig config = GameSyncConfig.HANDLER.instance();

        if (!config.autosaveSyncEnabled) {
            return;
        }

        int intervalTicks = config.autosaveIntervalTicks;
        if (intervalTicks <= 0 || server.getTickCount() % intervalTicks != 0) {
            return;
        }

        String worldName = server.getWorldData().getLevelName();
        if (!config.targetWorld.equals(worldName)) {
            return;
        }

        Path worldPath = PlatformServices.PLATFORM.getGameDirectory()
                .resolve("saves")
                .resolve(worldName);

        GameSyncLogger.info("Detected autosave tick for target world, starting sync: world=", worldName);

        GameSyncService.runSyncCycle(worldPath);
    }
}