package com.trident;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;

public class WorldChecker {
    public static int LobbyCheck = 0;

    private static String stableTitle = "";       // last confirmed stable title
    private static String pendingTitle = "";      // title we're waiting to stabilize
    private static long pendingTitleSince = -1;   // when we first saw pendingTitle

    private static final long STABILIZE_DELAY_MS = 1500; // wait 1.5s before trusting a new title

    public static void checkWorld(MinecraftClient client) {
        if (client.world == null) {
            stableTitle = "";
            pendingTitle = "";
            pendingTitleSince = -1;
            LobbyCheck = 0;
            return;
        }

        String currentTitle = getScoreboardTitle(client);
        if (currentTitle == null) currentTitle = "";

        long now = System.currentTimeMillis();

        if (!currentTitle.equals(pendingTitle)) {
            // Title just changed — start the stabilization timer
            pendingTitle = currentTitle;
            pendingTitleSince = now;
            return; // don't act yet
        }

        // Title has been the same since pendingTitleSince — check if it's been stable long enough
        if (pendingTitleSince != -1 && (now - pendingTitleSince) >= STABILIZE_DELAY_MS) {
            if (!pendingTitle.equals(stableTitle)) {
                // Title has stabilized and is different from what we last acted on
                stableTitle = pendingTitle;
                pendingTitleSince = -1;

                String lower = stableTitle.toLowerCase();
                if (lower.contains("skyblock")) {
                    LobbyCheck = 0;
                } else if (lower.contains("hypixel")) {
                    LobbyCheck = 1;
                } else {
                    LobbyCheck = 2;
                }

                System.out.println("[LobbyDetector] Stable title confirmed: \"" + stableTitle + "\" → LobbyCheck=" + LobbyCheck);
            }
        }
    }

    private static String getScoreboardTitle(MinecraftClient client) {
        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        return sidebar != null ? sidebar.getDisplayName().getString() : null;
    }
}