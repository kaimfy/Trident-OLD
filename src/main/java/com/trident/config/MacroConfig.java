package com.trident.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;

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