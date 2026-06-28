package com.trident.network;

import com.google.gson.JsonObject;
import com.trident.config.MacroConfig;
import com.trident.modules.DwarvenMiningModule;
import com.trident.modules.MobActionModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Handles inbound commands from the Discord bot.
 * All methods run on the Minecraft client thread.
 *
 * Expected JSON format:
 * { "command": "COMMAND_TYPE", "args": "optional_args" }
 */
@SuppressWarnings("null")
public class DiscordCommandHandler {

    private static final MinecraftClient client = MinecraftClient.getInstance();

    public static void handle(JsonObject command) {
        if (command == null || !command.has("command")) return;

        String cmd = command.get("command").getAsString().toUpperCase();
        String args = command.has("args") ? command.get("args").getAsString() : "";

        System.out.println("[Trident-Discord] Received command: " + cmd + " args: " + args);

        switch (cmd) {
            case "START_MACRO" -> handleStartMacro(args);
            case "STOP_MACRO" -> handleStopMacro();
            case "WARP" -> handleWarp(args);
            case "STATUS" -> handleStatus();
            case "DISCONNECT" -> handleDisconnect();
            case "CHAT" -> handleChat(args);
            case "CONFIG" -> handleConfig(args);
            default -> sendMessage("§c[Trident] Unknown command from bot: " + cmd);
        }
    }

    private static void handleStartMacro(String macroType) {
        switch (macroType.toLowerCase()) {
            case "dwarven" -> {
                if (!DwarvenMiningModule.isActive()) {
                    DwarvenMiningModule.start();
                    sendMessage("§a[Trident] Dwarven Mining started via Discord");
                }
            }
            case "mobaction" -> {
                if (!MobActionModule.isOn) {
                    MobActionModule.isOn = true;
                    sendMessage("§a[Trident] Mob Action started via Discord");
                }
            }
            default -> sendMessage("§c[Trident] Unknown macro type: " + macroType);
        }
    }

    private static void handleStopMacro() {
        DwarvenMiningModule.stop();
        MobActionModule.stop();
        sendMessage("§c[Trident] All macros stopped via Discord");
    }

    private static void handleWarp(String location) {
        if (location.isEmpty()) {
            sendMessage("§c[Trident] Warp location not specified");
            return;
        }
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand("warp " + location);
            sendMessage("§7[Trident] Warping to " + location + " via Discord");
        }
    }

    private static void handleStatus() {
        DiscordIntegration.sendStatus();
        sendMessage("§7[Trident] Status sent to Discord");
    }

    private static void handleDisconnect() {
        sendMessage("§c[Trident] Disconnecting via Discord command...");
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().getConnection()
                    .disconnect(Text.literal("Disconnected via Discord"));
        }
    }

    private static void handleChat(String message) {
        if (message.isEmpty()) return;
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatMessage(message);
        }
    }

    private static void handleConfig(String configString) {
        // Format: "key:value"
        String[] parts = configString.split(":", 2);
        if (parts.length != 2) {
            sendMessage("§c[Trident] Invalid config format. Use key:value");
            return;
        }
        String key = parts[0].trim();
        String value = parts[1].trim();

        MacroConfig config = MacroConfig.get();
        try {
            switch (key) {
                case "failsafeEnabled" -> config.failsafeEnabled = Boolean.parseBoolean(value);
                case "failsafeAction" -> config.failsafeAction = value.toUpperCase();
                case "dwarvenPrioritizeTitanium" -> config.dwarvenPrioritizeTitanium = Boolean.parseBoolean(value);
                case "dwarvenRotationSpeedMean" -> config.dwarvenRotationSpeedMean = Float.parseFloat(value);
                case "dwarvenDelayMean" -> config.dwarvenDelayMean = Float.parseFloat(value);
                case "dwarvenLookAround" -> config.dwarvenLookAround = Boolean.parseBoolean(value);
                case "dwarvenMicroPauseChance" -> config.dwarvenMicroPauseChance = Float.parseFloat(value);
                case "discordPingIntervalSeconds" -> config.discordPingIntervalSeconds = Integer.parseInt(value);
                case "discordPollIntervalSeconds" -> config.discordPollIntervalSeconds = Integer.parseInt(value);
                default -> {
                    sendMessage("§c[Trident] Unknown config key: " + key);
                    return;
                }
            }
            MacroConfig.save();
            sendMessage("§a[Trident] Config updated: " + key + " = " + value);
        } catch (Exception e) {
            sendMessage("§c[Trident] Failed to set config: " + e.getMessage());
        }
    }

    private static void sendMessage(String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }
}
