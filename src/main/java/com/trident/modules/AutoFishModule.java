package com.trident.modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

@SuppressWarnings("null")
public class AutoFishModule {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private boolean enabled = false;
    private boolean waitingToRecast = false;
    private boolean pendingReel = false;
    private boolean pendingFlay = false;
    private int delayTicks = 0;
    private int recastDelay = 0;
    private int flayTicks = 0;
    private int originalSlot = -1;
    private boolean pendingFlayPost = false;
    private int flayPostTicks = 0;
    private boolean flaySwapped = false;

    private FishingBobberEntity cachedBobber = null;

    // Air surfacing state
    private boolean surfacing = false;

    private boolean isHoldingFishingRod() {
        if (mc.player == null) return false;
        return mc.player.getMainHandStack().getItem() instanceof FishingRodItem;
    }

    private int findFlayRodSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof FishingRodItem) {
                String name = stack.getName().getString().toLowerCase();
                if (name.contains("flaming flay")) {
                    return i;
                }
            }
        }
        return -1;
    }

    private FishingBobberEntity findBobber(ClientPlayerEntity player) {
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof FishingBobberEntity bobber && bobber.getOwner() == player) {
                return bobber;
            }
        }
        return null;
    }

    public void toggle() {
        enabled = !enabled;
        waitingToRecast = false;
        pendingReel = false;
        pendingFlay = false;
        flaySwapped = false;
        delayTicks = 0;
        recastDelay = 0;
        flayTicks = 0;
        originalSlot = -1;
        pendingFlayPost = false;
        flayPostTicks = 0;
        cachedBobber = null;
        surfacing = false;

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
        if (!enabled || mc.player == null || mc.world == null || mc.interactionManager == null) return;

        ClientPlayerEntity player = mc.player;

        // --- Air check: jump to surface if air is critically low ---
        int maxAir     = player.getMaxAir();
        int currentAir = player.getAir();

        if (!surfacing && currentAir <= 20) { // 20 ticks = 1 second
            // Hold space to swim upward
            mc.options.jumpKey.setPressed(true);
            surfacing = true;
        }

        if (surfacing) {
            // Stop pressing space once air is refilled (back to max)
            if (currentAir >= maxAir) {
                mc.options.jumpKey.setPressed(false);
                surfacing = false;

            }

        }

        if (!isHoldingFishingRod() && !flaySwapped) {
            enabled = false;
            waitingToRecast = false;
            pendingReel = false;
            pendingFlay = false;
            cachedBobber = null;
            return;
        }

        if (pendingReel) {
            if (delayTicks-- <= 0) {
                mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
                player.swingHand(Hand.MAIN_HAND);
                pendingReel = false;
                cachedBobber = null;

                int flaySlot = findFlayRodSlot();
                if (flaySlot != -1) {
                    originalSlot = player.getInventory().getSelectedSlot();
                    player.getInventory().setSelectedSlot(flaySlot);
                    flaySwapped = true;
                    pendingFlay = true;
                    flayTicks = 2;
                } else {
                    waitingToRecast = true;
                    recastDelay = 5 + mc.world.random.nextInt(10);
                }
            }
            return;
        }

        if (pendingFlay) {
            if (flayTicks-- <= 0) {
                mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
                player.swingHand(Hand.MAIN_HAND);
                pendingFlay = false;
                pendingFlayPost = true;
                flayPostTicks = 2;
            }
            return;
        }

        if (pendingFlayPost) {
            if (flayPostTicks-- <= 0) {
                if (originalSlot != -1) {
                    player.getInventory().setSelectedSlot(originalSlot);
                    originalSlot = -1;
                }
                flaySwapped = false;
                pendingFlayPost = false;
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
                cachedBobber = null;
            }
            return;
        }

        if (cachedBobber == null || cachedBobber.isRemoved() || cachedBobber.getOwner() != player) {
            cachedBobber = findBobber(player);
        }

        if (cachedBobber == null) return;

        for (Entity other : mc.world.getEntities()) {
            if (other instanceof ArmorStandEntity armorStand
                    && other.squaredDistanceTo(cachedBobber) < 2.0) {
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