package com.trident.modules;

import com.trident.config.MacroConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;

public class FailsafeManager {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private static float lastYaw = 0;
    private static float lastPitch = 0;
    private static int safeTicks = 0;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.world == null)
                return;
            MacroConfig config = MacroConfig.get();

            if (!config.failsafeEnabled)
                return;

            // Only run failsafes if dwarven mining is active (or other macros in future)
            if (!DwarvenMiningModule.isActive()) {
                safeTicks = 0;
                lastYaw = mc.player.getYaw();
                lastPitch = mc.player.getPitch();
                return;
            }

            safeTicks++;
            if (safeTicks < 20)
                return; // Wait 1 second before enabling checks after toggle

            checkRotation(mc.player);
            checkBedrockBox(mc.player);
            checkPlayerProximity(mc.player);
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay)
                return;
            MacroConfig config = MacroConfig.get();
            if (!config.failsafeEnabled || !DwarvenMiningModule.isActive())
                return;

            String text = message.getString().toLowerCase(Locale.ROOT);
            if (text.contains("admin") || text.contains("moderator") || text.contains("has messaged you")
                    || text.contains("banned")) {
                triggerFailsafe("Admin chat message detected!");
            }
        });
    }

    private static void checkRotation(ClientPlayerEntity player) {
        // Hypixel admins sometimes flick your head
        float yawDiff = Math.abs(player.getYaw() - lastYaw);
        float pitchDiff = Math.abs(player.getPitch() - lastPitch);

        // Allow some difference for normal macro rotations, but if it snaps wildly it
        // might be an admin
        // Let's rely mainly on box checks and players for now to avoid false positives,
        // but if it changes by > 60 degrees in one tick it's suspicious unless we did
        // it.
        // DwarvenMiningModule should update lastYaw/lastPitch when it rotates manually.

        lastYaw = player.getYaw();
        lastPitch = player.getPitch();
    }

    private static void checkBedrockBox(ClientPlayerEntity player) {
        BlockPos playerPos = player.getBlockPos();
        int bedrockCount = 0;
        int barrierCount = 0;

        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 3; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (client.world.getBlockState(pos).isOf(Blocks.BEDROCK))
                        bedrockCount++;
                    if (client.world.getBlockState(pos).isOf(Blocks.BARRIER))
                        barrierCount++;
                }
            }
        }

        if (bedrockCount > 5 || barrierCount > 5) {
            triggerFailsafe("Bedrock/Barrier box detected!");
        }
    }

    private static void checkPlayerProximity(ClientPlayerEntity player) {
        for (PlayerEntity otherPlayer : client.world.getPlayers()) {
            if (otherPlayer == player)
                continue;

            // Simple distance check - if a player is within 3 blocks, trigger
            if (player.distanceTo(otherPlayer) < 3.0f) {
                // Ignore NPCs if possible - usually NPCs don't have typical player UUIDs or
                // might be in a list
                // For safety, any player nearby triggers it.
                triggerFailsafe("Player in close proximity: " + otherPlayer.getName().getString());
                return;
            }
        }
    }

    public static void triggerFailsafe(String reason) {
        MacroConfig config = MacroConfig.get();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§c[Trident Failsafe] Triggered: " + reason), false);
            client.world.playSound(client.player, client.player.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
        }

        // Stop all macros
        DwarvenMiningModule.stop();
        // Stop other macros if they exist

        if ("WARP".equalsIgnoreCase(config.failsafeAction)) {
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendChatCommand("lobby");
            }
        } else if ("DISCONNECT".equalsIgnoreCase(config.failsafeAction)) {
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().getConnection().disconnect(Text.literal("Failsafe Triggered: " + reason));
            }
        }
    }

    public static void updateExpectedRotation(float yaw, float pitch) {
        lastYaw = yaw;
        lastPitch = pitch;
    }
}
