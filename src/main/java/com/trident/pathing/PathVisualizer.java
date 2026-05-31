package com.trident.pathing;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class PathVisualizer {
    private static final List<Integer> markerIds = new ArrayList<>();

    public static void spawnPathMarkers(List<BlockPos> points) {
        clearMarkers();

        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.world instanceof ClientWorld cWorld)) return;
        for (int i = 0; i < points.size(); i++) {
            BlockPos pos = points.get(i);

            ArmorStandEntity stand = new ArmorStandEntity(
                    cWorld,
                    pos.getX() + 0.5,
                    pos.getY() - 1,
                    pos.getZ() + 0.5
            );

            stand.setCustomName(Text.literal("§f#" + (i + 1)));
            stand.setCustomNameVisible(true);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);

            int entityId = -1000 - i; // unique negative ID
            stand.setId(entityId);

            cWorld.addEntity(stand);
            markerIds.add(entityId);
        }
    }


    public static void clearMarkers() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.world instanceof ClientWorld cWorld)) return;

        for (Integer id : markerIds) {
            var entity = cWorld.getEntityById(id);
            if (entity != null) {
                entity.discard();
            }
        }
        markerIds.clear();
    }
}
