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

import java.util.*;

/**
 * Foraging module — walks between trees and chops one log per tree (Treecapitator).
 * Only active in foraging zones (Park, Galatea).
 * Mirrors DwarvenMining humanization: Gaussian delays, sinusoidal jitter,
 * look-around, micro-pauses, anti-pattern variance.
 */
@SuppressWarnings("null")
public class Foraging {
    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final Random rand = new Random();

    private static boolean active = false;
    private static BlockPos currentTarget = null;
    private static Vec3d currentTargetOffset = Vec3d.ZERO;
    private static float targetYaw, targetPitch;
    private static float currentRotateSpeed = 0.12f;
    private static int delayTicks = 0;
    private static boolean isChopping = false;

    // After breaking a log, we hold attack for a few extra ticks for Treecapitator
    private static int chopHoldTicks = 0;
    private static final int CHOP_HOLD_DURATION = 5;

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

    // Walking variation
    private static int strafeChangeTicks = 0;
    private static boolean strafeLeft = false;
    private static boolean strafeRight = false;

    // Anti-pattern delay tracking
    private static final int DELAY_HISTORY_SIZE = 8;
    private static final int[] delayHistory = new int[DELAY_HISTORY_SIZE];
    private static int delayHistoryIndex = 0;

    private static int totalActiveTicks = 0;

    // Set of recently broken tree positions to avoid re-targeting stumps
    private static final Set<BlockPos> recentlyBroken = new LinkedHashSet<>();
    private static final int MAX_RECENT = 20;

    // Log block type -> display name (matches the GUI dropdown options).
    private static final Map<Block, String> LOG_BLOCKS = new LinkedHashMap<>();
    static {
        LOG_BLOCKS.put(Blocks.OAK_LOG, "Oak");
        LOG_BLOCKS.put(Blocks.SPRUCE_LOG, "Spruce");
        LOG_BLOCKS.put(Blocks.BIRCH_LOG, "Birch");
        LOG_BLOCKS.put(Blocks.JUNGLE_LOG, "Jungle");
        LOG_BLOCKS.put(Blocks.ACACIA_LOG, "Acacia");
        LOG_BLOCKS.put(Blocks.DARK_OAK_LOG, "Dark Oak");
        LOG_BLOCKS.put(Blocks.MANGROVE_LOG, "Mangrove");
        LOG_BLOCKS.put(Blocks.CHERRY_LOG, "Cherry");
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.world == null)
                return;

            while (KeyHandler.foragingMacro.wasPressed()) {
                toggle();
            }

            if (!active)
                return;

            if (mc.currentScreen != null && mc.currentScreen.shouldPause()) {
                stop();
                return;
            }

            // Zone check — only operate in foraging zones
            if (!WorldChecker.isForagingZone()) {
                // Don't stop, just idle — player might be traveling to the zone
                mc.options.attackKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                return;
            }

            totalActiveTicks++;
            MacroConfig config = MacroConfig.get();

            // ===== Micro-pause =====
            if (microPauseTicks > 0) {
                microPauseTicks--;
                mc.options.attackKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
                return;
            }

            if (currentTarget == null && rand.nextFloat() < config.foragingMicroPauseChance * 0.05f) {
                microPauseTicks = 10 + rand.nextInt(20);
                return;
            }

            // ===== Look-around =====
            if (config.foragingLookAround && !isLookingAround) {
                lookAroundCooldown--;
                if (lookAroundCooldown <= 0 && currentTarget == null) {
                    startLookAround();
                }
            }

            if (isLookingAround) {
                tickLookAround(mc.player);
                return;
            }

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            // ===== Chop hold — keep attack pressed briefly after block breaks =====
            if (chopHoldTicks > 0) {
                chopHoldTicks--;
                mc.options.attackKey.setPressed(true);
                mc.options.forwardKey.setPressed(false);
                if (chopHoldTicks <= 0) {
                    mc.options.attackKey.setPressed(false);
                    isChopping = false;
                    // Delay before finding next tree
                    int delay = gaussianDelay(config.foragingDelayMean, config.foragingDelayStddev, 3, 25);
                    delay = applyAntiPattern(delay);
                    delayTicks = delay;
                }
                return;
            }

