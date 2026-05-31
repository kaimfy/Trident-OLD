package com.trident.network;

import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DiscordIntegration implements ClientModInitializer {

    private static final String API_BASE = "https://areocentric-gustily-alyse.ngrok-free.dev/mc";
    private static final HttpClient http = HttpClient.newHttpClient();
    private static ScheduledExecutorService scheduler;
    private static String username;
    private static final int PING_INTERVAL_MINUTES = 10;
    private static final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            username = client.getSession().getUsername();
            System.out.println("[DEBUG] Minecraft started, username: " + username);
            sendStart();
            startPingLoop();
            running.set(true);
            startCrashWatcher(client);
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            System.out.println("[DEBUG] Minecraft stopping");
            sendStop();
            stopPing();
            running.set(false);
        });
    }

    private void sendStart() {
        JsonObject json = new JsonObject();
        json.addProperty("username", username);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/start"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> System.out.println("[DEBUG] /start sent, response: " + resp.statusCode()))
                .exceptionally(e -> {
                    System.out.println("[DEBUG] Failed to send /start: " + e.getMessage());
                    return null;
                });
    }

    private void startPingLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) return;

            JsonObject json = new JsonObject();
            json.addProperty("username", username);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/ping"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                    .build();
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> System.out.println("[DEBUG] /ping sent, response: " + resp.statusCode()))
                    .exceptionally(e -> {
                        System.out.println("[DEBUG] Failed to send /ping: " + e.getMessage());
                        return null;
                    });
        }, 0, PING_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    private void sendStop() {
        JsonObject json = new JsonObject();
        json.addProperty("username", username);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/stop"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("[DEBUG] /stop sent, response: " + resp.statusCode());
        } catch (Exception e) {
            System.out.println("[DEBUG] Failed to send /stop: " + e.getMessage());
        }
    }

    private void stopPing() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            System.out.println("[DEBUG] Ping scheduler stopped");
        }
    }

    private void startCrashWatcher(net.minecraft.client.MinecraftClient client) {
        Thread crashWatcher = new Thread(() -> {
            try {
                while (running.get()) {
                    if (!client.isRunning()) {
                        System.out.println("[DEBUG] Minecraft crash detected");
                        sendStop();
                        running.set(false);
                        stopPing();
                        break;
                    }
                    Thread.sleep(5000); // check every 5 seconds
                }
            } catch (InterruptedException ignored) {}
        }, "Discord-Crash-Watcher");
        crashWatcher.setDaemon(true);
        crashWatcher.start();
    }
}
