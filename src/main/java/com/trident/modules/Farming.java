package com.trident.modules;

import com.trident.config.MacroConfig;
import com.trident.events.KeyHandler;
import com.trident.events.WorldChecker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;
import java.util.Set;

/**
 * Garden farming macro. Runs a lawnmower-style pattern down crop rows: it snaps to the nearest
 * cardinal heading, walks forward holding attack to break whatever crop is in the lane, and when it
 * runs into the end of a row (movement stalls) it performs a smooth 180° U-turn — optionally
 * strafing one lane over — and continues the other way.
 *
 * <p>Only operates inside the SkyBlock Garden (see {@link WorldChecker#isGardenZone()}). Humanization
 * (Gaussian rotation speed, sinusoidal jitter, micro-pauses, look-around, anti-pattern delay
 * variance) mirrors {@link DwarvenMining} and {@link Foraging}.
 */
@SuppressWarnings("null")
public class Farming {
    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final Random rand = new Random();

    private enum State { FARMING, UTURN }

    private static boolean active = false;
    private static State state = State.FARMING;

    // Heading the macro walks along (snapped to a cardinal at start) and the pitch we hold.
    private static float laneYaw = 0f;
    private static float targetPitch = 18f;
    private static float currentRotateSpeed = 0.12f;

    // End-of-row detection
    private static Vec3d lastPos = Vec3d.ZERO;
    private static int stalledTicks = 0;
    private static final int STALL_LIMIT = 8;

    // Brief natural pause at the end of a row, once the U-turn completes.
    private static int resumeDelayTicks = 0;

    // U-turn progress
    private static float uturnTargetYaw = 0f;
    private static int uturnStrafeTicks = 0;
    private static boolean uturnStrafeLeft = false;

    // ===== Humanization state =====
    private static float jitterPhaseYaw = 0f;
    private static float jitterPhasePitch = 0f;
    private static float jitterFreqYaw = 0.08f;
    private static float jitterFreqPitch = 0.06f;
    private static float jitterAmpYaw = 0.4f;
    private static float jitterAmpPitch = 0.3f;

    private static int microPauseTicks = 0;

    private static int lookAroundCooldown = 0;
    private static int lookAroundTicks = 0;
    private static float lookAroundYawOffset = 0f;
    private static float lookAroundPitchOffset = 0f;
    private static boolean isLookingAround = false;

