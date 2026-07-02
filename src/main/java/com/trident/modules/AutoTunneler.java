package com.trident.modules;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Rapid-Fire AutoTunneler (Anti-Ghost Block Edition)
 *
 * Hits blocks instantly but tracks exactly WHEN they were hit.
 * If a block doesn't break on the server within a set ping window,
 * it aggressively retries to prevent the player from getting stuck.
 */
public class AutoTunneler {

    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static boolean enabled = false;

    private static final int TUNNEL_WIDTH = 1;
    private static final int TUNNEL_HEIGHT = 2;
    private static final double REACH = 4.5;

    // How many ticks to wait for the server to confirm the block broke.
    // 4 ticks = 200ms. If you still get stuck, lower this to 2 or 3.
    // If you mine too slowly, raise it to 5 or 6.
    private static final int RETRY_TICKS = 5;

    private static final Set<Block> MINEABLE = Set.of(
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.COAL_ORE, Blocks.IRON_ORE,
            Blocks.COPPER_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE,
            Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.GRAVEL, Blocks.DIRT,
            Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.DEEPSLATE,
            Blocks.NETHERRACK, Blocks.TUFF
    );

    // Tracks blocks and the exact tick they were last attacked
    private static final Map<BlockPos, Integer> recentlyAttacked = new HashMap<>();
    private static int tickCounter = 0;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setToggle(boolean val) {
        enabled = val;
        if (!val) {
            if (client.interactionManager != null) {
                client.interactionManager.cancelBlockBreaking();
            }
            recentlyAttacked.clear();
            tickCounter = 0;
        }
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!enabled || client.player == null || client.world == null || client.interactionManager == null) {
                return;
            }

            tickCounter++;

            // Memory cleanup: Remove blocks from the map that we haven't touched in 40 ticks
            if (tickCounter % 40 == 0) {
                recentlyAttacked.entrySet().removeIf(entry -> tickCounter - entry.getValue() > 40);
            }

            // Find the absolute closest block that is either unattacked, OR needs a retry
            BlockPos target = getClosestUnattackedBlock(client.player);

            if (target != null) {
                Direction direction = getMiningDirection(target);

                // Send the break packet (START_DESTROY)
                client.interactionManager.attackBlock(target, direction);
                client.player.swingHand(Hand.MAIN_HAND);

                // Record the exact tick we hit it
                recentlyAttacked.put(target, tickCounter);
            }
        });
    }

    private static BlockPos getClosestUnattackedBlock(ClientPlayerEntity player) {
        Vec3d eyePos = player.getCameraPosVec(1.0f);
        Direction facing = Direction.fromHorizontalDegrees(player.getYaw());
        BlockPos origin = BlockPos.ofFloored(eyePos);

        BlockPos bestPos = null;
        double bestDist = Double.MAX_VALUE;

        for (int depth = 1; depth <= (int) Math.floor(REACH); depth++) {
            for (int w = 0; w < TUNNEL_WIDTH; w++) {
                for (int h = 0; h < TUNNEL_HEIGHT; h++) {

                    BlockPos pos = offsetForFacing(origin, facing, depth, w, h);

                    // ANTI-STUCK LOGIC:
                    // If we hit this block recently, check if enough time has passed to retry.
                    if (recentlyAttacked.containsKey(pos)) {
                        int lastHitTick = recentlyAttacked.get(pos);
                        if (tickCounter - lastHitTick < RETRY_TICKS) {
                            continue; // Still waiting for the server, ignore it for now
                        }
                    }

                    double dist = eyePos.distanceTo(Vec3d.ofCenter(pos));

                    if (dist <= REACH) {
                        BlockState state = client.world.getBlockState(pos);

                        if (!state.isAir() && MINEABLE.contains(state.getBlock())) {
                            if (dist < bestDist) {
                                bestDist = dist;
                                bestPos = pos;
                            }
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    private static BlockPos offsetForFacing(BlockPos origin, Direction facing, int depth, int w, int h) {
        Direction side = facing.rotateYClockwise();
        BlockPos base = origin.offset(facing, depth);
        base = base.offset(side, w - (TUNNEL_WIDTH - 1) / 2);
        base = base.down(h);
        return base;
    }

    private static Direction getMiningDirection(BlockPos pos) {
        ClientPlayerEntity player = client.player;
        if (player == null) return Direction.NORTH;

        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();

        return Direction.getFacing((float) dx, (float) dy, (float) dz);
    }
}