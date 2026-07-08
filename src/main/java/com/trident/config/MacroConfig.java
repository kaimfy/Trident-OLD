package com.trident.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class MacroConfig {
    public boolean espEnabled = false;
    public float pathRotationSpeed = 0.08f;
    public float pathPickleSwapDetection = 70f;
    public boolean pathLoopActive = true;
    public boolean nukerToggle = true;

    // ===== Dwarven Mining Config =====
    public boolean dwarvenMiningToggle = false;
    public boolean dwarvenPrioritizeTitanium = true;
    // Dwarven Mining Humanization
    public float dwarvenRotationSpeedMean = 0.12f;
    public float dwarvenDelayMean = 8f;
    public float dwarvenDelayStddev = 4f;
    public boolean dwarvenLookAround = true;
    public float dwarvenMicroPauseChance = 0.08f;

    // ===== Foraging Config =====
    public boolean foragingToggle = false;
    // Single tree type the macro will chop.
    // One of: Oak, Spruce, Birch, Jungle, Acacia, Dark Oak, Mangrove, Cherry
    public String foragingTreeType = "Oak";
    // Foraging Humanization
    public float foragingRotationSpeedMean = 0.12f;
    public float foragingDelayMean = 6f;
    public float foragingDelayStddev = 3f;
    public boolean foragingLookAround = true;
    public float foragingMicroPauseChance = 0.08f;
    public float foragingScanRadius = 15f;

    // ===== Farming Config (Garden) =====
    public boolean farmingToggle = false;
    // Single crop the macro will break.
    // One of: Wheat, Carrot, Potato, Nether Wart, Sugar Cane, Cactus, Pumpkin, Melon, Cocoa, Mushroom
    public String farmingCropType = "Wheat";
    // Farming humanization
    public float farmingRotationSpeedMean = 0.12f;
    public float farmingDelayMean = 5f;
    public float farmingDelayStddev = 3f;
    public boolean farmingLookAround = true;
    public float farmingMicroPauseChance = 0.06f;
    // Shift one lane sideways when performing a U-turn at the end of a row
    public boolean farmingLaneShift = true;

    // ===== Failsafe Config =====
    public boolean failsafeEnabled = true;
    public String failsafeAction = "WARN"; // WARN, WARP, DISCONNECT
    // Failsafe Reactions
    public boolean failsafeReactNaturally = true;
    public boolean failsafeSendChat = false;
    public boolean failsafeTeleportDetect = true;
    public boolean failsafeInventoryDetect = true;
    public boolean failsafeRotationSnapDetect = true;
    public int failsafeReactionDurationTicks = 60;

    // ===== Discord Integration =====
    public String discordApiBase = "https://tridentclient.xyz/api/mc";
    public boolean discordEnabled = true;
    public int discordPingIntervalSeconds = 600;
    public int discordPollIntervalSeconds = 5;

    // ===== Auto-Update =====
    public boolean autoUpdateEnabled = true;

    // ===== Auto-Restart (external watchdog) =====
    // When true, the watchdog script relaunches the game if it closes/crashes mid-macro,
    // and the mod resumes the macro that was running. See watchdog/ in the repo.
    public boolean autoRestartEnabled = false;

    // ===== Keybind Overrides =====
    // Maps keybind translation key (e.g. "key.macromod.startmacro") -> GLFW key code
    public Map<String, Integer> keybindOverrides = new HashMap<>();

    // ===== Lifetime Stats =====
    public long lifetimeCoins = 0;
    public long lifetimeMacroMinutes = 0;

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final File configFile = new File("config/trident_macro_config.json");

    private static MacroConfig instance;

    public static MacroConfig get() {
        if (instance == null)
            load();
        return instance;
    }

    public static void load() {
        if (configFile.exists()) {
            try (Reader reader = new FileReader(configFile)) {
                instance = gson.fromJson(reader, MacroConfig.class);
                // Ensure map is never null after deserialization
                if (instance.keybindOverrides == null) {
                    instance.keybindOverrides = new HashMap<>();
                }
            } catch (IOException e) {
                e.printStackTrace();
                instance = new MacroConfig();
            }
        } else {
            instance = new MacroConfig();
            save();
        }
    }

    public static void save() {
        if (instance == null)
            return;
        try {
            configFile.getParentFile().mkdirs();
            try (Writer writer = new FileWriter(configFile)) {
                gson.toJson(instance, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}