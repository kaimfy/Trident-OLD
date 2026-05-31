package com.trident;

import net.minecraft.client.MinecraftClient;

public class MovementController {
    private static boolean moving = false;
    private static boolean moveLeft = true;
    private static long lastSwitchTime = 0;

    public static void toggleMovement() {
        moving = !moving;
    }

    public static void tick(MinecraftClient client) {
        if (!moving || client.player == null) {
            resetKeys(client);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastSwitchTime >= 1000) {
            moveLeft = !moveLeft;
            lastSwitchTime = now;
        }

        client.options.leftKey.setPressed(moveLeft);
        client.options.rightKey.setPressed(!moveLeft);
    }

    private static void resetKeys(MinecraftClient client) {
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }
}
