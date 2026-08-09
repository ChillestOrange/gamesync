package io.github.chillestorange.mixin.client;

import io.github.chillestorange.client.ui.SyncingScreen;
import io.github.chillestorange.config.GameSyncConfig;
import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.GameSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(MinecraftServer.class)
public class WorldSaveMixin {

    @Shadow
    @Final
    protected LevelStorageSource.LevelStorageAccess storageSource;

    @Inject(method = "stopServer", at = @At("TAIL"))
    private void gamesync$stopServer(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;

        if (!(server instanceof IntegratedServer)) {
            return;
        }

        String levelId = storageSource.getLevelId();
        if (!GameSyncConfig.isTargetWorld(levelId)) {
            return;
        }

        Path worldPath = storageSource.getLevelPath(LevelResource.ROOT);

        GameSyncLogger.info("Target world closed, starting upload sync for world {}", levelId);

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new SyncingScreen()));

        GameSyncService.runSyncCycle(
                worldPath,
                levelId,
                () -> {
                    GameSyncLogger.info("Upload sync complete for world {}", levelId);
                    gamesync$returnToTitleIfStillSyncing(minecraft);
                },
                error -> {
                    GameSyncLogger.error("Upload sync failed for world {}", levelId, error);
                    gamesync$returnToTitleIfStillSyncing(minecraft);
                }
        );
    }

    @Unique
    private void gamesync$returnToTitleIfStillSyncing(Minecraft minecraft) {
        minecraft.execute(() -> {
            if (minecraft.screen instanceof SyncingScreen) {
                minecraft.setScreen(new TitleScreen());
            }
        });
    }
}