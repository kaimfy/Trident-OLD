package com.trident;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.FishingRodItem;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

public class AutoFishModule {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private boolean enabled = false;
    private boolean waitingToRecast = false;
    private boolean pendingReel = false;
    private int delayTicks = 0;
    private int recastDelay = 0;

    private boolean isHoldingFishingRod() {
        if (mc.player == null) return false;
        return mc.player.getMainHandStack().getItem() instanceof FishingRodItem;
    }

    public void toggle() {
        enabled = !enabled;
        waitingToRecast = false;
        pendingReel = false;
        delayTicks = 0;
        recastDelay = 0;

        if (enabled && mc.player != null && mc.interactionManager != null) {
            if (!isHoldingFishingRod()) {
                enabled = false;
                return;
            }
            mc.player.swingHand(Hand.MAIN_HAND);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        }
    }

    public void tick() {
        if (!enabled || mc.player == null || mc.world == null) return;

        if (!isHoldingFishingRod()) {
            enabled = false;
            waitingToRecast = false;
            pendingReel = false;
            return;
        }

        ClientPlayerEntity player = mc.player;

        if (pendingReel) {
            if (delayTicks-- <= 0) {
                mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
                player.swingHand(Hand.MAIN_HAND);
                pendingReel = false;
                waitingToRecast = true;
                recastDelay = 5 + mc.world.random.nextInt(10);
            }
            return;
        }

        if (waitingToRecast) {
            if (recastDelay-- <= 0) {
                player.swingHand(Hand.MAIN_HAND);
                mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
                waitingToRecast = false;
            }
            return;
        }

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof FishingBobberEntity bobber && bobber.getOwner() == player) {
                for (Entity other : mc.world.getEntities()) {
                    if (other instanceof ArmorStandEntity armorStand && other.squaredDistanceTo(bobber) < 2.0) {
                        Text name = armorStand.getCustomName();
                        if (name != null && name.getString().toLowerCase().contains("!!!")) {
                            delayTicks = 1;
                            pendingReel = true;
                            return;
                        }
                    }
                }
            }
        }
    }


}