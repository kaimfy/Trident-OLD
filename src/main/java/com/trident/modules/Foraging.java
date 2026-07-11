package com.trident.modules;

import com.trident.config.MacroConfig;
import com.trident.events.KeyHandler;
import com.trident.events.WorldChecker;
import com.trident.pathing.TreePathfinder;
import com.trident.pathing.TreeRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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
    // Line-of-sight-verified point on the target block we aim at while chopping
    private static Vec3d currentAimPoint = null;
    private static int blockedTicks = 0;
    private static float targetYaw, targetPitch;
    private static float currentRotateSpeed = 0.12f;
    private static int delayTicks = 0;
    private static boolean isChopping = false;
    private static int zoneWaitTicks = 0;
    private static int noTreeTicks = 0;

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

    // Walking: jump only when genuinely blocked, never spam
    private static int collisionTicks = 0;
    private static int jumpCooldown = 0;

    // Stuck escalation: no movement progress -> hop -> give up on this tree
    private static Vec3d lastProgressPos = Vec3d.ZERO;
    private static int progressTicks = 0;
    private static int stuckStrikes = 0;
    private static boolean forceJump = false;

    // Chop escalation: repeated failed aim points -> climb trunk -> skip tree
    private static int aimResets = 0;

    // Axe throw (right-click ability, ~2s cooldown): fells logs at 5-8 blocks
    private static int axeThrowCooldown = 0;
    private static int throwWaitTicks = 0;
    private static boolean useKeyPressed = false;

    // Throttle full-area rescans while no tree is in sight
    private static int scanCooldown = 0;

    // Planned route: the next 1-2 tree bases after the current one, chosen by
    // nearest-neighbor chaining. Promoted (after re-validation — the sweep may
    // have already felled them) the moment the current tree drops, so there is
    // no "stop and think" between trees.
    private static final List<BlockPos> plannedTargets = new ArrayList<>();
    // Planned trees must be this far apart (squared) to count as different trees
    private static final double MIN_TREE_SEPARATION_SQ = 5.0 * 5.0;

    // Logs that refused to break (the Park portal frame is made of birch logs):
    // learned once per session via the chop watchdog, never targeted again.
    private static final Set<BlockPos> unbreakable = new HashSet<>();
    private static int chopProgressTicks = 0;

    // A* route to the current target (feet waypoints) and follow state
    private static final List<BlockPos> currentPath = new ArrayList<>();
    private static int pathIndex = 0;
    private static boolean pathComputed = false;
    // Route to the NEXT planned tree, computed while still working on the
    // current one so promotion has zero think-time
    private static List<BlockPos> nextPath = null;
    private static BlockPos nextPathFor = null;
    private static boolean nextPathTried = false;
    // Where the camera looks while walking (a waypoint ahead on the path)
    private static Vec3d walkLookPoint = null;

    // Per-frame rotation timing (smooth camera, framerate-independent)
    private static long lastFrameNanos = 0;

    // Anti-pattern delay tracking
    private static final int DELAY_HISTORY_SIZE = 8;
    private static final int[] delayHistory = new int[DELAY_HISTORY_SIZE];
    private static int delayHistoryIndex = 0;

    // Set of recently broken tree positions (bases plus their trunk columns)
    // to avoid re-targeting stumps and leftover trunks
    private static final Set<BlockPos> recentlyBroken = new LinkedHashSet<>();
    private static final int MAX_RECENT = 180;

    // Log block type -> display name (matches the GUI dropdown options).
    // Hypixel builds trees out of bark/stripped variants too, so every
    // variant of a species maps to the same dropdown name.
    private static final Map<Block, String> LOG_BLOCKS = new LinkedHashMap<>();
    private static void addLogVariants(String name, Block... blocks) {
        for (Block b : blocks) LOG_BLOCKS.put(b, name);
    }
    static {
        addLogVariants("Oak", Blocks.OAK_LOG, Blocks.OAK_WOOD, Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_OAK_WOOD);
        addLogVariants("Spruce", Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD, Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_SPRUCE_WOOD);
        addLogVariants("Birch", Blocks.BIRCH_LOG, Blocks.BIRCH_WOOD, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_BIRCH_WOOD);
        addLogVariants("Jungle", Blocks.JUNGLE_LOG, Blocks.JUNGLE_WOOD, Blocks.STRIPPED_JUNGLE_LOG, Blocks.STRIPPED_JUNGLE_WOOD);
        addLogVariants("Acacia", Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD, Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_ACACIA_WOOD);
        addLogVariants("Dark Oak", Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.STRIPPED_DARK_OAK_WOOD);
        // Hypixel Mangrove trees stand on their root blocks — those are part
        // of the trunk for base detection.
        addLogVariants("Mangrove", Blocks.MANGROVE_LOG, Blocks.MANGROVE_WOOD, Blocks.STRIPPED_MANGROVE_LOG,
                Blocks.STRIPPED_MANGROVE_WOOD, Blocks.MANGROVE_ROOTS, Blocks.MUDDY_MANGROVE_ROOTS);
        // "Fig" is Hypixel's retextured cherry wood (no cherry trees exist)
        addLogVariants("Fig", Blocks.CHERRY_LOG, Blocks.CHERRY_WOOD, Blocks.STRIPPED_CHERRY_LOG, Blocks.STRIPPED_CHERRY_WOOD);
    }

    public static void init() {
        // Rotation runs per render frame (not per tick): applying yaw/pitch at
        // 20Hz makes the camera visibly step, per-frame lerp looks like a hand.
        // The same hook draws the target/path overlay.
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            onRenderFrame();
            renderTargetOverlay();
        });

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

            WorldChecker.tick(mc);

            // Axe-throw bookkeeping: the right-click is a single tap (released
            // the tick after), and the ~2s ability cooldown counts down here.
            if (useKeyPressed) {
                mc.options.useKey.setPressed(false);
                useKeyPressed = false;
            }
            if (axeThrowCooldown > 0) axeThrowCooldown--;

            // Zone check — only operate in foraging zones
            if (!WorldChecker.isForagingZone()) {
                // Don't stop, just idle — player might be traveling to the zone
                mc.options.attackKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                // Periodic diagnostic so zone-detection failures are visible in chat
                if (++zoneWaitTicks % 200 == 0) {
                    mc.player.sendMessage(Text.literal("§3[§bTrident§3] §7Waiting for foraging zone — detected: §f"
                            + WorldChecker.getLocation()), false);
                }
                return;
            }
            zoneWaitTicks = 0;

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
                // Only keep holding while the crosshair is still on a log:
                // once the tree is gone it would instant-break the grass behind it.
                BlockPos aimed = crosshairBlock(mc);
                boolean onLog = aimed != null && LOG_BLOCKS.containsKey(mc.world.getBlockState(aimed).getBlock());
                mc.options.attackKey.setPressed(onLog);
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
                    // We only "finished a chop" if we were actually working on it;
                    // a target felled from afar by the sweep just gets replaced,
                    // without stopping to think.
                    boolean wasChoppingIt = isChopping
                            || mc.player.getEyePos().distanceTo(Vec3d.ofCenter(currentTarget)) <= 5.0;

                    // A tree that actually fell is a VERIFIED tree — remember it
                    // (and where we stood) for future targeting and pathfinding.
                    if (wasChoppingIt) {
                        TreeRegistry.recordTree(islandKey(), config.foragingTreeType,
                                currentTarget, mc.player.getBlockPos());
                    }

                    mc.options.attackKey.setPressed(false);
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    mc.options.leftKey.setPressed(false);
                    mc.options.rightKey.setPressed(false);

                    // Blacklist the whole trunk column too — the base is done,
                    // never climb after what's left of this tree.
                    addRecentlyBroken(currentTarget);
                    for (int i = 1; i <= 8; i++) {
                        addRecentlyBroken(currentTarget.up(i));
                    }
                    currentTarget = null;
                    currentAimPoint = null;
                    isChopping = false;

                    // Line up the next tree immediately — the camera starts
                    // drifting toward it during the post-chop pause.
                    promoteNextTarget(mc.player, config);

                    if (wasChoppingIt) {
                        // Brief hold for Treecapitator to finish
                        chopHoldTicks = CHOP_HOLD_DURATION;
                        return;
                    }
                }
            }

            // Find new target (throttled — the extended scan is a lot of blocks)
            if (currentTarget == null) {
                if (scanCooldown > 0) {
                    scanCooldown--;
                } else {
                    currentTarget = findNextTree(mc.player, config);
                }
                if (currentTarget == null) {
                    mc.options.attackKey.setPressed(false);
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    mc.options.leftKey.setPressed(false);
                    mc.options.rightKey.setPressed(false);
                    scanCooldown = Math.max(scanCooldown, 10);
                    // Silent-failure guard: say why we're standing still, and dump
                    // a census of nearby blocks to the log for diagnosis.
                    if (++noTreeTicks % 200 == 0) {
                        mc.player.sendMessage(Text.literal("§3[§bTrident§3] §eNo '" + config.foragingTreeType
                                + "' trees found nearby — check Tree Type in the GUI."), false);
                        dumpNearbyBlocks(mc, config);
                    }
                    return;
                }
                noTreeTicks = 0;
                currentTargetOffset = randomBlockFaceOffset();
                currentAimPoint = null;
                blockedTicks = 0;
                aimResets = 0;
                stuckStrikes = 0;
                progressTicks = 0;
                chopProgressTicks = 0;
                currentPath.clear();
                pathIndex = 0;
                pathComputed = false;
                walkLookPoint = null;
                nextPath = null;
                nextPathFor = null;
                nextPathTried = false;
                lastProgressPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                currentRotateSpeed = gaussianFloat(config.foragingRotationSpeedMean, 0.04f, 0.06f, 0.25f);
                regenerateJitter();
                planAhead(mc.player, config);
            }

            updateTargetAngles();

            double distance = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(currentTarget));

            if (distance > 4.0) {
                mc.options.attackKey.setPressed(false);
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
                isChopping = false;

                // ===== Axe throw =====
                // Just threw: stand still and watch it fly. If it felled the
                // tree, the broken-target check promotes the next one; if not,
                // resume walking when the wait runs out.
                if (throwWaitTicks > 0) {
                    throwWaitTicks--;
                    mc.options.forwardKey.setPressed(false);
                    mc.options.sprintKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    if (throwWaitTicks == 0) currentAimPoint = null;
                    return;
                }
                // A log at comfortable throwing range with a clear line: stop,
                // line up, tap right-click instead of walking the distance.
                if (config.foragingAxeThrow && axeThrowCooldown == 0
                        && distance >= 5.0 && distance <= 8.5) {
                    if (currentAimPoint == null) {
                        currentAimPoint = findVisibleAimPoint(mc.player, currentTarget);
                    }
                    if (currentAimPoint != null) {
                        mc.options.forwardKey.setPressed(false);
                        mc.options.sprintKey.setPressed(false);
                        mc.options.jumpKey.setPressed(false);
                        walkLookPoint = null; // aim at the log, not the path
                        updateTargetAngles();

                        float throwYawDiff = MathHelper.wrapDegrees(mc.player.getYaw() - targetYaw);
                        float throwPitchDiff = MathHelper.wrapDegrees(mc.player.getPitch() - targetPitch);
                        if (Math.abs(throwYawDiff) < 3.0f && Math.abs(throwPitchDiff) < 3.0f
                                && lookRayHitsTrunk(mc.player, config)) {
                            mc.options.useKey.setPressed(true);
                            useKeyPressed = true;
                            axeThrowCooldown = 45 + rand.nextInt(10);
                            throwWaitTicks = 12;
                        }
                        return;
                    }
                    // no clear throwing line — walk up as usual
                }

                // Route to the tree (A*, terrain-aware). Computed once per
                // target; the stall watchdog forces a re-route when needed.
                if (!pathComputed) {
                    pathComputed = true;
                    currentPath.clear();
                    // Mapped tree: route to the exact spot it was chopped from
                    // before — a known-reachable goal beats guessing.
                    BlockPos knownStand = TreeRegistry.standFor(islandKey(), currentTarget);
                    currentPath.addAll(knownStand != null
                            ? TreePathfinder.findPath(mc.world, mc.player.getBlockPos(), knownStand, 1.5)
                            : TreePathfinder.findPath(mc.world, mc.player.getBlockPos(), currentTarget, 3.0));
                    pathIndex = 0;
                }
                // The route to the NEXT tree gets computed while walking this
                // one — promotion is then instant.
                precomputeNextPath(mc);

                // Drop waypoints we've reached — or sailed past: steering at a
                // point behind/underneath the player flips the yaw every frame
                // and makes the camera spin in place.
                Vec3d feet = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                while (pathIndex < currentPath.size()) {
                    BlockPos wp = currentPath.get(pathIndex);
                    boolean reached = horizontalSq(feet, wp) < 1.69
                            && Math.abs(feet.y - wp.getY()) < 1.6;
                    boolean passed = false;
                    if (!reached && pathIndex + 1 < currentPath.size()) {
                        BlockPos nx = currentPath.get(pathIndex + 1);
                        // Passed once we cross the plane through the waypoint,
                        // perpendicular to the path direction — a distance test
                        // leaves a dead zone where the steering target sits
                        // behind the player and makes it step back and forth.
                        double sx = nx.getX() - wp.getX();
                        double sz = nx.getZ() - wp.getZ();
                        double px = feet.x - (wp.getX() + 0.5);
                        double pz = feet.z - (wp.getZ() + 0.5);
                        passed = sx * px + sz * pz > 0;
                    }
                    if (!reached && !passed) break;
                    pathIndex++;
                }

                // Pure pursuit: aim at the farthest waypoint within lookahead
                // range, so the gaze sweeps smoothly through corners instead of
                // snapping at every block.
                BlockPos stepTo = null;
                walkLookPoint = null;
                if (pathIndex < currentPath.size()) {
                    stepTo = currentPath.get(pathIndex);
                    BlockPos look = stepTo;
                    for (int i = pathIndex; i < currentPath.size(); i++) {
                        if (horizontalSq(feet, currentPath.get(i)) > 3.5 * 3.5) break;
                        look = currentPath.get(i);
                    }
                    // Never steer at a point basically underfoot — that's the
                    // yaw-flip spin; the tree itself is always ahead of us.
                    if (horizontalSq(feet, look) >= 1.0) {
                        walkLookPoint = new Vec3d(look.getX() + 0.5, look.getY() + 1.55, look.getZ() + 0.5);
                    }
                }

                float yawDiff = MathHelper.wrapDegrees(mc.player.getYaw() - targetYaw);
                boolean walking = Math.abs(yawDiff) < 35.0f;
                mc.options.forwardKey.setPressed(walking);
                // Sprint on the open stretches, ease off near the tree
                mc.options.sprintKey.setPressed(walking && Math.abs(yawDiff) < 15.0f && distance > 6.5);

                // Jump when the route steps up, or when blocked for a few ticks.
                if (jumpCooldown > 0) jumpCooldown--;
                if (mc.player.horizontalCollision && mc.player.isOnGround()) {
                    collisionTicks++;
                } else {
                    collisionTicks = 0;
                }
                // Jump only for real ledges: stairs/slabs (rise <= 0.6) are
                // walked up automatically, hopping on them looks robotic.
                boolean stepUp = false;
                if (stepTo != null && horizontalSq(feet, stepTo) < 2.6) {
                    double standY = TreePathfinder.standHeight(mc.world, stepTo);
                    stepUp = !Double.isNaN(standY) && standY - mc.player.getY() > 0.7;
                }
                boolean jump = forceJump || stepUp || (collisionTicks >= 4 && jumpCooldown == 0);
                if (jump && !stepUp) {
                    forceJump = false;
                    jumpCooldown = 20 + rand.nextInt(15);
                    collisionTicks = 0;
                }
                mc.options.jumpKey.setPressed(jump);

                // Progress watchdog: not actually moving -> re-route once,
                // still not moving -> give up on this tree.
                if (walking && ++progressTicks >= 20) {
                    progressTicks = 0;
                    Vec3d now = feet;
                    if (now.squaredDistanceTo(lastProgressPos) < 0.25) {
                        stuckStrikes++;
                        if (stuckStrikes == 1) {
                            forceJump = true;
                            pathComputed = false; // terrain read failed — re-route
                        } else {
                            addRecentlyBroken(currentTarget);
                            currentTarget = null;
                            currentAimPoint = null;
                            stuckStrikes = 0;
                            promoteNextTarget(mc.player, config);
                        }
                    } else {
                        stuckStrikes = 0;
                        if (currentPath.isEmpty()) pathComputed = false; // retry failed route
                    }
                    lastProgressPos = now;
                }
            } else {
                // Close enough — stop walking, start chopping
                mc.options.forwardKey.setPressed(false);
                mc.options.sprintKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
                collisionTicks = 0;
                walkLookPoint = null;

                // While chopping, the route to the next tree gets computed in
                // the background so the hand-off is seamless.
                precomputeNextPath(mc);

                // Pick a point on the log we can actually see (grass/leaves may
                // block the randomly chosen face).
                if (currentAimPoint == null) {
                    currentAimPoint = findVisibleAimPoint(mc.player, currentTarget);
                    if (currentAimPoint == null && !resolveBlockedTarget(mc.player, config)) {
                        return; // tree fully occluded — skipped, new target next tick
                    }
                    updateTargetAngles();
                }

                float yawDiff = MathHelper.wrapDegrees(mc.player.getYaw() - targetYaw);
                float pitchDiff = MathHelper.wrapDegrees(mc.player.getPitch() - targetPitch);

                if (Math.abs(yawDiff) < 5.0f && Math.abs(pitchDiff) < 5.0f) {
                    // Never swing unless the crosshair is on this tree's trunk —
                    // not on grass, leaves, players or mobs in front of it.
                    // Any trunk block near the base counts: with thick trunks the
                    // crosshair often clips the block next to/above the exact base.
                    if (isSameTrunk(crosshairBlock(mc), config)) {
                        blockedTicks = 0;
                        aimResets = 0;
                        mc.options.attackKey.setPressed(true);
                        isChopping = true;

                        // Break watchdog: a real tree cracks in well under 4s of
                        // swinging. One that doesn't is unbreakable scenery (the
                        // portal frame is birch logs) — learn it and move on.
                        if (++chopProgressTicks >= 80) {
                            List<BlockPos> column = new ArrayList<>();
                            for (int i = 0; i <= 3; i++) {
                                column.add(currentTarget.up(i));
                                unbreakable.add(currentTarget.up(i));
                            }
                            // Structures don't move — remember them forever
                            TreeRegistry.recordUnbreakable(islandKey(), column);
                            addRecentlyBroken(currentTarget);
                            System.out.println("[Trident] "
                                    + mc.world.getBlockState(currentTarget).getBlock()
                                    + " at " + currentTarget.toShortString()
                                    + " won't break (structure) — permanently blacklisted");
                            mc.options.attackKey.setPressed(false);
                            currentTarget = null;
                            currentAimPoint = null;
                            isChopping = false;
                            chopProgressTicks = 0;
                            promoteNextTarget(mc.player, config);
                            return;
                        }
                    } else {
                        mc.options.attackKey.setPressed(false);
                        isChopping = false;
                        // Aligned but something is in the way — re-aim at a visible
                        // spot (jitter can also drift off the edge for a tick or two);
                        // if that keeps failing, climb the trunk or skip the tree
                        // instead of standing there forever.
                        if (++blockedTicks >= 10) {
                            blockedTicks = 0;
                            currentAimPoint = null;
                            if (++aimResets >= 3) {
                                aimResets = 0;
                                if (!resolveBlockedTarget(mc.player, config)) {
                                    return; // tree skipped, new target next tick
                                }
                            }
                        }
                    }
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
     * True when {@code pos} is part of the current target's trunk near the base:
     * the target itself, or a same-species log within 1 block horizontally
     * (thick trunks) and up to 3 above (the reachable part).
     */
    private static boolean isSameTrunk(BlockPos pos, MacroConfig config) {
        if (pos == null || currentTarget == null) return false;
        if (pos.equals(currentTarget)) return true;
        int dy = pos.getY() - currentTarget.getY();
        if (dy < 0 || dy > 3) return false;
        if (Math.abs(pos.getX() - currentTarget.getX()) > 1
                || Math.abs(pos.getZ() - currentTarget.getZ()) > 1) return false;
        return isTargetLog(client.world.getBlockState(pos).getBlock(), config);
    }

    /**
     * Finds the base of the nearest tree. Starts with the configured scan radius;
     * when nothing is close, retries with an extended radius so the macro walks
     * to the next patch of trees instead of standing around waiting.
     */
    private static BlockPos findNextTree(ClientPlayerEntity player, MacroConfig config) {
        List<BlockPos> candidates = collectBases(player, config, config.foragingScanRadius);
        if (candidates.isEmpty()) {
            candidates = collectBases(player, config, extendedRadius(config));
        }
        if (candidates.isEmpty()) return null;

        Vec3d eyePos = player.getEyePos();
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

    private static double extendedRadius(MacroConfig config) {
        return Math.min(40.0, config.foragingScanRadius * 2.5);
    }

    /** All valid tree bases around the player within {@code radius}. */
    private static List<BlockPos> collectBases(ClientPlayerEntity player, MacroConfig config, double radius) {
        Vec3d eyePos = player.getEyePos();
        // Trees stand on the terrain around us — cap the vertical range instead
        // of scanning a full cube (keeps the extended scan cheap and avoids
        // targeting canopies far above/below).
        double vRange = Math.min(radius, 12);
        int minX = (int) Math.floor(eyePos.x - radius);
        int maxX = (int) Math.floor(eyePos.x + radius);
        int minY = (int) Math.floor(eyePos.y - vRange);
        int maxY = (int) Math.floor(eyePos.y + vRange);
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
                    if (recentlyBroken.contains(pos) || unbreakable.contains(pos)
                            || TreeRegistry.isUnbreakable(islandKey(), pos))
                        continue;
                    if (eyePos.distanceTo(Vec3d.ofCenter(pos)) > radius)
                        continue;

                    // Only true tree bases: the log must stand on solid ground.
                    // A log with air/leaves below is a floating remnant of an
                    // already-chopped tree — never chase those.
                    BlockPos below = pos.down();
                    BlockState belowState = client.world.getBlockState(below);
                    if (!LOG_BLOCKS.containsKey(belowState.getBlock())
                            && !belowState.isAir()
                            && !(belowState.getBlock() instanceof LeavesBlock)) {
                        candidates.add(pos);
                    }
                }
            }
        }

        // Thick trunks produce several "base" blocks per tree — collapse each
        // cluster (within 3 blocks horizontally) to a single representative so
        // one tree is one candidate.
        List<BlockPos> unique = new ArrayList<>();
        for (BlockPos c : candidates) {
            boolean sameTree = false;
            for (BlockPos u : unique) {
                int dx = c.getX() - u.getX();
                int dz = c.getZ() - u.getZ();
                if (dx * dx + dz * dz <= 9 && Math.abs(c.getY() - u.getY()) <= 4) {
                    sameTree = true;
                    break;
                }
            }
            if (!sameTree) unique.add(c);
        }
        // Portal frames are logs too — never target anything near slime blocks
        unique.removeIf(p -> nearSlime(p, 3));

        // Merge in the trees verified in earlier sessions: known-real, with a
        // remembered chop spot. (They skip the slime filter — they've already
        // been felled once, so they're proven legitimate.)
        for (TreeRegistry.KnownTree known : TreeRegistry.treesFor(islandKey(), config.foragingTreeType)) {
            if (eyePos.distanceTo(Vec3d.ofCenter(known.base)) > radius) continue;
            if (recentlyBroken.contains(known.base) || unbreakable.contains(known.base)) continue;
            if (!isTargetLog(client.world.getBlockState(known.base).getBlock(), config)) continue; // not regrown yet
            boolean covered = false;
            for (BlockPos u : unique) {
                int dx = known.base.getX() - u.getX();
                int dz = known.base.getZ() - u.getZ();
                if (dx * dx + dz * dz <= 9 && Math.abs(known.base.getY() - u.getY()) <= 4) {
                    covered = true;
                    break;
                }
            }
            if (!covered) unique.add(known.base);
        }
        return unique;
    }

    // ===== Route planning =====

    /** A planned base is still worth chopping: present, on the ground, not blacklisted. */
    private static boolean isValidBase(BlockPos pos, MacroConfig config) {
        if (recentlyBroken.contains(pos) || unbreakable.contains(pos)
                || TreeRegistry.isUnbreakable(islandKey(), pos)) return false;
        if (!isTargetLog(client.world.getBlockState(pos).getBlock(), config)) return false;
        if (nearSlime(pos, 3)) return false;
        BlockState below = client.world.getBlockState(pos.down());
        return !LOG_BLOCKS.containsKey(below.getBlock())
                && !below.isAir()
                && !(below.getBlock() instanceof LeavesBlock);
    }

    /**
     * Recomputes the lookahead: the next 2 trees after the current one, chained
     * nearest-neighbor (each picked closest to the previous), which naturally
     * follows the tree rings of the Park instead of zig-zagging.
     */
    private static void planAhead(ClientPlayerEntity player, MacroConfig config) {
        plannedTargets.clear();
        if (currentTarget == null) return;

        List<BlockPos> bases = collectBases(player, config, extendedRadius(config));
        Set<BlockPos> used = new HashSet<>();
        used.add(currentTarget);
        Vec3d from = Vec3d.ofCenter(currentTarget);

        for (int i = 0; i < 2; i++) {
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (BlockPos base : bases) {
                if (used.contains(base)) continue;
                // A different tree, not another trunk of the current one (the
                // sweep fells the whole bunch — planning inside it wastes moves).
                if (tooCloseToUsed(base, used)) continue;
                double d = from.squaredDistanceTo(Vec3d.ofCenter(base));
                if (d < bestDist) {
                    bestDist = d;
                    best = base;
                }
            }
            if (best == null) break;
            plannedTargets.add(best);
            used.add(best);
            from = Vec3d.ofCenter(best);
        }
    }

    /**
     * Routes to the first planned tree ahead of time — while we're still
     * walking to / chopping the current one. Runs once per target; starts from
     * where we'll be standing when the current tree is done.
     */
    private static void precomputeNextPath(MinecraftClient mc) {
        if (nextPathTried || plannedTargets.isEmpty()) return;
        nextPathTried = true;
        BlockPos from = currentPath.isEmpty()
                ? mc.player.getBlockPos()
                : currentPath.get(currentPath.size() - 1);
        nextPathFor = plannedTargets.get(0);
        BlockPos knownStand = TreeRegistry.standFor(islandKey(), nextPathFor);
        nextPath = knownStand != null
                ? TreePathfinder.findPath(mc.world, from, knownStand, 1.5)
                : TreePathfinder.findPath(mc.world, from, nextPathFor, 3.0);
    }

    private static double horizontalSq(Vec3d feet, BlockPos pos) {
        double dx = feet.x - (pos.getX() + 0.5);
        double dz = feet.z - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    /** Registry key for the current island — Park and Galatea coordinates must not mix. */
    private static String islandKey() {
        WorldChecker.Location loc = WorldChecker.getLocation();
        if (loc == null) return "unknown";
        return switch (loc) {
            case PARK -> "park";
            case GALATEA, MURKWATER, TANGLEBURG -> "galatea";
            case GARDEN -> "garden";
            default -> "other";
        };
    }

    /** Portals are framed with slime blocks — keep at least 3 blocks away. */
    private static boolean nearSlime(BlockPos pos, int range) {
        for (BlockPos p : BlockPos.iterate(pos.add(-range, -range, -range),
                pos.add(range, range, range))) {
            if (client.world.getBlockState(p).getBlock() == Blocks.SLIME_BLOCK) return true;
        }
        return false;
    }

    private static boolean tooCloseToUsed(BlockPos base, Set<BlockPos> used) {
        Vec3d center = Vec3d.ofCenter(base);
        for (BlockPos u : used) {
            if (center.squaredDistanceTo(Vec3d.ofCenter(u)) < MIN_TREE_SEPARATION_SQ) return true;
        }
        return false;
    }

    /**
     * Switches to the next planned tree, skipping entries the sweep already
     * felled. Leaves {@code currentTarget} null when the plan is exhausted —
     * the regular scan picks it up next tick.
     */
    private static void promoteNextTarget(ClientPlayerEntity player, MacroConfig config) {
        while (!plannedTargets.isEmpty()) {
            BlockPos next = plannedTargets.remove(0);
            if (!isValidBase(next, config)) continue;
            currentTarget = next;
            currentTargetOffset = randomBlockFaceOffset();
            currentAimPoint = null;
            blockedTicks = 0;
            aimResets = 0;
            stuckStrikes = 0;
            progressTicks = 0;
            chopProgressTicks = 0;
            lastProgressPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            currentRotateSpeed = gaussianFloat(config.foragingRotationSpeedMean, 0.04f, 0.06f, 0.25f);
            regenerateJitter();

            // Hand over the route computed in the background, when it matches
            // and we're standing near its start; otherwise route fresh.
            currentPath.clear();
            pathIndex = 0;
            walkLookPoint = null;
            if (nextPath != null && next.equals(nextPathFor)
                    && (nextPath.isEmpty() || player.getBlockPos().isWithinDistance(nextPath.get(0), 6))) {
                currentPath.addAll(nextPath);
                pathComputed = true;
            } else {
                pathComputed = false;
            }
            nextPath = null;
            nextPathFor = null;
            nextPathTried = false;

            planAhead(player, config);
            return;
        }
    }

    // ===== Line-of-sight targeting =====

    /**
     * True when the block the player is LOOKING at (out to throwing range,
     * beyond the crosshair's reach limit) is the target trunk — the thrown axe
     * flies along the look direction, so this is what it would hit.
     */
    private static boolean lookRayHitsTrunk(ClientPlayerEntity player, MacroConfig config) {
        double yawRad = Math.toRadians(player.getYaw());
        double pitchRad = Math.toRadians(player.getPitch());
        double xz = Math.cos(pitchRad);
        Vec3d dir = new Vec3d(-Math.sin(yawRad) * xz, -Math.sin(pitchRad), Math.cos(yawRad) * xz);
        Vec3d eye = player.getEyePos();
        BlockHitResult hit = client.world.raycast(new RaycastContext(
                eye, eye.add(dir.multiply(10.0)),
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player));
        return hit.getType() == HitResult.Type.BLOCK && isSameTrunk(hit.getBlockPos(), config);
    }

    /** The block the crosshair is actually on right now, or null (miss/entity). */
    private static BlockPos crosshairBlock(MinecraftClient mc) {
        if (mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            return bhr.getBlockPos();
        }
        return null;
    }

    /**
     * Finds a point on the target block the player can see (raycast from the eye
     * hits this block first). Samples the center plus a spread of off-center
     * points in random order, so the chosen spot varies like a human's aim would.
     * Returns null if every sampled point is obstructed.
     */
    private static Vec3d findVisibleAimPoint(ClientPlayerEntity player, BlockPos pos) {
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);

        List<Vec3d> samples = new ArrayList<>();
        double[] offsets = {-0.30, 0.0, 0.30};
        for (double dx : offsets)
            for (double dy : offsets)
                for (double dz : offsets)
                    if (dx != 0 || dy != 0 || dz != 0)
                        samples.add(center.add(dx, dy, dz));
        Collections.shuffle(samples, rand);
        samples.add(0, center.add(
                (rand.nextFloat() - 0.5f) * 0.2,
                (rand.nextFloat() - 0.5f) * 0.2,
                (rand.nextFloat() - 0.5f) * 0.2));

        for (Vec3d point : samples) {
            BlockHitResult hit = client.world.raycast(new RaycastContext(
                    eye, point,
                    RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE,
                    player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return point;
            }
        }
        return null;
    }

    /**
     * Called when no point of the current target is visible: walk up the trunk
     * looking for a visible log of the same tree; if the whole trunk is hidden
     * (e.g. behind a bush), skip this tree so we don't flail at obstructions.
     * Returns true if the target was replaced with a visible trunk block.
     */
    private static boolean resolveBlockedTarget(ClientPlayerEntity player, MacroConfig config) {
        for (int i = 1; i <= 3; i++) {
            BlockPos up = currentTarget.up(i);
            if (!isTargetLog(client.world.getBlockState(up).getBlock(), config)) break;
            // Must stay within chop reach from where we stand — never a block
            // the macro would start walking/jumping after.
            if (player.getEyePos().distanceTo(Vec3d.ofCenter(up)) > 4.0) break;
            Vec3d point = findVisibleAimPoint(player, up);
            if (point != null) {
                currentTarget = up;
                currentAimPoint = point;
                chopProgressTicks = 0;
                return true;
            }
        }
        addRecentlyBroken(currentTarget);
        currentTarget = null;
        currentAimPoint = null;
        promoteNextTarget(player, config);
        return false;
    }

    /**
     * Logs every distinct block type within the scan radius (with counts) so the
     * game log shows what Hypixel's trees are actually built from.
     */
    private static void dumpNearbyBlocks(MinecraftClient mc, MacroConfig config) {
        Vec3d eyePos = mc.player.getEyePos();
        int radius = (int) config.foragingScanRadius;
        Map<String, Integer> counts = new HashMap<>();
        BlockPos center = BlockPos.ofFloored(eyePos);
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -radius, -radius),
                center.add(radius, radius, radius))) {
            BlockState state = mc.world.getBlockState(pos);
            if (state.isAir()) continue;
            counts.merge(state.getBlock().toString(), 1, Integer::sum);
        }
        System.out.println("[Trident] Blocks within " + radius + " of player (looking for '"
                + config.foragingTreeType + "'):");
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(40)
                .forEach(e -> System.out.println("[Trident]   " + e.getKey() + " x" + e.getValue()));
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
        Vec3d targetCenter;
        if (currentAimPoint != null) {
            targetCenter = currentAimPoint;           // chopping: verified spot on the log
        } else if (walkLookPoint != null) {
            targetCenter = walkLookPoint;             // walking: a waypoint ahead on the route
        } else {
            targetCenter = Vec3d.ofCenter(currentTarget).add(currentTargetOffset);
        }
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
        // Note: the post-chop delay does NOT freeze the camera — the head keeps
        // drifting toward the already-planned next tree, like a player glancing
        // over before walking.
        if (!active || client.player == null || currentTarget == null || isLookingAround
                || microPauseTicks > 0 || !WorldChecker.isForagingZone()) {
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

        if (isChopping) {
            jitterPhaseYaw += jitterFreqYaw * dtTicks;
            jitterPhasePitch += jitterFreqPitch * dtTicks;
            currentYaw += (float) Math.sin(jitterPhaseYaw) * jitterAmpYaw * dtTicks;
            currentPitch += (float) Math.sin(jitterPhasePitch) * jitterAmpPitch * dtTicks;
        }

        player.setYaw(currentYaw);
        player.setPitch(MathHelper.clamp(currentPitch, -90f, 90f));
        FailsafeManager.updateExpectedRotation(player.getYaw(), player.getPitch());
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

    // ===== Target / path overlay =====

    /**
     * Draws the current target and the straight path to it: box around the
     * target log plus a line from the player. Green while chopping, red while
     * the aim is blocked, yellow en route.
     */
    private static void renderTargetOverlay() {
        if (!active || client.player == null || client.world == null || currentTarget == null) return;

        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getCameraPos();
        MatrixStack matrices = new MatrixStack();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer buffer = immediate.getBuffer(RenderLayers.LINES);

        float r, g, b;
        if (isChopping) {
            r = 0.3f; g = 1f; b = 0.4f;
        } else if (blockedTicks > 0 || aimResets > 0) {
            r = 1f; g = 0.35f; b = 0.3f;
        } else {
            r = 1f; g = 0.85f; b = 0.25f;
        }

        drawBoxEdges(matrices, buffer, new Box(currentTarget), r, g, b, 1f);

        // The walked route: player -> remaining waypoints -> the log itself
        Vec3d prev = new Vec3d(client.player.getX(), client.player.getY() + 0.1, client.player.getZ());
        for (int i = pathIndex; i < currentPath.size(); i++) {
            BlockPos wp = currentPath.get(i);
            Vec3d next = new Vec3d(wp.getX() + 0.5, wp.getY() + 0.1, wp.getZ() + 0.5);
            drawLine(matrices, buffer, prev, next, r, g, b, 1f);
            prev = next;
        }
        drawLine(matrices, buffer, prev,
                currentAimPoint != null ? currentAimPoint : Vec3d.ofCenter(currentTarget), r, g, b, 1f);

        // Planned trees: fading blue boxes chained current -> next -> next-next
        Vec3d chain = Vec3d.ofCenter(currentTarget);
        float alpha = 0.8f;
        for (BlockPos planned : plannedTargets) {
            Vec3d next = Vec3d.ofCenter(planned);
            drawBoxEdges(matrices, buffer, new Box(planned), 0.45f, 0.75f, 1f, alpha);
            drawLine(matrices, buffer, chain, next, 0.45f, 0.75f, 1f, alpha);
            chain = next;
            alpha = 0.5f;
        }

        // Pre-computed route to the next tree, if it's already known
        if (nextPath != null && !nextPath.isEmpty()) {
            Vec3d p = new Vec3d(nextPath.get(0).getX() + 0.5, nextPath.get(0).getY() + 0.1,
                    nextPath.get(0).getZ() + 0.5);
            for (int i = 1; i < nextPath.size(); i++) {
                BlockPos wp = nextPath.get(i);
                Vec3d next = new Vec3d(wp.getX() + 0.5, wp.getY() + 0.1, wp.getZ() + 0.5);
                drawLine(matrices, buffer, p, next, 0.45f, 0.75f, 1f, 0.4f);
                p = next;
            }
        }

        immediate.draw();
    }

    private static void drawBoxEdges(MatrixStack matrices, VertexConsumer buffer, Box box,
            float r, float g, float b, float a) {
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;

        line(matrices, buffer, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(matrices, buffer, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(matrices, buffer, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(matrices, buffer, x1, y1, z2, x1, y1, z1, r, g, b, a);

        line(matrices, buffer, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(matrices, buffer, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(matrices, buffer, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(matrices, buffer, x1, y2, z2, x1, y2, z1, r, g, b, a);

        line(matrices, buffer, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(matrices, buffer, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(matrices, buffer, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(matrices, buffer, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private static void drawLine(MatrixStack matrices, VertexConsumer buffer,
            Vec3d start, Vec3d end, float r, float g, float b, float a) {
        line(matrices, buffer, (float) start.x, (float) start.y, (float) start.z,
                (float) end.x, (float) end.y, (float) end.z, r, g, b, a);
    }

    private static void line(MatrixStack matrices, VertexConsumer buffer,
            float x1, float y1, float z1, float x2, float y2, float z2,
            float r, float g, float b, float a) {
        MatrixStack.Entry entry = matrices.peek();
        buffer.vertex(entry.getPositionMatrix(), x1, y1, z1)
                .color(r, g, b, a)
                .normal(entry, 0f, 1f, 0f)
                .lineWidth(2f);
        buffer.vertex(entry.getPositionMatrix(), x2, y2, z2)
                .color(r, g, b, a)
                .normal(entry, 0f, 1f, 0f)
                .lineWidth(2f);
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
        microPauseTicks = 0;
        isLookingAround = false;
        lookAroundCooldown = 200 + rand.nextInt(200);
        delayHistoryIndex = 0;
        collisionTicks = 0;
        jumpCooldown = 0;
        lastFrameNanos = 0;
        stuckStrikes = 0;
        progressTicks = 0;
        forceJump = false;
        aimResets = 0;
        scanCooldown = 0;
        chopProgressTicks = 0;
        axeThrowCooldown = 0;
        throwWaitTicks = 0;
        useKeyPressed = false;
        currentPath.clear();
        pathIndex = 0;
        pathComputed = false;
        walkLookPoint = null;
        nextPath = null;
        nextPathFor = null;
        nextPathTried = false;
        plannedTargets.clear();
        recentlyBroken.clear();
        unbreakable.clear();
        zoneWaitTicks = 0;
        noTreeTicks = 0;
        client.player.sendMessage(Text.literal("§3[§bTrident§3] §aForaging Macro Started! §7(v"
                + com.trident.core.Trident.MOD_VERSION + ")"), false);

        // Zone detection needs a couple of ticks — only warn on a *confirmed* wrong zone.
        if (WorldChecker.getLocation() == null) {
            client.player.sendMessage(Text.literal("§3[§bTrident§3] §7Detecting current zone..."), false);
        } else if (!WorldChecker.isForagingZone()) {
            client.player.sendMessage(Text.literal("§3[§bTrident§3] §eNot in a foraging zone (Park/Galatea). Will idle until you arrive."), false);
        }
    }

    public static void stop() {
        if (!active) return;
        active = false;
        currentTarget = null;
        currentAimPoint = null;
        walkLookPoint = null;
        currentPath.clear();
        nextPath = null;
        plannedTargets.clear();
        isChopping = false;
        chopHoldTicks = 0;
        isLookingAround = false;
        collisionTicks = 0;
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§3[§bTrident§3] §cForaging Macro Stopped!"), false);
            client.options.attackKey.setPressed(false);
            client.options.useKey.setPressed(false);
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
