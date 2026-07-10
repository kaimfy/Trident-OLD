package com.trident.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trident.config.MacroConfig;
import com.trident.events.WorldChecker;
import com.trident.modules.DwarvenMining;
import com.trident.modules.Farming;
import com.trident.modules.Foraging;
import com.trident.modules.MobAction;
import com.trident.modules.SeaLumies;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Bridge between the mod and the external watchdog script (see {@code watchdog/} in the repo).
 *
 * <p>The watchdog cannot see inside the game, and the game cannot restart itself after a crash,
 * so they talk through two small files in {@code config/trident_restart/}:
 * <ul>
 *   <li>{@code runtime.json} — written once at startup: our PID and launch command line. The
 *       watchdog uses the PID to know whether the game is alive and to detect exits/crashes.</li>
 *   <li>{@code state.json} — kept up to date every second: whether auto-restart is enabled and
 *       which macro (if any) is currently running. After the game dies, the watchdog relaunches
 *       it only if this file says {@code autoRestart && macroActive}.</li>
 * </ul>
 *
 * <p>On startup this class also reads back the previous {@code state.json}: if the last session
 * died mid-macro (crash or close) and auto-restart is on, it resumes — for the Sea Lumies macro
 * it only needs to trigger the Hypixel auto-join, because {@link SeaLumies}' join sequence
 * auto-starts the macro and its location state machine handles lobby/warp recovery. For the other
 * modules a small state machine here rejoins SkyBlock and re-enables the module.
 */
public final class RestartManager {

    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private static final File DIR = new File("config/trident_restart");
    private static final File STATE_FILE = new File(DIR, "state.json");
    private static final File RUNTIME_FILE = new File(DIR, "runtime.json");

    // ===== state.json sampling =====
    private static int sampleCooldown = 0;
    private static String lastWrittenKey = null;

    // ===== resume state machine =====
    private static String resumeModule = null;
    private static long resumeDeadlineMs = 0;
    private static long lastCommandMs = 0;
    private static int settleTicks = -1;
    private static boolean announcedResume = false;

    // Resume gives up after this long (covers launcher/auth/queue slowness).
    private static final long RESUME_TIMEOUT_MS = 10 * 60_000;
    // Throttle between /play, /warp etc. retries while resuming.
    private static final long COMMAND_RETRY_MS = 10_000;

    private RestartManager() {
    }

