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
    private static boolean timeoutDumped = false;

    // Called every tick only when macro is active
    public static void tick(MinecraftClient client) {
        if (client.world == null) {
            reset();
            return;
        }

        Location detected = detectLocation(client);
        long now = System.currentTimeMillis();

        if (detected == null) {
            // No scoreboard yet (or one we don't recognize at all)
            if (noScoreboardSince == -1) noScoreboardSince = now;
            pendingLocation = null;
            if (isScoreboardTimedOut() && !timeoutDumped) {
                timeoutDumped = true;
                System.out.println("[Trident] No recognizable scoreboard for 10s, dumping what the client sees:");
                dumpSidebar(client);
            }
            return;
        }

        noScoreboardSince = -1;
        timeoutDumped = false;

        if (detected != pendingLocation) {
            pendingLocation = detected;
            return;
        }


        if (detected != currentLocation) {
            currentLocation = detected != null ? detected : Location.SKYBLOCK_UNKNOWN;
            System.out.println("[Trident] Location detected: " + currentLocation);
            if (currentLocation == Location.SKYBLOCK_UNKNOWN) dumpSidebar(client);
        }

    }

    // Returns null if no scoreboard present
    private static Location detectLocation(MinecraftClient client) {
        if (client.world == null) return null;
        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);

        if (sidebar == null) return null;

        String title = sidebar.getDisplayName().getString().toLowerCase();
        // Since the HUD update the title may be drawn with custom font glyphs
        // instead of the literal text "SKYBLOCK", so it can't be a hard gate:
        // skyblock-only sidebar lines (Purse/Bits/...) also count as proof.
        boolean skyblock = title.contains("skyblock");

        // Render each sidebar row the way vanilla does: per-score display text
        // (modern Hypixel) wrapped in team prefix/suffix decoration (legacy Hypixel).
        for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(sidebar)) {
            if (entry.hidden()) continue;
            Team team = scoreboard.getScoreHolderTeam(entry.owner());
            String line = Team.decorateName(team, entry.name()).getString()
                    .replaceAll("§.", "")
                    .toLowerCase()
                    .trim();
            // Galatea sub-zones: Tangleburg's Path (spawn), Tangleburg, Moonglade Marsh, Murkwater Loch
            if (line.contains("murkwater")) return Location.MURKWATER;
            if (line.contains("your island")) return Location.YOUR_ISLAND;
            if (line.contains("tangleburg")) return Location.TANGLEBURG;
            if (line.contains("moonglade") || line.contains("galatea")) return Location.GALATEA;
            // Park sub-zones ("The Park" itself never appears on the sidebar)
            if (line.contains("park") || line.contains("spruce woods") || line.contains("dark thicket")
                    || line.contains("savanna woodland") || line.contains("jungle island")) return Location.PARK;
            if (line.contains("garden")) return Location.GARDEN;

            if (line.startsWith("purse:") || line.startsWith("piggy:") || line.startsWith("bits:")
                    || line.contains("motes:")) skyblock = true;
        }

        if (!skyblock) {
            if (title.contains("hypixel") || title.contains("prototype")) {
                return Location.LOBBY;
            }
            return null;
        }

        // No sub-zone matched — fall back to the island name from the tab list
        // ("Area: Galatea", "Area: The Park", ...), which covers sub-zones we
        // don't know by name.
        Location tabArea = detectTabArea(client);
        if (tabArea != null) return tabArea;

        return Location.SKYBLOCK_UNKNOWN;

    }

    // Hypixel puts an "Area: <island>" line in the tab list; unlike the sidebar
    // (which shows sub-zone names) it carries the island name directly.
    private static Location detectTabArea(MinecraftClient client) {
        var handler = client.getNetworkHandler();
        if (handler == null) return null;
        for (var entry : handler.getPlayerList()) {
            var display = entry.getDisplayName();
            if (display == null) continue;
            String line = display.getString().replaceAll("§.", "").toLowerCase().trim();
            if (!line.startsWith("area:")) continue;
            if (line.contains("galatea")) return Location.GALATEA;
            if (line.contains("park")) return Location.PARK;
            if (line.contains("garden")) return Location.GARDEN;
            if (line.contains("private island")) return Location.YOUR_ISLAND;
            return null;
        }
        return null;
    }

    // Logs what the client actually sees when we fail to recognize the zone,
    // so new/renamed Hypixel zones can be diagnosed from the game log.
    // Non-ASCII characters are escaped: custom-font glyph art (the new HUD)
    // shows up as backslash-u codes instead of rendering as readable text.
    private static void dumpSidebar(MinecraftClient client) {
        if (client.world == null) {
            System.out.println("[Trident] Dump: no world");
            return;
        }
        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (sidebar == null) {
            System.out.println("[Trident] Dump: no SIDEBAR objective. All objectives:");
            for (ScoreboardObjective obj : scoreboard.getObjectives()) {
                System.out.println("[Trident]   '" + obj.getName() + "' -> '"
                        + debugEscape(obj.getDisplayName().getString()) + "'");
            }
        } else {
            System.out.println("[Trident] Sidebar title: '"
                    + debugEscape(sidebar.getDisplayName().getString()) + "'");
            for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(sidebar)) {
                Team team = scoreboard.getScoreHolderTeam(entry.owner());
                System.out.println("[Trident] Sidebar line: '"
                        + debugEscape(Team.decorateName(team, entry.name()).getString()) + "'"
                        + (entry.hidden() ? " (hidden)" : ""));
            }
        }
        var handler = client.getNetworkHandler();
        if (handler != null) {
            for (var entry : handler.getPlayerList()) {
                var display = entry.getDisplayName();
                if (display == null) continue;
                String text = display.getString();
                if (text.toLowerCase().contains("area")) {
                    System.out.println("[Trident] Tab line: '" + debugEscape(text) + "'");
                }
            }
        }
    }

    private static String debugEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= 32 && c < 127) sb.append(c);
            else sb.append(String.format("\\u%04x", (int) c));
        }
        return sb.toString();
    }

    public static boolean isScoreboardTimedOut() {
        return noScoreboardSince != -1 &&
                (System.currentTimeMillis() - noScoreboardSince) >= SCOREBOARD_TIMEOUT_MS;
    }

    public static Location getLocation() {
        return currentLocation;
    }

    public static boolean isForagingZone() {
        // Murkwater and Tangleburg are sub-zones of Galatea.
        return currentLocation == Location.PARK || currentLocation == Location.GALATEA
                || currentLocation == Location.MURKWATER || currentLocation == Location.TANGLEBURG;
    }

    public static boolean isGardenZone() {
        return currentLocation == Location.GARDEN;
    }

    public static void reset() {
        currentLocation = null;
        pendingLocation = null;
        noScoreboardSince = -1;
        timeoutDumped = false;
    }
}