package io.github.chillestorange.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chillestorange.client.ui.WorldSyncBadge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds WorldSyncBadge to entries on the singleplayer world selection screen.
 * WorldSelectionList is reused by other screens (Realms upload), hence the
 * SelectWorldScreen check.
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntrySyncBadgeMixin {

    @Shadow
    @Final
    private WorldSelectionList list;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private Screen screen;

    @Shadow
    @Final
    private LevelSummary summary;

    @Shadow
    @Final
    private StringWidget infoText;

    @Shadow
    private int getTextX() {
        throw new AssertionError();
    }

    /**
     * Makes room for the badge on the bottom line.
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void gamesync$narrowText(CallbackInfo ci) {
        if (!gamesync$enabled()) return;
        // Same as vanilla's maxTextWidth.
        int narrowed = list.getRowWidth() - getTextX() - 2 - WorldSyncBadge.COLUMN_WIDTH;
        if (minecraft.font.width(infoText.getMessage()) > narrowed) {
            infoText.setTooltip(Tooltip.create(infoText.getMessage()));
        }
        infoText.setMaxWidth(narrowed);
    }

    // HEAD since extractContent returns early for locked/corrupted worlds.
    @Inject(method = "extractContent", at = @At("HEAD"))
    private void gamesync$drawBadge(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a,
                                    CallbackInfo ci) {
        if (!gamesync$enabled()) return;
        WorldSyncBadge.render(graphics, minecraft.font, summary.getLevelId(),
                gamesync$self().getContentRight(), gamesync$self().getContentY(), mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void gamesync$clickToggle(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!gamesync$enabled() || event.button() != InputConstants.MOUSE_BUTTON_LEFT) return;
        if (WorldSyncBadge.isOverToggle(gamesync$self().getContentRight(), gamesync$self().getContentY(),
                event.x(), event.y())) {
            WorldSyncBadge.toggle(minecraft, summary.getLevelId());
            cir.setReturnValue(true);
        }
    }

    @Unique
    private boolean gamesync$enabled() {
        return screen instanceof SelectWorldScreen;
    }

    @Unique
    private WorldSelectionList.WorldListEntry gamesync$self() {
        return (WorldSelectionList.WorldListEntry) (Object) this;
    }
}