    // Crops the macro will break (left-click). Configurable via the GUI.
    private static final Set<Block> allCrops = Set.of(
            Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.NETHER_WART,
            Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.PUMPKIN, Blocks.MELON,
            Blocks.COCOA, Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM);

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.world == null)
                return;

            while (KeyHandler.farmingMacro != null && KeyHandler.farmingMacro.wasPressed()) {
                toggle();
            }

            if (!active)
                return;

            if (mc.currentScreen != null && mc.currentScreen.shouldPause()) {
                stop();
                return;
            }

            WorldChecker.tick(mc);

            // Zone gate — idle (don't stop) until the player reaches the Garden.
            if (!WorldChecker.isGardenZone()) {
                releaseKeys(mc);
                return;
            }

            MacroConfig config = MacroConfig.get();

            // ===== Micro-pause =====
            if (microPauseTicks > 0) {
                microPauseTicks--;
                releaseKeys(mc);
                return;
            }
            if (state == State.FARMING && rand.nextFloat() < config.farmingMicroPauseChance * 0.05f) {
                microPauseTicks = 10 + rand.nextInt(20);
                return;
            }

            // ===== Look-around (only while cruising a row) =====
            if (config.farmingLookAround && !isLookingAround && state == State.FARMING) {
                lookAroundCooldown--;
                if (lookAroundCooldown <= 0) {
                    startLookAround();
                }
            }
            if (isLookingAround) {
                tickLookAround(mc.player);
                // keep mowing while glancing around
                mc.options.attackKey.setPressed(true);
                mc.options.forwardKey.setPressed(true);
                return;
            }

            if (resumeDelayTicks > 0) {
                resumeDelayTicks--;
                releaseKeys(mc);
                return;
            }

            if (state == State.UTURN) {
                tickUTurn(mc, config);
                return;
            }

            tickFarming(mc, config);
        });
    }

    // ===== Farming (cruise a row) =====

    private static void tickFarming(MinecraftClient mc, MacroConfig config) {
        ClientPlayerEntity player = mc.player;

        // Smoothly hold the lane heading + downward pitch, with jitter.
        applyRotation(player, laneYaw, targetPitch, true);

        mc.options.forwardKey.setPressed(true);
        mc.options.attackKey.setPressed(true);
        mc.options.sprintKey.setPressed(false);

        // Hop over small obstacles / stay on top of farmland ridges.
        mc.options.jumpKey.setPressed(player.horizontalCollision && player.isOnGround());

        // End-of-row detection: if we've barely moved for a while, we've hit the edge.
        Vec3d curPos = pos(player);
        double movedSq = curPos.squaredDistanceTo(lastPos);
        lastPos = curPos;

        boolean cropAhead = isCropAhead(player, config);
        if (movedSq < 0.0016) { // < 0.04 blocks/tick
            stalledTicks++;
        } else if (!cropAhead && !hasFarmlandAhead(player)) {
            stalledTicks += 2; // ran off the end of the plot — turn sooner
        } else {
            stalledTicks = 0;
        }

        if (stalledTicks >= STALL_LIMIT) {
            beginUTurn(config);
        }
    }

    // ===== U-turn =====

    private static void beginUTurn(MacroConfig config) {
        state = State.UTURN;
        stalledTicks = 0;
        laneYaw = MathHelper.wrapDegrees(laneYaw + 180f);
        uturnTargetYaw = laneYaw;
        currentRotateSpeed = gaussianFloat(config.farmingRotationSpeedMean, 0.04f, 0.06f, 0.25f);
        regenerateJitter();

        if (config.farmingLaneShift) {
            // Alternate the shift direction so we sweep across the plot instead of bouncing in place.
            uturnStrafeLeft = !uturnStrafeLeft;
            uturnStrafeTicks = 6 + rand.nextInt(4);
        } else {
            uturnStrafeTicks = 0;
        }
    }

    private static void tickUTurn(MinecraftClient mc, MacroConfig config) {
        ClientPlayerEntity player = mc.player;

        mc.options.attackKey.setPressed(false);
        applyRotation(player, uturnTargetYaw, targetPitch, false);

        float yawDiff = Math.abs(MathHelper.wrapDegrees(player.getYaw() - uturnTargetYaw));

        // Strafe into the neighbouring lane while turning.
        if (uturnStrafeTicks > 0) {
            mc.options.leftKey.setPressed(uturnStrafeLeft);
            mc.options.rightKey.setPressed(!uturnStrafeLeft);
            mc.options.forwardKey.setPressed(true);
            uturnStrafeTicks--;
        } else {
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
        }

        if (yawDiff < 6.0f && uturnStrafeTicks <= 0) {
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            lastPos = pos(player);
            stalledTicks = 0;
            state = State.FARMING;
            // Natural pause before mowing the next row (Gaussian, in ticks).
            int delay = (int) Math.round(config.farmingDelayMean + rand.nextGaussian() * config.farmingDelayStddev);
            resumeDelayTicks = Math.max(2, Math.min(30, delay));
        }
    }

    // ===== Crop / farmland scanning =====

    private static boolean isCropEnabled(Block block, MacroConfig c) {
        String name = cropName(block);
        return name != null && name.equals(c.farmingCropType);
    }

    /** Display name of a crop block (matches the GUI dropdown options), or null if not a crop. */
    private static String cropName(Block block) {
        if (block == Blocks.WHEAT) return "Wheat";
        if (block == Blocks.CARROTS) return "Carrot";
        if (block == Blocks.POTATOES) return "Potato";
        if (block == Blocks.NETHER_WART) return "Nether Wart";
        if (block == Blocks.SUGAR_CANE) return "Sugar Cane";
        if (block == Blocks.CACTUS) return "Cactus";
        if (block == Blocks.PUMPKIN) return "Pumpkin";
        if (block == Blocks.MELON) return "Melon";
        if (block == Blocks.COCOA) return "Cocoa";
        if (block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM) return "Mushroom";
        return null;
    }

    /** True if an enabled crop sits in the block column just ahead of the player. */
    private static boolean isCropAhead(ClientPlayerEntity player, MacroConfig config) {
        BlockPos ahead = BlockPos.ofFloored(pos(player).add(forward(player).multiply(1.0)));
        for (int dy = 0; dy <= 2; dy++) {
            BlockState state = client.world.getBlockState(ahead.up(dy));
            Block block = state.getBlock();
            if (allCrops.contains(block) && isCropEnabled(block, config)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFarmlandAhead(ClientPlayerEntity player) {
        BlockPos ahead = BlockPos.ofFloored(pos(player).add(forward(player).multiply(1.0))).down();
        Block below = client.world.getBlockState(ahead).getBlock();
        return below == Blocks.FARMLAND || below == Blocks.SOUL_SAND || below == Blocks.DIRT
                || below == Blocks.GRASS_BLOCK || below == Blocks.SAND || below == Blocks.MOSS_BLOCK;
    }

    private static Vec3d forward(ClientPlayerEntity player) {
        float yawRad = (float) Math.toRadians(player.getYaw());
        return new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));
    }

    private static Vec3d pos(ClientPlayerEntity player) {
        return new Vec3d(player.getX(), player.getY(), player.getZ());
    }

    // ===== Rotation =====

    private static void applyRotation(ClientPlayerEntity player, float wantYaw, float wantPitch, boolean jitter) {
        float speed = currentRotateSpeed * (0.85f + rand.nextFloat() * 0.30f);
        float yaw = MathHelper.lerpAngleDegrees(speed, player.getYaw(), wantYaw);
        float pitch = MathHelper.lerpAngleDegrees(speed, player.getPitch(), wantPitch);

        if (jitter) {
            jitterPhaseYaw += jitterFreqYaw;
            jitterPhasePitch += jitterFreqPitch;
            yaw += (float) Math.sin(jitterPhaseYaw) * jitterAmpYaw;
            pitch += (float) Math.sin(jitterPhasePitch) * jitterAmpPitch;
        }

        pitch = MathHelper.clamp(pitch, -20f, 45f);
        player.setYaw(yaw);
        player.setPitch(pitch);
        FailsafeManager.updateExpectedRotation(yaw, pitch);
    }

    // ===== Look-around =====

    private static void startLookAround() {
        isLookingAround = true;
        lookAroundTicks = 20 + rand.nextInt(20);
        lookAroundYawOffset = (rand.nextFloat() - 0.5f) * 30f;
        lookAroundPitchOffset = (rand.nextFloat() - 0.5f) * 14f;
    }

    private static void tickLookAround(ClientPlayerEntity player) {
        if (player == null) return;
        lookAroundTicks--;

        float progress = 1.0f - ((float) lookAroundTicks / 40f);
        float easedYaw, easedPitch;
        if (progress < 0.5f) {
            float t = progress * 2f;
            easedYaw = lookAroundYawOffset * smoothStep(t);
            easedPitch = lookAroundPitchOffset * smoothStep(t);
        } else {
            float t = (progress - 0.5f) * 2f;
            easedYaw = lookAroundYawOffset * (1f - smoothStep(t));
            easedPitch = lookAroundPitchOffset * (1f - smoothStep(t));
        }

        player.setYaw(player.getYaw() + easedYaw * 0.05f);
        player.setPitch(player.getPitch() + easedPitch * 0.05f);
        FailsafeManager.updateExpectedRotation(player.getYaw(), player.getPitch());

        if (lookAroundTicks <= 0) {
            isLookingAround = false;
            lookAroundCooldown = 200 + rand.nextInt(200);
        }
    }

    private static float smoothStep(float t) {
        return t * t * (3f - 2f * t);
    }

    // ===== Helpers =====

    private static float gaussianFloat(float mean, float stddev, float min, float max) {
        float val = (float) (mean + rand.nextGaussian() * stddev);
        return Math.max(min, Math.min(max, val));
    }

    private static void regenerateJitter() {
        jitterFreqYaw = 0.05f + rand.nextFloat() * 0.10f;
        jitterFreqPitch = 0.04f + rand.nextFloat() * 0.08f;
        jitterAmpYaw = 0.2f + rand.nextFloat() * 0.6f;
        jitterAmpPitch = 0.15f + rand.nextFloat() * 0.5f;
        jitterPhaseYaw = rand.nextFloat() * (float) (2 * Math.PI);
        jitterPhasePitch = rand.nextFloat() * (float) (2 * Math.PI);
    }

    private static void releaseKeys(MinecraftClient mc) {
        mc.options.attackKey.setPressed(false);
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
    }

    // ===== Toggle / Start / Stop =====

    public static void toggle() {
        if (active) stop();
        else start();
    }

    public static void start() {
        if (client.player == null) return;
        active = true;
        state = State.FARMING;
        // Snap to the nearest cardinal heading so rows stay straight.
        laneYaw = Math.round(client.player.getYaw() / 90f) * 90f;
        laneYaw = MathHelper.wrapDegrees(laneYaw);
        targetPitch = 18f;
        currentRotateSpeed = 0.12f;
        lastPos = pos(client.player);
        stalledTicks = 0;
        microPauseTicks = 0;
        isLookingAround = false;
        lookAroundCooldown = 200 + rand.nextInt(200);
        uturnStrafeTicks = 0;
        resumeDelayTicks = 0;
        regenerateJitter();

        client.player.sendMessage(Text.literal("§3[§bTrident§3] §aFarming Macro Started!"), false);
        if (!WorldChecker.isGardenZone()) {
            client.player.sendMessage(Text.literal("§3[§bTrident§3] §eNot in the Garden. Will idle until you arrive."), false);
        }
    }

    public static void stop() {
        if (!active) return;
        active = false;
        state = State.FARMING;
        isLookingAround = false;
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§3[§bTrident§3] §cFarming Macro Stopped!"), false);
            releaseKeys(client);
        }
    }

    public static boolean isActive() {
        return active;
    }
}
