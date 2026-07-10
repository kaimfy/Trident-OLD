package com.trident.events;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.*;

public class WorldChecker {

    public enum Location {
        MURKWATER,
        YOUR_ISLAND,
        TANGLEBURG,
        PARK,
        GALATEA,
        GARDEN,
        SKYBLOCK_UNKNOWN,
        LOBBY,
        LIMBO
    }

    // How long to wait for scoreboard to appear before declaring limbo
    private static final long SCOREBOARD_TIMEOUT_MS = 10_000;

    private static Location currentLocation = null;
    private static Location pendingLocation = null;
    private static long noScoreboardSince = -1;

    // Called every tick only when macro is active
    public static void tick(MinecraftClient client) {
        if (client.world == null) {
            reset();
            return;
        }

        Location detected = detectLocation(client);
        long now = System.currentTimeMillis();

        if (detected == null) {
            // No scoreboard yet
            if (noScoreboardSince == -1) noScoreboardSince = now;
            pendingLocation = null;
            return;
        }

        noScoreboardSince = -1;

        if (detected != pendingLocation) {
            pendingLocation = detected;
            return;
        }


        if (detected != currentLocation) {
            currentLocation = detected != null ? detected : Location.SKYBLOCK_UNKNOWN;
        }

    }

    // Returns null if no scoreboard present
    private static Location detectLocation(MinecraftClient client) {
        if (client.world == null) return null;
        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);

        if (sidebar == null) return null;

        String title = sidebar.getDisplayName().getString().toLowerCase();

        if (!title.contains("skyblock")) {
            if (title.contains("hypixel") || title.contains("prototype")) {
                return Location.LOBBY;
            }
            return null;
        }


        for (ScoreHolder holder : scoreboard.getKnownScoreHolders()) {
            var team = scoreboard.getScoreHolderTeam(holder.getNameForScoreboard());
            String line = "";
            if (team != null) {
                line = (team.getPrefix().getString() + holder.getNameForScoreboard() + team.getSuffix().getString())
                        .replaceAll("§.", "")
                        .toLowerCase()
                        .trim();
            }
            if (line.contains("murkwater")) return Location.MURKWATER;
            if (line.contains("your island")) return Location.YOUR_ISLAND;
            if (line.contains("tangleburg")) return Location.TANGLEBURG;
            if (line.contains("village")) return Location.TANGLEBURG;
            if (line.contains("the park") || line.contains("⏣ park")) return Location.PARK;
            if (line.contains("galatea")) return Location.GALATEA;
            if (line.contains("garden")) return Location.GARDEN;
        }

        return Location.SKYBLOCK_UNKNOWN;

    }

    public static boolean isScoreboardTimedOut() {
        return noScoreboardSince != -1 &&
                (System.currentTimeMillis() - noScoreboardSince) >= SCOREBOARD_TIMEOUT_MS;
    }

    public static Location getLocation() {
        return currentLocation;
    }

    public static boolean isForagingZone() {
        return currentLocation == Location.PARK || currentLocation == Location.GALATEA;
    }

    public static boolean isGardenZone() {
        return currentLocation == Location.GARDEN;
    }

    public static void reset() {
        currentLocation = null;
        pendingLocation = null;
        noScoreboardSince = -1;
    }
}