            // Check if current target is broken
            if (currentTarget != null) {
                BlockState state = mc.world.getBlockState(currentTarget);
                if (state.isAir() || !isTargetLog(state.getBlock(), config)) {
                    // Tree was chopped (Treecapitator cleared it)
                    mc.options.attackKey.setPressed(false);
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    mc.options.leftKey.setPressed(false);
                    mc.options.rightKey.setPressed(false);

                    addRecentlyBroken(currentTarget);
                    currentTarget = null;
                    isChopping = false;
                    strafeLeft = false;
                    strafeRight = false;

                    // Brief hold for Treecapitator to finish
                    chopHoldTicks = CHOP_HOLD_DURATION;
                    return;
                }
            }

            // Find new target
            if (currentTarget == null) {
                currentTarget = findNextTree(mc.player, config);
                if (currentTarget == null) {
                    mc.options.attackKey.setPressed(false);
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    mc.options.leftKey.setPressed(false);
                    mc.options.rightKey.setPressed(false);
                    return;
                }
                currentTargetOffset = randomBlockFaceOffset();
                currentRotateSpeed = gaussianFloat(config.foragingRotationSpeedMean, 0.04f, 0.06f, 0.25f);
                regenerateJitter();
            }

            updateTargetAngles();
            updateViewSmooth(1.0f);

