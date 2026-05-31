package com.trident.tracking;

import com.trident.network.BazaarPriceFetcher;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class MacroTracker {
    private static long macroStartTime = -1;
    private static int initialPickleCount = 0;
    private static int currentPickleCount = 0;

    public static void startTracking(PlayerEntity player) {
        macroStartTime = System.currentTimeMillis();
        initialPickleCount = countSeaLumies(player);
        currentPickleCount = initialPickleCount;
    }

    public static void stopTracking() {
        macroStartTime = -1;
        initialPickleCount = 0;
        currentPickleCount = 0;
    }

    public static void updatePickleCount(PlayerEntity player) {
        if (macroStartTime != -1) {
            currentPickleCount = countSeaLumies(player);
        }
    }

    public static double getMoneyPerHour() {
        if (macroStartTime == -1) return 0;

        long elapsedMillis = System.currentTimeMillis() - macroStartTime;
        if (elapsedMillis == 0) return 0;

        int picklesGained = currentPickleCount - initialPickleCount;
        double currentValue = picklesGained * BazaarPriceFetcher.getCurrentPrice();
        double hoursElapsed = elapsedMillis / (1000.0 * 60 * 60);

        return currentValue / hoursElapsed;
    }

    // Modified countSeaLumies method
    private static int countSeaLumies(PlayerEntity player) {
        int count = 0;
        PlayerInventory inventory = player.getInventory();

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.PLAYER_HEAD)) {
                String displayName = stack.getName().getString();
                if (displayName.toLowerCase().contains("enchanted sea lumies")) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }
}