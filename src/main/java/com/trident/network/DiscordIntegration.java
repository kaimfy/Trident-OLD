package com.trident.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trident.config.MacroConfig;
import com.trident.modules.DwarvenMining;
import com.trident.modules.MobAction;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("null")
public class DiscordIntegration implements ClientModInitializer {

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    private static final Gson gson = new Gson();
    private static ScheduledExecutorService scheduler;
    private static String username;
    private static final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            username = client.getSession().getUsername();
            MacroConfig config = MacroConfig.get();
            if (!config.discordEnabled) {
                System.out.println("[Trident-Discord] Discord integration disabled in config.");
                return;
            }
            System.out.println("[Trident-Discord] Starting with username: " + username);
            running.set(true);
            sendEvent("start", null);
            startScheduledTasks();
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            System.out.println("[Trident-Discord] Client stopping...");
            sendEvent("stop", null);
            shutdown();
        });
    }

    private void startScheduledTasks() {
        MacroConfig config = MacroConfig.get();
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "Trident-Discord");
            t.setDaemon(true);
            return t;
        });

        // Ping loop
        scheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            try {
                sendPing();
            } catch (Exception e) {
                System.out.println("[Trident-Discord] Ping failed: " + e.getMessage());
            }
        }, config.discordPingIntervalSeconds, config.discordPingIntervalSeconds, TimeUnit.SECONDS);

        // Command polling loop
        scheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            try {
                pollCommands();
            } catch (Exception e) {
                System.out.println("[Trident-Discord] Poll failed: " + e.getMessage());
            }
        }, config.discordPollIntervalSeconds, config.discordPollIntervalSeconds, TimeUnit.SECONDS);
    }

    // ===== Outbound: Mod → Bot =====

    private static String getApiBase() {
        return MacroConfig.get().discordApiBase;
    }

    /**
     * Send a generic event to the bot.
     */
    public static void sendEvent(String eventType, JsonObject extraData) {
        if (!running.get() && !"stop".equals(eventType)) return;

        JsonObject json = new JsonObject();
        json.addProperty("username", username);
        json.addProperty("event", eventType);
        json.addProperty("timestamp", System.currentTimeMillis());
        if (extraData != null) {
            json.add("data", extraData);
        }

        String endpoint = getApiBase() + "/" + eventType;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> System.out.println("[Trident-Discord] /" + eventType
                        + " → " + resp.statusCode()))
                .exceptionally(e -> {
                    System.out.println("[Trident-Discord] /" + eventType + " failed: " + e.getMessage());
                    return null;
                });
    }

    /**
     * Send a heartbeat ping with current status.
     */
    private static void sendPing() {
        MinecraftClient mc = MinecraftClient.getInstance();
        JsonObject data = new JsonObject();

        if (mc.player != null) {
            data.addProperty("x", Math.round(mc.player.getX()));
            data.addProperty("y", Math.round(mc.player.getY()));
            data.addProperty("z", Math.round(mc.player.getZ()));
            data.addProperty("health", mc.player.getHealth());
        }
        data.addProperty("dwarvenActive", DwarvenMining.isActive());
        data.addProperty("mobActionActive", MobAction.isOn);

        sendEvent("ping", data);
    }

    /**
     * Send a failsafe notification.
     */
    public static void sendFailsafe(String reason) {
        JsonObject data = new JsonObject();
        data.addProperty("reason", reason);
        data.addProperty("action", MacroConfig.get().failsafeAction);
        sendEvent("failsafe", data);
    }

    /**
     * Send a full status update.
     */
    public static void sendStatus() {
        MinecraftClient mc = MinecraftClient.getInstance();
        JsonObject data = new JsonObject();

        if (mc.player != null) {
            data.addProperty("x", mc.player.getX());
            data.addProperty("y", mc.player.getY());
            data.addProperty("z", mc.player.getZ());
            data.addProperty("health", mc.player.getHealth());
            data.addProperty("hunger", mc.player.getHungerManager().getFoodLevel());
        }
        data.addProperty("dwarvenActive", DwarvenMining.isActive());
        data.addProperty("mobActionActive", MobAction.isOn);

        sendEvent("status", data);
    }

    // ===== Inbound: Bot → Mod (polling) =====

    private static void pollCommands() {
        if (username == null) return;

        String endpoint = getApiBase() + "/commands?username=" + username;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isEmpty()) {
                        try {
                            JsonArray commands = gson.fromJson(resp.body(), JsonArray.class);
                            if (commands != null && !commands.isEmpty()) {
                                // Dispatch on the Minecraft thread
                                MinecraftClient.getInstance().execute(() -> {
                                    for (JsonElement elem : commands) {
                                        if (elem.isJsonObject()) {
                                            DiscordCommandHandler.handle(elem.getAsJsonObject());
                                        }
                                    }
                                });
                            }
                        } catch (Exception e) {
                            System.out.println("[Trident-Discord] Failed to parse commands: " + e.getMessage());
                        }
                    }
                })
                .exceptionally(e -> {
                    // Silently fail on poll — server may be down
                    return null;
                });
    }

    // ===== Lifecycle =====

    private void shutdown() {
        running.set(false);
        if (scheduler != null) {
            scheduler.shutdownNow();
            System.out.println("[Trident-Discord] Scheduler stopped.");
        }
    }
}
