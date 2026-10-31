package io.github.chillestorange.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ClientToastHandler {

    private static final Component TITLE = Component.literal("Game Sync");

    private ClientToastHandler() {
    }

    public static void showSyncStarted() {
        show("Sync started...");
    }

    public static void showSyncFinished() {
        show("World synchronized!");
    }

    public static void showMessage(String message) {
        show(message);
    }

    private static void show(String message) {

        Minecraft minecraft = Minecraft.getInstance();

        minecraft.execute(() -> minecraft.gui.toastManager().addToast(new SyncProgressToast(TITLE, Component.literal(message))));
    }
}