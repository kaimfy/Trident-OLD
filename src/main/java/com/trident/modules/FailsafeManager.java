package com.trident.modules;

import com.trident.config.MacroConfig;
import com.trident.core.TridentLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.Random;
import java.util.Set;

public class FailsafeManager {
    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final Random rand = new Random();

    // Rotation tracking
    private static float lastYaw = 0;
    private static float lastPitch = 0;
    private static float expectedYaw = 0;
    private static float expectedPitch = 0;
    private static int safeTicks = 0;

    // Teleportation tracking
    private static Vec3d lastPosition = null;

    // Inventory tracking
    private static int[] hotbarHashes = new int[9];

    // Reaction sequence state
    private static boolean reacting = false;
    private static int reactionTicksLeft = 0;
    private static int reactionPhase = 0;
    private static String reactionReason = "";
    private static float reactionLookYaw = 0;
    private static float reactionLookPitch = 0;
    private static int reactionWalkDir = 0; // 0=none, 1=forward, 2=left, 3=right, 4=back

    // Entity tracking
    private static int nearbyEntityCount = 0;

    // Chat patterns
    private static final String[] SUSPICIOUS_CHAT_PATTERNS = {
            "admin", "moderator", "has messaged you", "banned",
            "are you there", "hello?", "afk check", "afk?",
            "are you botting", "are you macroing", "staff",
            "watchdog", "suspicious", "warning"
    };

