package com.trident.modules;

import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Nuker {
    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static boolean enabled = false;
    private static int tickCounter = 0;


    public static void setToggle(boolean val) {
        enabled = val;
        assert client.player != null;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!enabled || client.player == null || client.world == null) return;

            tickCounter++;
            if (tickCounter < 1) return; // just a safety, breaks 1 block per tick
            tickCounter = 0;

            BlockPos target = findNearestPickle(client.player);
            if (target != null) {
                breakBlock(target);
            }
        });
    }

    private static BlockPos findNearestPickle(ClientPlayerEntity player) {
        assert client.player != null;
        Vec3d playerPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());

        double radius = 4.5;

        int minX = (int) Math.floor(playerPos.x - radius);
        int maxX = (int) Math.floor(playerPos.x + radius);
        int minY = (int) Math.floor(playerPos.y - radius);
        int maxY = (int) Math.floor(playerPos.y + radius);
        int minZ = (int) Math.floor(playerPos.z - radius);
        int maxZ = (int) Math.floor(playerPos.z + radius);

        List<BlockPos> candidates = new ArrayList<>();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = client.world.getBlockState(pos);

                    if (state.isOf(Blocks.SEA_PICKLE) && playerPos.distanceTo(Vec3d.ofCenter(pos)) <= radius) {
                        candidates.add(pos);
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingDouble(pos -> playerPos.distanceTo(Vec3d.ofCenter(pos))));
        return candidates.get(0);
    }

    private static void breakBlock(BlockPos pos) {
        if (client.getNetworkHandler() == null || client.player == null) return;

        client.getNetworkHandler().sendPacket(
                new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP)
        );

        client.player.swingHand(Hand.MAIN_HAND);

        client.getNetworkHandler().sendPacket(
                new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP)
        );

        removeBlockClientSide(pos);

        if (client.world != null) {
            client.player.playSound(
                    SoundEvents.BLOCK_SLIME_BLOCK_BREAK,
                    1.0f,
                    1.0f
            );
        }
    }

    private static void removeBlockClientSide(BlockPos pos) {
        if (client.world instanceof ClientWorld clientWorld) {
            clientWorld.setBlockState(pos, Blocks.WATER.getDefaultState(), 3);
        }
    }


}