            double distance = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(currentTarget));

            if (distance > 4.0) {
                // Walk towards tree
                mc.options.attackKey.setPressed(false);
                isChopping = false;

                float yawDiff = MathHelper.wrapDegrees(mc.player.getYaw() - targetYaw);
                if (Math.abs(yawDiff) < 15.0f) {
                    mc.options.forwardKey.setPressed(true);
                    mc.options.sprintKey.setPressed(true);
                } else {
                    mc.options.forwardKey.setPressed(false);
                }

                tickWalkingVariation(mc);

                if (mc.player.horizontalCollision) {
                    mc.options.jumpKey.setPressed(true);
                } else {
                    mc.options.jumpKey.setPressed(false);
                }
            } else {
                // Close enough — stop walking, start chopping
                mc.options.forwardKey.setPressed(false);
                mc.options.sprintKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
                strafeLeft = false;
                strafeRight = false;

                float yawDiff = MathHelper.wrapDegrees(mc.player.getYaw() - targetYaw);
                float pitchDiff = MathHelper.wrapDegrees(mc.player.getPitch() - targetPitch);

                if (Math.abs(yawDiff) < 5.0f && Math.abs(pitchDiff) < 5.0f) {
                    mc.options.attackKey.setPressed(true);
                    isChopping = true;
                }
            }
        });
    }

    // ===== Tree finding =====

    private static boolean isTargetLog(Block block, MacroConfig config) {
        String name = LOG_BLOCKS.get(block);
        return name != null && name.equals(config.foragingTreeType);
    }

    /**
     * Finds the bottom-most log of the nearest tree (so Treecapitator works optimally).
     */
    private static BlockPos findNextTree(ClientPlayerEntity player, MacroConfig config) {
        Vec3d eyePos = player.getEyePos();
        double radius = config.foragingScanRadius;
        int minX = (int) Math.floor(eyePos.x - radius);
        int maxX = (int) Math.floor(eyePos.x + radius);
        int minY = (int) Math.floor(eyePos.y - radius);
        int maxY = (int) Math.floor(eyePos.y + radius);
        int minZ = (int) Math.floor(eyePos.z - radius);
        int maxZ = (int) Math.floor(eyePos.z + radius);

        // Collect bottom-most log of each tree trunk
        List<BlockPos> candidates = new ArrayList<>();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    Block block = state.getBlock();

                    if (!isTargetLog(block, config))
                        continue;
                    if (recentlyBroken.contains(pos))
                        continue;
                    if (eyePos.distanceTo(Vec3d.ofCenter(pos)) > radius)
                        continue;

                    // Check if this is the bottom log (block below is NOT a log)
                    BlockPos below = pos.down();
                    BlockState belowState = client.world.getBlockState(below);
                    if (!LOG_BLOCKS.containsKey(belowState.getBlock())) {
                        candidates.add(pos);
                    }
                }
            }
        }

        if (candidates.isEmpty())
            return null;

        // Sort by distance
        candidates.sort((p1, p2) -> {
            double d1 = eyePos.distanceTo(Vec3d.ofCenter(p1));
            double d2 = eyePos.distanceTo(Vec3d.ofCenter(p2));
            return Double.compare(d1, d2);
        });

        // Imperfect selection (same as DwarvenMining)
        if (candidates.size() >= 3 && rand.nextFloat() < 0.15f) {
            return candidates.get(1 + rand.nextInt(Math.min(2, candidates.size() - 1)));
        }
        if (candidates.size() >= 2 && rand.nextFloat() < 0.10f) {
            return candidates.get(1);
        }

        return candidates.get(0);
    }

    private static void addRecentlyBroken(BlockPos pos) {
        recentlyBroken.add(pos);
        if (recentlyBroken.size() > MAX_RECENT) {
            Iterator<BlockPos> it = recentlyBroken.iterator();
            it.next();
            it.remove();
        }
    }

    // ===== Gaussian/Random Helpers =====

    private static int gaussianDelay(float mean, float stddev, int min, int max) {
        int delay = (int) Math.round(mean + rand.nextGaussian() * stddev);
        return Math.max(min, Math.min(max, delay));
    }

    private static float gaussianFloat(float mean, float stddev, float min, float max) {
        float val = (float) (mean + rand.nextGaussian() * stddev);
        return Math.max(min, Math.min(max, val));
    }

    private static int applyAntiPattern(int delay) {
        delayHistory[delayHistoryIndex % DELAY_HISTORY_SIZE] = delay;
        delayHistoryIndex++;
        if (delayHistoryIndex >= DELAY_HISTORY_SIZE) {
            float sum = 0, sumSq = 0;
            for (int d : delayHistory) {
                sum += d;
                sumSq += d * d;
            }
            float mean = sum / DELAY_HISTORY_SIZE;
            float variance = (sumSq / DELAY_HISTORY_SIZE) - (mean * mean);
            if (variance < 2.0f) {
                delay += 8 + rand.nextInt(15);
            }
        }
        return delay;
    }

    private static Vec3d randomBlockFaceOffset() {
        float spread = 0.35f;
        return new Vec3d(
                (rand.nextFloat() - 0.5f) * spread * 2,
                (rand.nextFloat() - 0.5f) * spread * 2,
                (rand.nextFloat() - 0.5f) * spread * 2
        );
    }

    private static void regenerateJitter() {
        jitterFreqYaw = 0.05f + rand.nextFloat() * 0.10f;
        jitterFreqPitch = 0.04f + rand.nextFloat() * 0.08f;
        jitterAmpYaw = 0.2f + rand.nextFloat() * 0.6f;
        jitterAmpPitch = 0.15f + rand.nextFloat() * 0.5f;
        jitterPhaseYaw = rand.nextFloat() * (float) (2 * Math.PI);
        jitterPhasePitch = rand.nextFloat() * (float) (2 * Math.PI);
    }

    // ===== Rotation =====

    private static void updateTargetAngles() {
        ClientPlayerEntity player = client.player;
        if (player == null || currentTarget == null) return;
        Vec3d playerPos = player.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(currentTarget).add(currentTargetOffset);
        Vec3d targetVec = targetCenter.subtract(playerPos);

        double dx = targetVec.x;
        double dz = targetVec.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        targetYaw = (float) (MathHelper.atan2(dz, dx) * (180f / Math.PI) - 90f);
        targetPitch = (float) -(MathHelper.atan2(targetVec.y, distXZ) * (180f / Math.PI));

        targetYaw = MathHelper.wrapDegrees(targetYaw);
        targetPitch = MathHelper.wrapDegrees(targetPitch);
    }

    private static void updateViewSmooth(float delta) {
        if (!active || client.player == null || currentTarget == null) return;
        ClientPlayerEntity player = client.player;

        float speed = currentRotateSpeed * (0.85f + rand.nextFloat() * 0.30f);

        float currentYaw = MathHelper.lerpAngleDegrees(speed * delta, player.getYaw(), targetYaw);
        float currentPitch = MathHelper.lerpAngleDegrees(speed * delta, player.getPitch(), targetPitch);

        if (isChopping) {
            jitterPhaseYaw += jitterFreqYaw;
            jitterPhasePitch += jitterFreqPitch;
            currentYaw += (float) Math.sin(jitterPhaseYaw) * jitterAmpYaw;
            currentPitch += (float) Math.sin(jitterPhasePitch) * jitterAmpPitch;
        }

        player.setYaw(currentYaw);
        player.setPitch(currentPitch);
        FailsafeManager.updateExpectedRotation(currentYaw, currentPitch);
    }

    // ===== Look-Around =====

    private static void startLookAround() {
        isLookingAround = true;
        lookAroundTicks = 30 + rand.nextInt(30);
        lookAroundYawOffset = (rand.nextFloat() - 0.5f) * 60f;
        lookAroundPitchOffset = (rand.nextFloat() - 0.5f) * 20f;
    }

    private static void tickLookAround(ClientPlayerEntity player) {
        if (player == null) return;
        lookAroundTicks--;

        float progress = 1.0f - ((float) lookAroundTicks / 60f);
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

        float baseYaw = player.getYaw();
        float basePitch = player.getPitch();
        player.setYaw(baseYaw + easedYaw * 0.05f);
        player.setPitch(basePitch + easedPitch * 0.05f);
        FailsafeManager.updateExpectedRotation(player.getYaw(), player.getPitch());

        if (lookAroundTicks <= 0) {
            isLookingAround = false;
            lookAroundCooldown = 200 + rand.nextInt(200);
        }
    }

    private static float smoothStep(float t) {
        return t * t * (3f - 2f * t);
    }

    // ===== Walking Variation =====

    private static void tickWalkingVariation(MinecraftClient mc) {
        strafeChangeTicks--;
        if (strafeChangeTicks <= 0) {
            strafeChangeTicks = 10 + rand.nextInt(20);
            float r = rand.nextFloat();
            if (r < 0.3f) { strafeLeft = true; strafeRight = false; }
            else if (r < 0.6f) { strafeLeft = false; strafeRight = true; }
            else { strafeLeft = false; strafeRight = false; }
        }
        mc.options.leftKey.setPressed(strafeLeft);
        mc.options.rightKey.setPressed(strafeRight);
    }

    // ===== Toggle / Start / Stop =====

    public static void toggle() {
        if (active) stop();
        else start();
    }

    public static void start() {
        if (client.player == null) return;
        active = true;
        currentTarget = null;
        isChopping = false;
        chopHoldTicks = 0;
        totalActiveTicks = 0;
        microPauseTicks = 0;
        isLookingAround = false;
        lookAroundCooldown = 200 + rand.nextInt(200);
        delayHistoryIndex = 0;
        strafeLeft = false;
        strafeRight = false;
        recentlyBroken.clear();
        client.player.sendMessage(Text.literal("§3[§bTrident§3] §aForaging Macro Started!"), false);

        if (!WorldChecker.isForagingZone()) {
            client.player.sendMessage(Text.literal("§3[§bTrident§3] §eNot in a foraging zone (Park/Galatea). Will idle until you arrive."), false);
        }
    }

    public static void stop() {
        if (!active) return;
        active = false;
        currentTarget = null;
        isChopping = false;
        chopHoldTicks = 0;
        isLookingAround = false;
        strafeLeft = false;
        strafeRight = false;
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§3[§bTrident§3] §cForaging Macro Stopped!"), false);
            client.options.attackKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
        }
    }

    public static boolean isActive() {
        return active;
    }
}
