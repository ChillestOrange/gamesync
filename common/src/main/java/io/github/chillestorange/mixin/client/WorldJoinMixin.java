package io.github.chillestorange.mixin.client;

import io.github.chillestorange.client.ui.SyncingScreen;
import io.github.chillestorange.config.GameSyncConfig;
import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.GameSyncService;
import io.github.chillestorange.util.WorldDataHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

@Mixin(WorldSelectionList.WorldListEntry.class)
public class WorldJoinMixin {

    @Unique
    private static final String LEVEL_DAT = "level.dat";

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private LevelSummary summary;

    @Shadow
    @Final
    private WorldSelectionList list;

    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void gamesync$joinWorld(CallbackInfo ci) {

        String levelId = summary.getLevelId();
        if (!GameSyncConfig.isTargetWorld(levelId)) {
            return;
        }

        ci.cancel();

        Screen previousScreen = minecraft.screen;

        // TODO: Add functionality to cancel sync and return to world selection menu.
        minecraft.setScreen(new SyncingScreen());

        Path worldPath = minecraft.getLevelSource().getLevelPath(levelId);
        GameSyncLogger.info("Starting join-triggered sync for world {}", levelId);

        GameSyncService.runSyncCycle(
                worldPath,
                levelId,
                () -> gamesync$onSyncComplete(levelId, worldPath),
                error -> gamesync$onSyncFailed(previousScreen, levelId, error)
        );
    }

    @Unique
    private void gamesync$onSyncComplete(String levelId, Path worldPath) {
        UUID uuid = minecraft.getUser().getProfileId();
        Path levelDatPath = worldPath.resolve(LEVEL_DAT);

        // Exceptions intentionally limited to those documented by
        // WorldDataHelper.updateSingleplayerUuid().
        try {
            WorldDataHelper.updateSingleplayerUuid(levelDatPath, uuid);
            GameSyncLogger.debug("Updated singleplayer_uuid in level.dat to {} (path={})", uuid, levelDatPath);
        } catch (IOException | IllegalStateException e) {
            GameSyncLogger.error("Failed to update singleplayer_uuid in {}. Opening world anyway", levelDatPath, e);
        }

        minecraft.execute(() ->
                minecraft.createWorldOpenFlows().openWorld(levelId, list::returnToScreen));
    }

    @Unique
    private void gamesync$onSyncFailed(Screen previousScreen, String levelId, Throwable error) {
        GameSyncLogger.error("Sync failed for world {}", levelId, error);

        minecraft.execute(() -> {
            if (minecraft.screen instanceof SyncingScreen) {
                minecraft.setScreen(previousScreen);
            }
        });
    }
}