    public static void init() {
        DIR.mkdirs();
        writeRuntimeInfo();

        if (MacroConfig.get().autoRestartEnabled) {
            String previous = readPreviousActiveModule();
            if (previous != null) {
                resumeModule = previous;
                resumeDeadlineMs = System.currentTimeMillis() + RESUME_TIMEOUT_MS;
                if ("sealumies".equals(previous)) {
                    // The join sequence only auto-starts when armed (config toggle or here);
                    // arm it so the macro resumes even with the toggle off.
                    SeaLumies.requestAutoStart();
                } else {
                    // Keep the Sea Lumies join sequence (if the user has its auto-start
                    // toggle on) from fighting the module we are actually trying to resume.
                    SeaLumies.suppressAutoStart();
                }
                System.out.println("[Trident-Restart] Last session ended mid-macro (" + previous
                        + "), resuming after relaunch.");
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register(RestartManager::tick);

        // A graceful quit is the user closing the game on purpose. Record "no macro
        // running" so neither the watchdog nor the next boot mistakes it for a crash
        // and resumes the macro. Real crashes/kills never reach this handler, so the
        // "active" record survives exactly when resume is wanted.
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> writeShutdownState());
    }

    /** True while a post-restart resume is pending — makes AutoJoinHypixel connect for us. */
    public static boolean wantsAutoJoin() {
        return resumeModule != null;
    }

    // ===== tick =====

    private static void tick(MinecraftClient mc) {
        if (resumeModule != null) {
            tickResume(mc);
            return; // don't sample state.json mid-resume: the old "active" record must survive a crash here
        }

        if (++sampleCooldown < 20)
            return;
        sampleCooldown = 0;
        sampleState();
    }

    // ===== state.json writing =====

    private static void sampleState() {
        boolean autoRestart = MacroConfig.get().autoRestartEnabled;
        String active = activeModule();
        String key = autoRestart + "|" + active;
        if (key.equals(lastWrittenKey))
            return;

        JsonObject o = new JsonObject();
        o.addProperty("autoRestart", autoRestart);
        o.addProperty("macroActive", active != null);
        if (active != null)
            o.addProperty("module", active);
        o.addProperty("updatedMs", System.currentTimeMillis());
        if (writeAtomic(STATE_FILE, gson.toJson(o)))
            lastWrittenKey = key;
    }

    private static void writeShutdownState() {
        JsonObject o = new JsonObject();
        o.addProperty("autoRestart", MacroConfig.get().autoRestartEnabled);
        o.addProperty("macroActive", false);
        o.addProperty("updatedMs", System.currentTimeMillis());
        writeAtomic(STATE_FILE, gson.toJson(o));
    }

    private static String activeModule() {
        if (SeaLumies.macrotoggle)
            return "sealumies";
        if (DwarvenMining.isActive())
            return "dwarven";
        if (Foraging.isActive())
            return "foraging";
        if (Farming.isActive())
            return "farming";
        if (MobAction.isOn)
            return "mobaction";
        return null;
    }

    private static void writeRuntimeInfo() {
        JsonObject o = new JsonObject();
        ProcessHandle self = ProcessHandle.current();
        o.addProperty("pid", self.pid());
        o.addProperty("startedMs", System.currentTimeMillis());
        self.info().commandLine().ifPresent(cl -> o.addProperty("commandLine", cl));
        o.addProperty("workingDir", System.getProperty("user.dir"));
        writeAtomic(RUNTIME_FILE, gson.toJson(o));
    }

    private static boolean writeAtomic(File target, String content) {
        try {
            Path tmp = target.toPath().resolveSibling(target.getName() + ".tmp");
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            System.err.println("[Trident-Restart] Failed to write " + target + ": " + e.getMessage());
            return false;
        }
    }

    private static String readPreviousActiveModule() {
        if (!STATE_FILE.exists())
            return null;
        try (Reader reader = Files.newBufferedReader(STATE_FILE.toPath())) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed.isJsonObject()) {
                JsonObject o = parsed.getAsJsonObject();
                if (o.has("macroActive") && o.get("macroActive").getAsBoolean()) {
                    return o.has("module") ? o.get("module").getAsString() : "sealumies";
                }
            }
        } catch (Exception e) {
            System.err.println("[Trident-Restart] Failed to read state.json: " + e.getMessage());
        }
        return null;
    }

    // ===== resume state machine =====

    private static void tickResume(MinecraftClient mc) {
        // User turned the feature off while we were still trying — stop.
        if (!MacroConfig.get().autoRestartEnabled) {
            abortResume("auto-restart disabled");
            return;
        }
        if (System.currentTimeMillis() > resumeDeadlineMs) {
            abortResume("timed out");
            return;
        }

        // Not connected yet — AutoJoinHypixel is doing the connecting (it checks wantsAutoJoin()).
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null)
            return;

        if ("sealumies".equals(resumeModule)) {
            // Connected: the SeaLumies auto-start join sequence takes over from here
            // (loads the route, sets macrotoggle, recovers lobby/wrong-zone itself).
            sendChat(mc, "§3[§bTrident§3] §7Auto-restart: reconnected, Sea Lumies resuming.");
            resumeModule = null;
            return;
        }

        if (!announcedResume) {
            announcedResume = true;
            sendChat(mc, "§3[§bTrident§3] §7Auto-restart: reconnected, resuming " + resumeModule + "...");
        }

        WorldChecker.tick(mc);
        WorldChecker.Location loc = WorldChecker.getLocation();
        if (loc == null)
            return; // scoreboard not confirmed yet

        if (loc == WorldChecker.Location.LOBBY) {
            settleTicks = -1;
            if (throttled())
                return;
            sendCommand(mc, "play skyblock");
            WorldChecker.reset();
            return;
        }

        // In SkyBlock. /play skyblock puts us back where we logged out, so usually we are
        // already in the right zone; only foraging/farming have a detectable required zone
        // and get a warp fallback if the respawn landed elsewhere.
        boolean zoneOk = switch (resumeModule) {
            case "foraging" -> WorldChecker.isForagingZone();
            case "farming" -> WorldChecker.isGardenZone();
            default -> true; // dwarven / mobaction: no WorldChecker zone exists, trust the respawn
        };

        if (!zoneOk) {
            settleTicks = -1;
            if (throttled())
                return;
            String warp = "foraging".equals(resumeModule) ? "warp park" : "warp garden";
            sendChat(mc, "§3[§bTrident§3] §7Auto-restart: wrong zone (" + loc + "), sending /" + warp);
            sendCommand(mc, warp);
            WorldChecker.reset();
            return;
        }

        // Right place — let the world settle for a few seconds, then start the module.
        if (settleTicks < 0)
            settleTicks = 100;
        if (--settleTicks > 0)
            return;

        switch (resumeModule) {
            case "dwarven" -> DwarvenMining.start();
            case "foraging" -> Foraging.start();
            case "farming" -> Farming.start();
            case "mobaction" -> MobAction.isOn = true;
        }
        sendChat(mc, "§3[§bTrident§3] §7Auto-restart: " + resumeModule + " resumed.");
        resumeModule = null;
    }

    private static void abortResume(String reason) {
        System.out.println("[Trident-Restart] Resume aborted: " + reason);
        if (client.player != null)
            sendChat(client, "§3[§bTrident§3] §7Auto-restart: resume aborted (" + reason + ")");
        resumeModule = null;
    }

    private static boolean throttled() {
        long now = System.currentTimeMillis();
        if (now - lastCommandMs < COMMAND_RETRY_MS)
            return true;
        lastCommandMs = now;
        return false;
    }

    private static void sendCommand(MinecraftClient mc, String command) {
        var handler = mc.getNetworkHandler();
        if (handler != null) {
            try {
                handler.sendChatCommand(command);
            } catch (Exception e) {
                System.err.println("[Trident-Restart] Failed to send /" + command + ": " + e.getMessage());
            }
        }
    }

    private static void sendChat(MinecraftClient mc, String message) {
        if (mc.player != null)
            mc.player.sendMessage(Text.literal(message), false);
    }
}
