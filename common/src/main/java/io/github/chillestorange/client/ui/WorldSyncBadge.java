package io.github.chillestorange.client.ui;

import io.github.chillestorange.config.GameSyncConfig;
import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.GameSyncService;
import io.github.chillestorange.service.SyncStatusStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * Sync status and toggle button drawn at the end of a world entry's bottom
 * line. The top right corner is left free since other mods like to draw there.
 * Everything is read from GameSyncConfig and SyncStatusStore every frame, so
 * the status updates while a sync is running.
 */
public final class WorldSyncBadge {

    /** Horizontal space reserved on the bottom line; vanilla's info text is narrowed by this. */
    public static final int COLUMN_WIDTH = 96;

    // Vanilla's third text line is at contentY + 9 + 9 + 3.
    private static final int LINE_Y_OFFSET = 9 + 9 + 3;
    private static final int TOGGLE_WIDTH = 13;
    private static final int TOGGLE_HEIGHT = 11;
    private static final int STATUS_GAP = 4;

    private static final int COLOR_ON = 0xFF55FF55;
    private static final int COLOR_OFF = 0xFF909090;
    private static final int COLOR_SYNCING = 0xFFFFDD55;
    private static final int COLOR_FAILED = 0xFFFF5555;
    private static final int COLOR_MUTED = 0xFF808080;
    private static final int TOGGLE_BG = 0x66000000;
    private static final int TOGGLE_BORDER = 0xFF505050;
    private static final int TOGGLE_BORDER_HOVER = 0xFFFFFFFF;

    private static final DateTimeFormatter TOOLTIP_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);

    private WorldSyncBadge() {
    }

    public static void render(GuiGraphicsExtractor graphics, Font font, String levelId,
                              int right, int top, int mouseX, int mouseY) {
        boolean enabled = GameSyncConfig.isTargetWorld(levelId);

        // --- Toggle ---
        Component toggleLabel = Component.translatable("gamesync.world.toggle");
        int x0 = right - TOGGLE_WIDTH;
        int y0 = top + LINE_Y_OFFSET - 1;
        boolean hovered = isOverToggle(right, top, mouseX, mouseY);

        graphics.fill(x0, y0, right, y0 + TOGGLE_HEIGHT, TOGGLE_BG);
        graphics.outline(x0, y0, TOGGLE_WIDTH, TOGGLE_HEIGHT, hovered ? TOGGLE_BORDER_HOVER : TOGGLE_BORDER);
        graphics.text(font, toggleLabel, x0 + (TOGGLE_WIDTH - font.width(toggleLabel) + 1) / 2, y0 + 2,
                enabled ? COLOR_ON : COLOR_OFF, false);

        if (hovered) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable(
                    enabled ? "gamesync.world.toggle.on.tooltip" : "gamesync.world.toggle.off.tooltip"), 220), mouseX, mouseY);
        }

        // --- Status ---
        if (!enabled && !GameSyncService.isSyncing(levelId)) {
            return;
        }

        SyncStatusStore.WorldStatus status = SyncStatusStore.get(levelId);
        Component statusText;
        int color;
        Component tooltip = null;

        if (GameSyncService.isSyncing(levelId)) {
            statusText = Component.translatable("gamesync.world.status.syncing",
                    ".".repeat((int) (System.currentTimeMillis() / 400 % 4)));
            color = COLOR_SYNCING;
        } else if (status == null || (status.lastSuccessMillis() == 0 && status.lastFailureMillis() == 0)) {
            statusText = Component.translatable("gamesync.world.status.never");
            color = COLOR_MUTED;
        } else if (status.lastAttemptFailed()) {
            statusText = Component.translatable("gamesync.world.status.failed");
            color = COLOR_FAILED;
            tooltip = Component.translatable("gamesync.world.status.failed.tooltip",
                    formatTime(status.lastFailureMillis()), status.lastError());
        } else {
            statusText = Component.translatable("gamesync.world.status.synced", relativeTime(status.lastSuccessMillis()));
            color = COLOR_ON;
            tooltip = Component.translatable("gamesync.world.status.synced.tooltip", formatTime(status.lastSuccessMillis()));
        }

        int statusRight = x0 - STATUS_GAP;
        int sx = statusRight - font.width(statusText);
        int sy = top + LINE_Y_OFFSET;
        graphics.text(font, statusText, sx, sy, color, false);

        if (tooltip != null && mouseX >= sx && mouseX < statusRight && mouseY >= sy && mouseY < sy + 9) {
            graphics.setTooltipForNextFrame(font, font.split(tooltip, 250), mouseX, mouseY);
        }
    }

    public static boolean isOverToggle(int right, int top, double mouseX, double mouseY) {
        int y0 = top + LINE_Y_OFFSET - 1;
        return mouseX >= right - TOGGLE_WIDTH && mouseX < right && mouseY >= y0 && mouseY < y0 + TOGGLE_HEIGHT;
    }

    /**
     * Turning sync on also starts a sync right away.
     */
    public static void toggle(Minecraft minecraft, String levelId) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        boolean enable = !GameSyncConfig.isTargetWorld(levelId);
        GameSyncConfig.setTargetWorld(levelId, enable);
        GameSyncLogger.info("Sync {} for world {}", enable ? "enabled" : "disabled", levelId);

        if (enable) {
            GameSyncService.runSyncCycle(minecraft.getLevelSource().getLevelPath(levelId), levelId,
                    () -> {
                    }, _ -> {
                    });
        }
    }

    private static Component relativeTime(long epochMillis) {
        long seconds = Math.max(0, (System.currentTimeMillis() - epochMillis) / 1000);
        if (seconds < 60) return Component.translatable("gamesync.world.time.now");
        if (seconds < 3600) return Component.translatable("gamesync.world.time.minutes", seconds / 60);
        if (seconds < 86400) return Component.translatable("gamesync.world.time.hours", seconds / 3600);
        return Component.translatable("gamesync.world.time.days", seconds / 86400);
    }

    private static String formatTime(long epochMillis) {
        return TOOLTIP_TIME.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
    }
}
