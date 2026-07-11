package com.trident.modules;

import com.trident.config.MacroConfig;
import com.trident.events.KeyHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

@SuppressWarnings("null")
public class DwarvenMining {
    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final Random rand = new Random();

    private static boolean active = false;
    private static BlockPos currentTarget = null;
    private static Vec3d currentTargetOffset = Vec3d.ZERO;
    private static float targetYaw, targetPitch;
    private static float currentRotateSpeed = 0.12f;
    private static int delayTicks = 0;
    private static boolean isMining = false;

    // ===== Humanization state =====
    // Sinusoidal jitter
    private static float jitterPhaseYaw = 0f;
    private static float jitterPhasePitch = 0f;
    private static float jitterFreqYaw = 0.08f;
    private static float jitterFreqPitch = 0.06f;
    private static float jitterAmpYaw = 0.4f;
    private static float jitterAmpPitch = 0.3f;

    // Micro-pause
    private static int microPauseTicks = 0;

    // Look-around
    private static int lookAroundCooldown = 0;
    private static int lookAroundTicks = 0;
    private static float lookAroundYawOffset = 0f;
    private static float lookAroundPitchOffset = 0f;
    private static boolean isLookingAround = false;

    // Walking variation
    private static int strafeChangeTicks = 0;
    private static boolean strafeLeft = false;
    private static boolean strafeRight = false;

    // Anti-pattern: track recent delays
    private static final int DELAY_HISTORY_SIZE = 8;
    private static final int[] delayHistory = new int[DELAY_HISTORY_SIZE];
    private static int delayHistoryIndex = 0;

    // Per-frame rotation timing (smooth camera, framerate-independent)
    private static long lastFrameNanos = 0;
    // Last broken block — the next pick prefers its neighbors (vein sweeping)
    private static BlockPos lastBrokenPos = null;

    private static final Set<Block> MITHRIL_BLOCKS = Set.of(
            Blocks.PRISMARINE,
            Blocks.PRISMARINE_BRICKS,
            Blocks.DARK_PRISMARINE,
            Blocks.CYAN_TERRACOTTA,
            Blocks.LIGHT_GRAY_WOOL,
            Blocks.GRAY_WOOL,
            Blocks.BLUE_WOOL,
            Blocks.LIGHT_BLUE_WOOL);

    private static final Set<Block> TITANIUM_BLOCKS = Set.of(
            Blocks.POLISHED_DIORITE,
            Blocks.DIORITE,
            Blocks.END_STONE);

    public static void init() {
        // Rotation runs per render frame (not per tick): applying yaw/pitch at
        // 20Hz makes the camera visibly step, per-frame lerp looks like a hand.
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES
                .register(context -> onRenderFrame());

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.world == null)
                return;

            // Handle Key Toggle
            while (KeyHandler.dwarvenMacro.wasPressed()) {
                toggle();
            }

            if (!active)
                return;

            if (mc.currentScreen != null && mc.currentScreen.shouldPause()) {
                stop();
                return;
            }

            MacroConfig config = MacroConfig.get();

            // ===== Micro-pause check =====
            if (microPauseTicks > 0) {
                microPauseTicks--;
                mc.options.attackKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
                return;
            }

            // Random micro-pause trigger (only when idle/between targets)
            if (currentTarget == null && rand.nextFloat() < config.dwarvenMicroPauseChance * 0.05f) {
                microPauseTicks = 10 + rand.nextInt(20); // 10-30 ticks pause
                return;
            }

            // ===== Look-around behavior =====
            if (config.dwarvenLookAround && !isLookingAround) {
                lookAroundCooldown--;
                if (lookAroundCooldown <= 0 && currentTarget == null) {
                    startLookAround();
                }
            }

            if (isLookingAround) {
                tickLookAround(mc.player);
                return; // Don't mine while looking around
            }

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            // Check if current target is broken
            if (currentTarget != null) {
                BlockState state = mc.world.getBlockState(currentTarget);
                if (state.isAir() || state.isOf(Blocks.BEDROCK)) {
                    // Block broken
                    mc.options.attackKey.setPressed(false);
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    mc.options.leftKey.setPressed(false);
                    mc.options.rightKey.setPressed(false);
                    lastBrokenPos = currentTarget;
                    currentTarget = null;
                    isMining = false;
                    strafeLeft = false;
                    strafeRight = false;

                    // Gaussian delay with anti-pattern — kept short: adjacent
                    // blocks chain quickly, long stares read as "thinking"
                    int delay = gaussianDelay(config.dwarvenDelayMean, config.dwarvenDelayStddev, 1, 12);
                    delay = applyAntiPattern(delay);
                    delayTicks = delay;
                    return;
                }
            }

