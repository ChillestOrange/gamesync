package io.github.chillestorange.client.ui;

import io.github.chillestorange.service.cloud.AuthEvent;
import io.github.chillestorange.service.cloud.AuthEventBus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

public class SyncingScreen extends Screen {

    private static final long FRAME_DURATION_MS = 400;
    private static final String[] DOT_FRAMES = {".", "..", "..."};
    private static final long COPY_FEEDBACK_DURATION_MS = 2000L;

    private static final Component LABEL_COPY = Component.literal("Copy to Clipboard");
    private static final Component LABEL_COPIED = Component.literal("Copied!");

    private static final int TITLE_SPACING = 8;

    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 8;
    private boolean wasFullscreen = false;

    private final long startTimeMillis = System.currentTimeMillis();
    @Nullable
    private String pendingAuthUrl = null;
    @Nullable
    private Button copyButton = null;
    private long copyFeedbackUntilMs = 0L;

    public SyncingScreen() {
        super(Component.literal("Syncing World"));
    }

    @Override
    protected void init() {
        super.init();

        // Re-subscribed here (not just the constructor) so that if the widget
        // system ever rebuilds this screen, the bus doesn't silently lose
        // its registration.
        AuthEventBus.subscribe(event -> this.minecraft.execute(() -> this.handleAuthEvent(event)));

        // The button is always created, just hidden until an auth URL shows
        // up. That way receiving the URL is a one-field flip rather than a
        // full rebuildWidgets() pass.
        int buttonX = (this.width - BUTTON_WIDTH) / 2;
        int buttonY = authBlockTopY() + (this.font.lineHeight + 2) * 3 + BUTTON_GAP;

        this.copyButton = Button.builder(LABEL_COPY, btn -> this.copyUrlToClipboard())
                .bounds(buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.copyButton.visible = (pendingAuthUrl != null);

        addRenderableWidget(this.copyButton);
    }

    @Override
    public void removed() {
        super.removed();
        // Prevent a stale callback firing into a screen that's no longer active.
        AuthEventBus.unsubscribe();
    }

    /**
     * Dispatches on the sealed {@link AuthEvent} type. Exhaustive by
     * construction: if a new event variant is ever added to {@code AuthEvent},
     * this switch fails to compile until it's handled here too — there's no
     * default branch to silently swallow it.
     */
    private void handleAuthEvent(AuthEvent event) {
        switch (event) {
            case AuthEvent.BrowserUnavailable(String authUrl) -> onAuthUrlReceived(authUrl);
            case AuthEvent.Complete ignored -> onAuthComplete();
        }
    }

    private void onAuthUrlReceived(String url) {
        this.pendingAuthUrl = url;
        if (this.copyButton != null) {
            this.copyButton.visible = true;
        }
        // Don't minimize here, 26.3's renderer can't acquire a surface for a
        // minimized window. Window has no fullscreen getter anymore, so read the option.
        this.wasFullscreen = this.minecraft.options.fullscreen().get();
        if(this.wasFullscreen){
            this.minecraft.getWindow().setFullscreen(false);
        }
    }


    private void onAuthComplete() {
        this.pendingAuthUrl = null;
        this.copyFeedbackUntilMs = 0L;
        if (copyButton != null) {
            copyButton.visible = false;
            copyButton.setMessage(LABEL_COPY);
        }

        long windowHandle = this.minecraft.getWindow().handle();
        SDLVideo.SDL_RaiseWindow(windowHandle);

        if(this.wasFullscreen){
            this.minecraft.getWindow().setFullscreen(true);
            this.wasFullscreen = false;
        }
    }

    private void copyUrlToClipboard() {
        if (pendingAuthUrl == null || copyButton == null) return;

        this.minecraft.keyboardHandler.setClipboard(pendingAuthUrl);
        this.copyFeedbackUntilMs = System.currentTimeMillis() + COPY_FEEDBACK_DURATION_MS;
        this.copyButton.setMessage(LABEL_COPIED);
        // Button text reverts in tick() once the feedback duration expires.

        // Reset focus for copy-to-clipboard button
        this.minecraft.execute(this::clearFocus);
    }

    @Override
    public void tick() {
        super.tick();
        if (copyFeedbackUntilMs > 0
                && System.currentTimeMillis() > copyFeedbackUntilMs
                && copyButton != null) {
            copyFeedbackUntilMs = 0L;
            copyButton.setMessage(LABEL_COPY);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);

        int centerX = this.width / 2;

        if (pendingAuthUrl != null) {
            renderAuthState(graphics, centerX);
        } else {
            int centerY = this.height / 2;
            long elapsed = System.currentTimeMillis() - startTimeMillis;
            String dots = DOT_FRAMES[(int) (elapsed / FRAME_DURATION_MS) % DOT_FRAMES.length];
            graphics.centeredText(this.font, "Syncing world" + dots, centerX, centerY - 10, 0xFFFFFFFF);
            renderSpinner(graphics, centerX, centerY + 14, elapsed);
        }
    }

    private void renderSyncState(GuiGraphicsExtractor graphics, int centerX, int centerY) {
        long elapsed = System.currentTimeMillis() - startTimeMillis;
        String dots = DOT_FRAMES[(int) (elapsed / FRAME_DURATION_MS) % DOT_FRAMES.length];

        graphics.centeredText(this.font, "Syncing world" + dots, centerX, centerY - 10, 0xFFFFFFFF);
        renderSpinner(graphics, centerX, centerY + 14, elapsed);
    }

    /**
     * Top Y coordinate of the auth-state block (title + two explanation
     * lines + button). Computed from the total block height so the whole
     * group stays centered as one unit regardless of GUI scale or aspect
     * ratio, instead of each line being offset by a fixed raw amount from
     * centerY. Used by both init() (to place the button) and
     * renderAuthState() (to place the text), so the two never drift apart.
     */
    private int authBlockTopY() {
        int lineHeight = this.font.lineHeight + 2;
        int textBlockHeight = lineHeight * 2; // title + 2 explanation lines
        int totalHeight = textBlockHeight + BUTTON_GAP + BUTTON_HEIGHT;
        return (this.height / 2) - totalHeight / 2;
    }

    private void renderAuthState(GuiGraphicsExtractor graphics, int centerX) {
        int lineHeight = this.font.lineHeight + 2;
        int topY = authBlockTopY();

        graphics.centeredText(this.font,
                "Authorization Required",
                centerX, topY, 0xFFFFFFFF);

        // Explanation — slightly dimmed to create visual hierarchy below the title
        graphics.centeredText(this.font,
                "A browser has opened for authorization." +
                        "If not Press F11 and login , or paste this link if it didn't open:\"",
                centerX, topY + lineHeight + TITLE_SPACING, 0xFFAAAAAA);
//        graphics.centeredText(this.font,
//                "Copy the URL below and paste it into your browser:",
//                centerX, topY + lineHeight * 2, 0xFFAAAAAA);

        // Copy button is rendered by the widget system via addRenderableWidget —
        // no manual draw call needed here. Its Y position is derived from the
        // same authBlockTopY() calculation, so it sits right below this text.
    }

    private void renderSpinner(GuiGraphicsExtractor graphics, int centerX, int centerY, long elapsed) {
        float baseAngle = (elapsed % 1000) / 1000f * 360f;
        int radius = 6;
        int dotCount = 8;

        for (int i = 0; i < dotCount; i++) {
            double theta = Math.toRadians(baseAngle + (360.0 / dotCount) * i);
            int x = centerX + (int) (Math.cos(theta) * radius);
            int y = centerY + (int) (Math.sin(theta) * radius);
            int brightness = 255 - (i * 255 / dotCount);
            int color = 0xFF000000 | (brightness << 16) | (brightness << 8) | brightness;
            graphics.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}