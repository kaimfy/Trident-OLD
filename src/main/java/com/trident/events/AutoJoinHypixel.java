package com.trident.events;

import com.trident.core.RestartManager;
import com.trident.modules.SeaLumies;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

public class AutoJoinHypixel {
    private static int timer = 0;
    private static int connectionAttempts = 0;
    private static long lastAttemptTime = 0;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!SeaLumies.macrotoggle && !RestartManager.wantsAutoJoin()) return;

            // Don't try to connect if already on a server or in a menu
            if (client.getNetworkHandler() != null || client.world != null) return;


            timer++;
            if (timer >= 200) { // Increased to 10 seconds between attempts
                long currentTime = System.currentTimeMillis();

                // Rate limiting: wait longer between attempts if many failures
                if (connectionAttempts > 3) {
                    if (currentTime - lastAttemptTime < 30000) { // 30 seconds cooldown
                        timer = 0;
                        return;
                    }
                }

                timer = 0;
                connectionAttempts++;
                lastAttemptTime = currentTime;

                try {
                    ServerAddress address = new ServerAddress("mc.hypixel.net", 25565);
                    ServerInfo info = new ServerInfo("Hypixel", "mc.hypixel.net", ServerInfo.ServerType.OTHER);

                    // Set up proper server info
                    info.setResourcePackPolicy(ServerInfo.ResourcePackPolicy.ENABLED);

                    ConnectScreen.connect(
                            new MultiplayerScreen(null),
                            client,
                            address,
                            info,
                            false,
                            null // Use default cookie storage
                    );

                } catch (Exception e) {
                    System.err.println("Failed to connect to Hypixel: " + e.getMessage());
                    connectionAttempts++; // Count failed attempts
                }
            }
        });
    }

    // Reset attempts on successful connection
    public static void onSuccessfulConnection() {
        connectionAttempts = 0;
    }
}