            // Find new target if we don't have one
            if (currentTarget == null) {
                currentTarget = findNextTarget(mc.player, config);
                if (currentTarget == null) {
                    // No blocks found, just wait
                    mc.options.attackKey.setPressed(false);
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    mc.options.leftKey.setPressed(false);
                    mc.options.rightKey.setPressed(false);
                    return;
                }
                // Generate random aim point on the block face (not always center)
                currentTargetOffset = randomBlockFaceOffset();
                // Generate new rotation speed for this target (Gaussian)
                currentRotateSpeed = gaussianFloat(config.dwarvenRotationSpeedMean, 0.04f, 0.06f, 0.25f);
                // Regenerate jitter parameters
                regenerateJitter();
            }

            updateTargetAngles();

            double distance = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(currentTarget));

            if (distance > 4.5) {
                // Walk towards target
                mc.options.attackKey.setPressed(false);
                isMining = false;

                float yawDiff = MathHelper.wrapDegrees(mc.player.getYaw() - targetYaw);
                if (Math.abs(yawDiff) < 10.0f) {
                    mc.options.forwardKey.setPressed(true);
                } else {
                    mc.options.forwardKey.setPressed(false);
                }

                // Walking variation: random strafing
                tickWalkingVariation(mc);

                // Jump if colliding horizontally
                if (mc.player.horizontalCollision) {
                    mc.options.jumpKey.setPressed(true);
                } else {
                    mc.options.jumpKey.setPressed(false);
                }
            } else {
                // Stop walking and start mining
                mc.options.forwardKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
                strafeLeft = false;
                strafeRight = false;

                float yawDiff = MathHelper.wrapDegrees(mc.player.getYaw() - targetYaw);
                float pitchDiff = MathHelper.wrapDegrees(mc.player.getPitch() - targetPitch);

                if (Math.abs(yawDiff) < 5.0f && Math.abs(pitchDiff) < 5.0f) {
                    boolean lookingAtValid = false;
                    if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                        BlockPos hitPos = ((BlockHitResult) mc.crosshairTarget).getBlockPos();
                        Block hitBlock = mc.world.getBlockState(hitPos).getBlock();
                        if (MITHRIL_BLOCKS.contains(hitBlock) || TITANIUM_BLOCKS.contains(hitBlock)) {
                            lookingAtValid = true;
                        }
                    }

                    if (lookingAtValid) {
                        mc.options.attackKey.setPressed(true);
                        isMining = true;
                    } else {
                        mc.options.attackKey.setPressed(false);
                        isMining = false;
                        
                        // If we've reached the target angles but crosshair isn't on a valid block,
                        // it's obscured by an entity or another block. Pick a new target.
                        currentTarget = null;
                        delayTicks = 5;
                    }
                }
            }
        });
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

    /**
     * Anti-pattern: if recent delays are too similar, inject extra variance.
     */
    private static int applyAntiPattern(int delay) {
        delayHistory[delayHistoryIndex % DELAY_HISTORY_SIZE] = delay;
        delayHistoryIndex++;

        if (delayHistoryIndex >= DELAY_HISTORY_SIZE) {
            // Check if last N delays have low variance
            float sum = 0, sumSq = 0;
            for (int d : delayHistory) {
                sum += d;
                sumSq += d * d;
            }
            float mean = sum / DELAY_HISTORY_SIZE;
            float variance = (sumSq / DELAY_HISTORY_SIZE) - (mean * mean);

            if (variance < 2.0f) {
                // Too uniform — inject a deviation (kept moderate, a long
                // freeze reads as lag, not as a human)
                int extraDelay = 5 + rand.nextInt(8);
                delay += extraDelay;
            }
        }
        return delay;
    }

    /**
     * Generate a random offset on a random face of the block (not always center).
     */
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
        // Subtle hand sway — anything bigger reads as shaking
        jitterAmpYaw = 0.08f + rand.nextFloat() * 0.25f;
        jitterAmpPitch = 0.06f + rand.nextFloat() * 0.20f;
        jitterPhaseYaw = rand.nextFloat() * (float) (2 * Math.PI);
        jitterPhasePitch = rand.nextFloat() * (float) (2 * Math.PI);
    }

    // ===== Target Finding with Imperfect Selection =====

    private static BlockPos findNextTarget(ClientPlayerEntity player, MacroConfig config) {
        Vec3d eyePos = player.getEyePos();
        double radius = 15.0;
        int minX = (int) Math.floor(eyePos.x - radius);
        int maxX = (int) Math.floor(eyePos.x + radius);
        int minY = (int) Math.floor(eyePos.y - radius);
        int maxY = (int) Math.floor(eyePos.y + radius);
        int minZ = (int) Math.floor(eyePos.z - radius);
        int maxZ = (int) Math.floor(eyePos.z + radius);

        List<BlockPos> candidates = new ArrayList<>();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    Block block = state.getBlock();

                    if ((MITHRIL_BLOCKS.contains(block) || TITANIUM_BLOCKS.contains(block)) &&
                            eyePos.distanceTo(Vec3d.ofCenter(pos)) <= radius) {
                        
                        RaycastContext context = new RaycastContext(
                                eyePos,
                                Vec3d.ofCenter(pos),
                                RaycastContext.ShapeType.COLLIDER,
                                RaycastContext.FluidHandling.NONE,
                                player
                        );
                        BlockHitResult hit = client.world.raycast(context);
                        if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                            candidates.add(pos);
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty())
            return null;

        // Rank by convenience, not raw distance: turning the head costs like
        // walking does, and the neighbor of the block we just broke is the
        // natural next pick — a human sweeps a face/vein, they don't pivot to
        // whatever is nearest behind them.
        double yawRad = Math.toRadians(player.getYaw());
        double pitchRad = Math.toRadians(player.getPitch());
        double xzLen = Math.cos(pitchRad);
        Vec3d look = new Vec3d(-Math.sin(yawRad) * xzLen, -Math.sin(pitchRad), Math.cos(yawRad) * xzLen);

        candidates.sort((p1, p2) -> Double.compare(
                targetScore(p1, eyePos, look, config),
                targetScore(p2, eyePos, look, config)));

        // Imperfect selection: 15% chance to skip the best and pick 2nd or 3rd
        if (candidates.size() >= 3 && rand.nextFloat() < 0.15f) {
            return candidates.get(1 + rand.nextInt(Math.min(2, candidates.size() - 1)));
        }
        if (candidates.size() >= 2 && rand.nextFloat() < 0.10f) {
            return candidates.get(1);
        }

        return candidates.get(0);
    }

    /** Lower is better: distance + turn cost - vein continuity - yield - titanium priority. */
    private static double targetScore(BlockPos pos, Vec3d eyePos, Vec3d look, MacroConfig config) {
        Vec3d to = Vec3d.ofCenter(pos).subtract(eyePos);
        double dist = to.length();
        double angleDeg = Math.toDegrees(Math.acos(
                MathHelper.clamp(look.dotProduct(to.normalize()), -1.0, 1.0)));
        double score = dist + angleDeg / 25.0;          // a 90-degree pivot "costs" ~3.6 blocks

        if (lastBrokenPos != null
                && pos.getSquaredDistance(lastBrokenPos) <= 6.25) {
            score -= 4.0;                                // keep sweeping the same face/vein
        }
        score -= yieldBonus(client.world.getBlockState(pos).getBlock());
        if (config.dwarvenPrioritizeTitanium
                && TITANIUM_BLOCKS.contains(client.world.getBlockState(pos).getBlock())) {
            score -= 1000.0;                             // titanium always outranks mithril
        }
        return score;
    }

    /**
     * Mithril blocks aren't equal — value per block strength decides:
     *   Light Blue Wool: 1500 strength, 5 Mithril + 5 Powder (best yield/swing)
     *   Gray Wool:        800 strength, 2 Mithril + 3 Powder
     *   Cyan Terracotta:  500 strength, 1 Mithril + 1 Powder (weakest yield)
     */
    private static double yieldBonus(Block block) {
        if (block == Blocks.LIGHT_BLUE_WOOL) return 3.0;
        if (block == Blocks.GRAY_WOOL) return 1.5;
        if (block == Blocks.CYAN_TERRACOTTA) return 0.5;
        return 0.0;
    }

    // ===== Rotation =====

    private static void updateTargetAngles() {
        ClientPlayerEntity player = client.player;
        if (player == null || currentTarget == null)
            return;
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

    /**
     * Runs once per render frame. The lerp factor is scaled by real frame time
     * (in tick units), so the turn speed is identical at 30 or 300 fps —
     * {@code currentRotateSpeed} keeps meaning "fraction per tick".
     */
    private static void onRenderFrame() {
        if (!active || client.player == null || currentTarget == null || isLookingAround
                || microPauseTicks > 0 || delayTicks > 0) {
            lastFrameNanos = 0;
            return;
        }
        ClientPlayerEntity player = client.player;

        long now = System.nanoTime();
        float dtTicks = lastFrameNanos == 0 ? 0.05f : (now - lastFrameNanos) / 50_000_000f;
        lastFrameNanos = now;
        dtTicks = MathHelper.clamp(dtTicks, 0f, 1f);

        updateTargetAngles();

        float t = 1f - (float) Math.pow(1f - currentRotateSpeed, dtTicks);
        float currentYaw = MathHelper.lerpAngleDegrees(t, player.getYaw(), targetYaw);
        float currentPitch = MathHelper.lerpAngleDegrees(t, player.getPitch(), targetPitch);

        // Sinusoidal jitter, frame-time scaled so its amplitude is fps-independent
        if (isMining) {
            jitterPhaseYaw += jitterFreqYaw * dtTicks;
            jitterPhasePitch += jitterFreqPitch * dtTicks;
            currentYaw += (float) Math.sin(jitterPhaseYaw) * jitterAmpYaw * dtTicks;
            currentPitch += (float) Math.sin(jitterPhasePitch) * jitterAmpPitch * dtTicks;
        }

        player.setYaw(currentYaw);
        player.setPitch(MathHelper.clamp(currentPitch, -90f, 90f));
        FailsafeManager.updateExpectedRotation(player.getYaw(), player.getPitch());
    }

    // ===== Look-Around Behavior =====

    private static void startLookAround() {
        isLookingAround = true;
        lookAroundTicks = 30 + rand.nextInt(30); // 30-60 ticks
        lookAroundYawOffset = (rand.nextFloat() - 0.5f) * 60f; // ±30 degrees
        lookAroundPitchOffset = (rand.nextFloat() - 0.5f) * 20f; // ±10 degrees
    }

    private static void tickLookAround(ClientPlayerEntity player) {
        if (player == null) return;
        lookAroundTicks--;

        float progress = 1.0f - ((float) lookAroundTicks / 60f);
        // Smooth ease: pan out then back
        float easedYaw, easedPitch;
        if (progress < 0.5f) {
            // Pan out
            float t = progress * 2f;
            easedYaw = lookAroundYawOffset * smoothStep(t);
            easedPitch = lookAroundPitchOffset * smoothStep(t);
        } else {
            // Pan back
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
            lookAroundCooldown = 200 + rand.nextInt(200); // 200-400 ticks until next
        }
    }

    private static float smoothStep(float t) {
        return t * t * (3f - 2f * t);
    }

    // ===== Walking Variation =====

    private static void tickWalkingVariation(MinecraftClient mc) {
        strafeChangeTicks--;
        if (strafeChangeTicks <= 0) {
            strafeChangeTicks = 10 + rand.nextInt(20); // change strafe every 10-30 ticks
            float r = rand.nextFloat();
            if (r < 0.3f) {
                strafeLeft = true;
                strafeRight = false;
            } else if (r < 0.6f) {
                strafeLeft = false;
                strafeRight = true;
            } else {
                strafeLeft = false;
                strafeRight = false;
            }
        }
        mc.options.leftKey.setPressed(strafeLeft);
        mc.options.rightKey.setPressed(strafeRight);
    }

    // ===== Toggle / Start / Stop =====

    public static void toggle() {
        if (active) {
            stop();
        } else {
            start();
        }
    }

    public static void start() {
        if (client.player == null)
            return;
        active = true;
        currentTarget = null;
        lastBrokenPos = null;
        lastFrameNanos = 0;
        isMining = false;
        microPauseTicks = 0;
        isLookingAround = false;
        lookAroundCooldown = 200 + rand.nextInt(200);
        delayHistoryIndex = 0;
        strafeLeft = false;
        strafeRight = false;
        client.player.sendMessage(Text.literal("§3[§bTrident§3] §aDwarven Mining Macro Started!"), false);
    }

    public static void stop() {
        if (!active)
            return;
        active = false;
        currentTarget = null;
        isMining = false;
        isLookingAround = false;
        strafeLeft = false;
        strafeRight = false;
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§3[§bTrident§3] §cDwarven Mining Macro Stopped!"), false);
            client.options.attackKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
        }
    }

    public static boolean isActive() {
        return active;
    }
}
