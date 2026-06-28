package com.trident.modules;

import com.trident.config.MacroConfig;
import com.trident.events.KeyHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class DwarvenMiningModule {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private static boolean active = false;
    private static BlockPos currentTarget = null;
    private static Vec3d currentTargetOffset = Vec3d.ZERO;
    private static float targetYaw, targetPitch;
    private static float rotateSpeed = 0.15f;
    private static int delayTicks = 0;
    private static boolean isMining = false;

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
                    currentTarget = null;
                    isMining = false;
                    delayTicks = 2 + (int) (Math.random() * 5); // wait a random bit before next block
                    return;
                }
            }

            // Find new target if we don't have one
            if (currentTarget == null) {
                currentTarget = findNextTarget(mc.player);
                if (currentTarget == null) {
                    // No blocks found, just wait
                    mc.options.attackKey.setPressed(false);
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    return;
                }
                currentTargetOffset = new Vec3d((Math.random() - 0.5) * 0.6, (Math.random() - 0.5) * 0.6,
                        (Math.random() - 0.5) * 0.6);
            }

            updateTargetAngles();

            // Look at target smoothly
            updateViewSmooth(1.0f);

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

                float yawDiff = MathHelper.wrapDegrees(mc.player.getYaw() - targetYaw);
                float pitchDiff = MathHelper.wrapDegrees(mc.player.getPitch() - targetPitch);

                if (Math.abs(yawDiff) < 5.0f && Math.abs(pitchDiff) < 5.0f) {
                    mc.options.attackKey.setPressed(true);
                    isMining = true;
                }
            }
        });
    }

    private static BlockPos findNextTarget(ClientPlayerEntity player) {
        MacroConfig config = MacroConfig.get();
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
                        candidates.add(pos);
                    }
                }
            }
        }

        if (candidates.isEmpty())
            return null;

        // Sort candidates
        candidates.sort((p1, p2) -> {
            boolean isTitanium1 = TITANIUM_BLOCKS.contains(client.world.getBlockState(p1).getBlock());
            boolean isTitanium2 = TITANIUM_BLOCKS.contains(client.world.getBlockState(p2).getBlock());

            if (config.dwarvenPrioritizeTitanium && isTitanium1 != isTitanium2) {
                return isTitanium1 ? -1 : 1;
            }

            // Otherwise sort by distance
            double d1 = eyePos.distanceTo(Vec3d.ofCenter(p1));
            double d2 = eyePos.distanceTo(Vec3d.ofCenter(p2));
            return Double.compare(d1, d2);
        });

        return candidates.get(0);
    }

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

    private static void updateViewSmooth(float delta) {
        if (!active || client.player == null || currentTarget == null)
            return;
        ClientPlayerEntity player = client.player;

        float speed = rotateSpeed * (0.8f + (float) Math.random() * 0.4f);

        float currentYaw = MathHelper.lerpAngleDegrees(speed * delta, player.getYaw(), targetYaw);
        float currentPitch = MathHelper.lerpAngleDegrees(speed * delta, player.getPitch(), targetPitch);

        if (isMining) {
            currentYaw += (float) ((Math.random() - 0.5) * 0.3);
            currentPitch += (float) ((Math.random() - 0.5) * 0.3);
        }

        player.setYaw(currentYaw);
        player.setPitch(currentPitch);
        FailsafeManager.updateExpectedRotation(currentYaw, currentPitch);
    }

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
        isMining = false;
        client.player.sendMessage(Text.literal("§3[§bTrident§3] §aDwarven Mining Macro Started!"), false);
    }

    public static void stop() {
        if (!active)
            return;
        active = false;
        currentTarget = null;
        isMining = false;
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§3[§bTrident§3] §cDwarven Mining Macro Stopped!"), false);
            client.options.attackKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
        }
    }

    public static boolean isActive() {
        return active;
    }
}
