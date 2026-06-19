package com.trident.events;

import com.trident.core.TridentLoader;
import com.trident.pathing.RouteManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class CameraManager {
    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static boolean active = false;
    private static float targetYaw, targetPitch;
    private static float rotateSpeed = 0.08f;
    private static final double reachDistance = 1.5;
    private static final double targetMargin = 0.4;
    private static boolean wasInWater = false;
    private static int tickstuckCounter = 0;
    private static final List<BlockPos> savePosition = new ArrayList<>();
    private static final List<BlockPos> targetPositions = new ArrayList<>();
    private static Vec3d targetOffset = Vec3d.ZERO;
    private static BlockPos currentTarget = null;
    public static boolean isMoving = false;
    private static int stuckTicks = 0;
    private static boolean raisedTarget = false;
    private static boolean firstblock = false;
    private static boolean picklecheck = false;
    public static int pickleamount = 80;
    private static int sneakdelay = 0;

    public static void toggleMacro() {
        if (active) {
            stopMacro();
        } else if (!targetPositions.isEmpty()) {
            tickstuckCounter = 0;
            BlockPos next = targetPositions.remove(0);
            if (next == null) return;
            boolean isInWater = client.player != null && client.player.isTouchingWater();
            currentTarget = isInWater ? next : next.up();
            targetOffset = randomOffset();
            wasInWater = isInWater;
            active = true;
            updateTargetAngles();
            if (wasInWater) {
                client.options.forwardKey.setPressed(true);
                client.options.sprintKey.setPressed(true);
            }
        }
    }

    public static void clearAllTargets() {
        targetPositions.clear();
        stopMacro();
    }

    public static void clearsave() {
        savePosition.clear();
    }

    public static void stopMacro() {
        active = false;
        currentTarget = null;
        picklecheck = false;
        firstblock = false;
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    public static void addTarget(BlockPos pos) {
        if (pos != null) targetPositions.add(pos);
    }

    public static void addsave(BlockPos pos) {
        if (pos != null) savePosition.add(pos);
    }

    private static Vec3d randomOffset() {
        return new Vec3d(
                (Math.random() - 0.5) * 2 * targetMargin,
                (Math.random() - 0.5) * 2 * targetMargin,
                (Math.random() - 0.5) * 2 * targetMargin
        );
    }

    private static void updateTargetAngles() {
        if (client.player == null || currentTarget == null) return;
        Vec3d playerPos = client.player.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(currentTarget).add(targetOffset);
        Vec3d targetVec = targetCenter.subtract(playerPos);

        double dx = targetVec.x;
        double dz = targetVec.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        targetYaw = (float) (MathHelper.atan2(dz, dx) * (180f / Math.PI) - 90f);
        targetPitch = (float) -(MathHelper.atan2(targetVec.y, distXZ) * (180f / Math.PI));

        targetYaw = MathHelper.wrapDegrees(targetYaw);
        targetPitch = MathHelper.wrapDegrees(targetPitch);
    }

    public static boolean isActive() {
        return active;
    }

    public static void tick() {
        if (client.player == null || !active || currentTarget == null) return;

        Screen screen = client.currentScreen;
        if (screen != null && screen.shouldPause()) {
            stopMacro();
            return;
        }

        tickstuckCounter++;
        if (tickstuckCounter >= 120) {
            sendMessage("§3[§bTrident§3] §7Stuck Detected.");

            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.sprintKey.setPressed(false);

            tickstuckCounter = 0;
            currentTarget = null;
            targetPositions.clear();
            active = false;
            picklecheck = false;
            raisedTarget = false;
            stuckTicks = 0;
            firstblock = false;

            return;
        }

        if (currentTarget == null) return;

        ClientPlayerEntity player = client.player;
        Vec3d playerEyePos = player.getEyePos();
        Vec3d targetCenter = safeVec(currentTarget).add(targetOffset);

        double dx = playerEyePos.x - targetCenter.x;
        double dz = playerEyePos.z - targetCenter.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        double dy = Math.abs(playerEyePos.y - targetCenter.y);

        boolean isInWater = player.isTouchingWater();

        if (isInWater) handleWaterMovement(playerEyePos, targetCenter);
        else handleLandMovement(distXZ, dy);

        if (distXZ < reachDistance + targetMargin && dy < 1.5) nextTarget();

        wasInWater = isInWater;
    }

    private static void handleWaterMovement(Vec3d playerEyePos, Vec3d targetCenter) {
        ClientPlayerEntity player = client.player;



        assert player != null;
        if (player.isOnGround()) {
            sneakdelay = 5;
            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
        } else {
            if (targetCenter.y > playerEyePos.y + 0.3) {
                client.options.jumpKey.setPressed(true);
                client.options.sneakKey.setPressed(false);
            } else if (targetCenter.y < playerEyePos.y - 0.3) {
                if (sneakdelay == 0) {
                    client.options.sneakKey.setPressed(true);
                    client.options.jumpKey.setPressed(false);
                }
            } else {
                client.options.jumpKey.setPressed(false);
                client.options.sneakKey.setPressed(false);
            }
        }
        if (sneakdelay > 0) {
            sneakdelay--;
        }

        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(true);

        if (!isMoving && currentTarget != null && currentTarget.getY() + 1 > player.getBlockY()) {
            if (!raisedTarget) {
                currentTarget = currentTarget.up(3);
                raisedTarget = true;
                stuckTicks = 0;
            }
        }

        if (raisedTarget) {
            stuckTicks++;
            if (stuckTicks >= 10) {
                currentTarget = currentTarget.down(3);
                raisedTarget = false;
                stuckTicks = 0;
            }
        }
    }

    private static void handleLandMovement(double distXZ, double dy) {
        ClientPlayerEntity player = client.player;
        client.options.sneakKey.setPressed(false);

        if (!player.isSprinting() && player.isOnGround() && firstblock) {
            client.options.jumpKey.setPressed(true);
        } else client.options.jumpKey.setPressed(false);

        if (wasInWater || distXZ > 0.5) {
            client.options.forwardKey.setPressed(true);
            client.options.sprintKey.setPressed(true);
        }
    }

    public static int countSeaPicklesInArea() {
        if (client.world == null) return 0;

        int count = 0;
        int minX = -680, maxX = -610;
        int minY = 54, maxY = 89;
        int minZ = -31, maxZ = 41;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = client.world.getBlockState(pos);
                    if (state.getBlock() == Blocks.SEA_PICKLE &&
                            state.contains(Properties.PICKLES) &&
                            state.get(Properties.PICKLES) >= 3) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    private static void nextTarget() {
        tickstuckCounter = 0;
        raisedTarget = false;
        stuckTicks = 0;

        if (targetPositions.isEmpty()) {
            stopMacro();
            return;
        }

        BlockPos next = targetPositions.remove(0);
        if (next == null) {
            nextTarget();
            return;
        }

        boolean isInWater = client.player != null && client.player.isTouchingWater();
        currentTarget = isInWater ? next : next.up();
        targetOffset = randomOffset();
        updateTargetAngles();
    }

    private static Vec3d safeVec(BlockPos pos) {
        if (pos == null) return Vec3d.ZERO;
        return Vec3d.ofCenter(pos);
    }

    public static void setpickles(float pickleamoun) {
        pickleamount = (int) pickleamoun;
    }

    public static void updateViewSmooth(float delta) {
        if (!active || client.player == null || currentTarget == null) return;

        ClientPlayerEntity player = client.player;
        updateTargetAngles();

        float speed = player.isTouchingWater() ? rotateSpeed * 1.5f : rotateSpeed;
        speed *= delta;

        float currentYaw = MathHelper.lerpAngleDegrees(speed, player.getYaw(), targetYaw);
        float currentPitch = MathHelper.lerpAngleDegrees(speed, player.getPitch(), targetPitch);

        player.setYaw(currentYaw);
        player.setPitch(currentPitch);
    }

    public static void setmoving(boolean moving) {
        isMoving = moving;
    }

    public static boolean finishedRoute() {
        return !active && currentTarget == null && targetPositions.isEmpty();
    }

    public static void editspeed(float speed) {
        rotateSpeed = speed;
    }

    public static void editPoint(int point) {
        point--;
        if (point >= 0) {
            sendMessage("§3[§bTrident§3] §7Edited point #" + (point + 1));
            assert MinecraftClient.getInstance().player != null;
            savePosition.set(point, MinecraftClient.getInstance().player.getBlockPos());
            TridentLoader.getInstance().editblockpoint(point);
        } else sendMessage("§3[§bTrident§3] §7Point #" + (point + 1) + " is not valid");
    }

    public static void removePoint(int point) {
        point--;
        if (point >= 0) {
            sendMessage("§3[§bTrident§3] §7Removed point #" + (point + 1));
            assert MinecraftClient.getInstance().player != null;
            savePosition.remove(point);
            TridentLoader.getInstance().removeblockpoint(point);
        } else sendMessage("§3[§bTrident§3] §7Point #" + (point + 1) + " is not valid");
    }

    private static void sendMessage(String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    public static void loadPresetRoute(String name) {
        List<BlockPos> route = RouteManager.loadRoute(name);
        if (route != null) {
            savePosition.clear();
            targetPositions.clear();
            savePosition.addAll(route);
            targetPositions.addAll(route);
            TridentLoader.getInstance().setBlockList(savePosition);
            sendMessage("§3[§bTrident§3] §7Loaded preset: " + name);
        }
    }

    public static void saveCurrentRoute(String name) {
        if (!savePosition.isEmpty()) {
            RouteManager.saveRoute(name, new ArrayList<>(savePosition));
            sendMessage("§3[§bTrident§3] §7Saved preset: " + name);
        } else {
            sendMessage("§3[§bTrident§3] §7No route to save");
        }
    }

    public static void deletePresetRoute(String name) {
        RouteManager.deleteRoute(name);
        sendMessage("§3[§bTrident§3] §7Deleted preset: " + name);
    }

    public static List<String> getPresetNames() {
        return RouteManager.getRouteNames();
    }
}