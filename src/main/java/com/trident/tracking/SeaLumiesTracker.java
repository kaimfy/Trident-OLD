package com.trident.tracking;

import com.trident.core.TridentLoader;
import com.trident.network.BazaarPriceFetcher;

import net.minecraft.entity.player.PlayerEntity;

public class SeaLumiesTracker {

    private static int previousInventoryCount = 0;

    private static long sessionStartTime = -1; // ms
    private static double sessionCoins = 0;

    private static long lifetimeCoins = 0;
    private static long lifetimeMacroTime = 0; // stored internally in ms


    /* =====================================================
       Tick (Call every client tick)
       ===================================================== */

    public static void tick(PlayerEntity player) {
        if (player == null) return;

        // Only track when macro is active
        if (TridentLoader.macrotoggle) {
            if (sessionStartTime == -1) {
                sessionStartTime = System.currentTimeMillis();
                sessionCoins = 0; // reset session coins at macro start
                previousInventoryCount = TridentLoader.countSeaLumies(player);
            }
            if(TridentLoader.macroActive)trackInventory(player);

        } else {
            // Macro stopped: finalize session
            if (sessionStartTime != -1) {
                long elapsed = System.currentTimeMillis() - sessionStartTime;
                lifetimeMacroTime += elapsed;
                lifetimeCoins += (long) sessionCoins;

                sessionStartTime = -1;
                sessionCoins = 0; // reset for next macro
            }
        }
    }


    /* =====================================================
       Inventory Delta Tracking
       ===================================================== */

    private static void trackInventory(PlayerEntity player) {
        int current = TridentLoader.countSeaLumies(player);
        int gained = current - previousInventoryCount;

        if (gained > 0) {
            double price = BazaarPriceFetcher.getCurrentPrice();
            long coins = (long) (gained * price);

            sessionCoins += coins;
        }

        previousInventoryCount = current;
    }


    /* =====================================================
       Getters
       ===================================================== */

    public static double getSessionCoins() {
        return sessionCoins;
    }

    public static long getLifetimeCoins() {
        return lifetimeCoins + (long) sessionCoins; // include active session
    }

    /** Coins per hour for current macro session only */
    public static double getCoinsPerHour() {
        if (sessionStartTime == -1 || sessionCoins <= 0) return 0;

        long elapsed = System.currentTimeMillis() - sessionStartTime;
        if (elapsed <= 0) return 0;

        double hours = elapsed / 3600000.0;
        return sessionCoins / hours;
    }

    public static long getLifetimeMacroTimeMillis() {
        long currentSession = 0;

        if (sessionStartTime != -1 && TridentLoader.macroActive) {
            currentSession = System.currentTimeMillis() - sessionStartTime;
        }

        return lifetimeMacroTime + currentSession;
    }

    public static int getLifetimeMacroMinutes() {
        return (int) (getLifetimeMacroTimeMillis() / 60000);
    }


    /* =====================================================
       Config Persistence
       ===================================================== */

    public static void loadFromConfig(long savedCoins, long savedMinutes) {
        lifetimeCoins = savedCoins;
        lifetimeMacroTime = savedMinutes * 60000L;
    }

    public static long getLifetimeCoinsForSave() {
        return lifetimeCoins + (long) sessionCoins;
    }

    public static long getLifetimeMinutesForSave() {
        return getLifetimeMacroTimeMillis() / 60000;
    }
}