    // Unnatural blocks
    private static final Set<Block> STAFFCHECK_BLOCKS = Set.of(
            Blocks.BEDROCK, Blocks.BARRIER, Blocks.STRUCTURE_VOID,
            Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK
    );

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.world == null)
                return;
            MacroConfig config = MacroConfig.get();

            if (!config.failsafeEnabled)
                return;

            // Handle ongoing reaction sequence
            if (reacting) {
                tickReaction(mc.player);
                return;
            }

            // Only run failsafes if any macro is active
            if (!isAnyMacroActive()) {
                safeTicks = 0;
                lastYaw = mc.player.getYaw();
                lastPitch = mc.player.getPitch();
                lastPosition = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                snapshotHotbar(mc.player);
                return;
            }

            safeTicks++;
            if (safeTicks < 20)
                return; // Wait 1 second before enabling checks after toggle

            // ===== Detection Checks =====
            if (config.failsafeTeleportDetect) {
                checkTeleportation(mc.player);
            }
            if (config.failsafeRotationSnapDetect) {
                checkRotationSnap(mc.player);
            }
            if (config.failsafeInventoryDetect) {
                checkInventoryChange(mc.player);
            }
            checkBedrockBox(mc.player);
            checkPlayerProximity(mc.player);
            checkEntitySpawn(mc.player);

            // Update tracking state
            lastYaw = mc.player.getYaw();
            lastPitch = mc.player.getPitch();
            lastPosition = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            snapshotHotbar(mc.player);
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay)
                return;
            MacroConfig config = MacroConfig.get();
            if (!config.failsafeEnabled || !isAnyMacroActive())
                return;

            String text = message.getString().toLowerCase(Locale.ROOT);
            for (String pattern : SUSPICIOUS_CHAT_PATTERNS) {
                if (text.contains(pattern)) {
                    triggerFailsafe("Suspicious chat: \"" + pattern + "\"");
                    return;
                }
            }
        });
    }

    // ===== Detection Methods =====

    private static void checkTeleportation(ClientPlayerEntity player) {
        if (lastPosition == null) {
            lastPosition = new Vec3d(player.getX(), player.getY(), player.getZ());
            return;
        }
        Vec3d currentPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        double dist = currentPos.distanceTo(lastPosition);
        // If moved >10 blocks in a single tick and we didn't cause it
        if (dist > 10.0) {
            triggerFailsafe("Teleportation detected! (moved " + String.format("%.1f", dist) + " blocks)");
        }
    }

    private static void checkRotationSnap(ClientPlayerEntity player) {
        float yawDiff = Math.abs(MathHelper_wrapDegrees(player.getYaw() - expectedYaw));
        float pitchDiff = Math.abs(MathHelper_wrapDegrees(player.getPitch() - expectedPitch));

        // If rotation changed >45° from where we expected it, and the macro was setting it,
        // something external moved the camera
        if (yawDiff > 45.0f || pitchDiff > 45.0f) {
            triggerFailsafe("Rotation snap detected! (Δyaw=" + String.format("%.1f", yawDiff)
                    + ", Δpitch=" + String.format("%.1f", pitchDiff) + ")");
        }
    }

    private static float MathHelper_wrapDegrees(float degrees) {
        float f = degrees % 360.0f;
        if (f >= 180.0f) f -= 360.0f;
        if (f < -180.0f) f += 360.0f;
        return f;
    }

    private static void checkInventoryChange(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            int hash = stack.isEmpty() ? 0 : stack.getItem().hashCode() * 31 + stack.getCount();
            if (hotbarHashes[i] != 0 && hotbarHashes[i] != hash && safeTicks > 40) {
                // Hotbar changed unexpectedly
                triggerFailsafe("Hotbar slot " + i + " changed unexpectedly!");
                return;
            }
        }
    }

    private static void snapshotHotbar(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            hotbarHashes[i] = stack.isEmpty() ? 0 : stack.getItem().hashCode() * 31 + stack.getCount();
        }
    }

    private static void checkBedrockBox(ClientPlayerEntity player) {
        BlockPos playerPos = player.getBlockPos();
        int suspiciousCount = 0;

        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 3; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    Block block = client.world.getBlockState(pos).getBlock();
                    if (STAFFCHECK_BLOCKS.contains(block)) {
                        suspiciousCount++;
                    }
                }
            }
        }

        if (suspiciousCount > 5) {
            triggerFailsafe("Unnatural block cage detected! (" + suspiciousCount + " blocks)");
        }
    }

    private static void checkPlayerProximity(ClientPlayerEntity player) {
        for (PlayerEntity otherPlayer : client.world.getPlayers()) {
            if (otherPlayer == player)
                continue;

            if (player.distanceTo(otherPlayer) < 3.0f) {
                triggerFailsafe("Player in close proximity: " + otherPlayer.getName().getString());
                return;
            }
        }
    }

    private static void checkEntitySpawn(ClientPlayerEntity player) {
        // Count entities within 5 blocks; if it spikes compared to last check, flag it
        int count = 0;
        for (Entity entity : client.world.getEntities()) {
            if (entity != player && entity.squaredDistanceTo(player) < 25.0) { // 5^2
                count++;
            }
        }
        // If >5 entities appeared in one tick that weren't there before
        if (count > nearbyEntityCount + 5 && safeTicks > 40) {
            triggerFailsafe("Entity spawn surge detected! (" + (count - nearbyEntityCount) + " new)");
        }
        nearbyEntityCount = count;
    }

    // ===== Trigger =====

    public static void triggerFailsafe(String reason) {
        MacroConfig config = MacroConfig.get();

        if (reacting) return; // Don't re-trigger during reaction

        if (client.player != null) {
            client.player.sendMessage(Text.literal("§c[Trident Failsafe] Triggered: " + reason), false);
            client.world.playSound(client.player, client.player.getBlockPos(),
                    SoundEvents.ENTITY_ENDER_DRAGON_GROWL,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
        }

        // Stop all macros immediately
        stopAllMacros();

        // Notify Discord
        notifyDiscord(reason);

        if (config.failsafeReactNaturally) {
            // Start natural reaction sequence instead of immediate escape
            reacting = true;
            reactionTicksLeft = config.failsafeReactionDurationTicks;
            reactionPhase = 0;
            reactionReason = reason;
            reactionLookYaw = (rand.nextFloat() - 0.5f) * 80f; // ±40°
            reactionLookPitch = (rand.nextFloat() - 0.5f) * 30f;
            reactionWalkDir = rand.nextInt(4) + 1;
        } else {
            // Immediate escape
            executeEscapeAction(reason);
        }
    }

    // ===== Natural Reaction Sequence =====

    private static void tickReaction(ClientPlayerEntity player) {
        if (player == null) {
            reacting = false;
            return;
        }

        reactionTicksLeft--;
        MacroConfig config = MacroConfig.get();
        int totalDuration = config.failsafeReactionDurationTicks;

        // Phase calculation (% of total duration)
        float progress = 1.0f - ((float) reactionTicksLeft / totalDuration);

        if (progress < 0.3f) {
            // Phase 1: Look around randomly (0% - 30%)
            float phaseProgress = progress / 0.3f;
            float yawDelta = reactionLookYaw * smoothStep(phaseProgress) * 0.03f;
            float pitchDelta = reactionLookPitch * smoothStep(phaseProgress) * 0.03f;
            player.setYaw(player.getYaw() + yawDelta);
            player.setPitch(player.getPitch() + pitchDelta);
        } else if (progress < 0.45f) {
            // Phase 2: Brief pause (30% - 45%)
            // Do nothing — just stand still
        } else if (progress < 0.65f) {
            // Phase 3: Walk in a random direction (45% - 65%)
            client.options.forwardKey.setPressed(reactionWalkDir == 1);
            client.options.leftKey.setPressed(reactionWalkDir == 2);
            client.options.rightKey.setPressed(reactionWalkDir == 3);
            client.options.backKey.setPressed(reactionWalkDir == 4);
        } else if (progress < 0.80f) {
            // Phase 4: Stop walking (65% - 80%)
            client.options.forwardKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.backKey.setPressed(false);

            // Optionally send a chat message
            if (reactionPhase == 0 && config.failsafeSendChat) {
                reactionPhase = 1;
                String[] msgs = {"?", "hm", "lag", "wut"};
                String msg = msgs[rand.nextInt(msgs.length)];
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatMessage(msg);
                }
            }
        }

        // End of reaction — execute escape
        if (reactionTicksLeft <= 0) {
            reacting = false;
            client.options.forwardKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.backKey.setPressed(false);
            executeEscapeAction(reactionReason);
        }
    }

    private static float smoothStep(float t) {
        return t * t * (3f - 2f * t);
    }

    // ===== Escape Actions =====

    private static void executeEscapeAction(String reason) {
        MacroConfig config = MacroConfig.get();

        if ("WARP".equalsIgnoreCase(config.failsafeAction)) {
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendChatCommand("lobby");
            }
        } else if ("DISCONNECT".equalsIgnoreCase(config.failsafeAction)) {
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().getConnection().disconnect(
                        Text.literal("Failsafe Triggered: " + reason));
            }
        }
        // "WARN" = just stops macros and alerts (already done)
    }

    // ===== Helpers =====

    private static boolean isAnyMacroActive() {
        if (DwarvenMiningModule.isActive()) return true;
        // AutoFish is an instance in TridentLoader — check via the loader
        // MobActionModule
        if (MobActionModule.isOn) return true;
        return false;
    }

    private static void stopAllMacros() {
        DwarvenMiningModule.stop();
        MobActionModule.stop();
        // AutoFish stop would need to be called from TridentLoader since it's instanced
        // We fire it through the loader if available
        if (client.player != null) {
            client.options.attackKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
        }
    }

    private static void notifyDiscord(String reason) {
        try {
            // Will be wired to DiscordIntegration.sendFailsafe()
            com.trident.network.DiscordIntegration.sendFailsafe(reason);
        } catch (Exception e) {
            // Discord not available, ignore
        }
    }

    public static void updateExpectedRotation(float yaw, float pitch) {
        expectedYaw = yaw;
        expectedPitch = pitch;
        lastYaw = yaw;
        lastPitch = pitch;
    }

    public static boolean isReacting() {
        return reacting;
    }
}
