package com.trident.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trident.config.MacroConfig;
import com.trident.core.Trident;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;

/**
 * Auto-updater that checks tridentclient.xyz for new mod versions.
 * Downloads the updated JAR and stages it for next restart.
 */
public class AutoUpdater {

    private static final String UPDATE_URL = "https://tridentclient.xyz/api/update";
    private static final Gson gson = new Gson();

    /**
     * Checks for updates asynchronously on a daemon thread.
     */
    public static void checkAsync() {
        MacroConfig config = MacroConfig.get();
        if (!config.autoUpdateEnabled) {
            Trident.LOGGER.info("[AutoUpdater] Auto-update is disabled in config.");
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                check();
            } catch (Exception e) {
                Trident.LOGGER.error("[AutoUpdater] Update check failed: {}", e.getMessage());
            }
        }, "Trident-AutoUpdater");
        thread.setDaemon(true);
        thread.start();
    }

    private static void check() throws Exception {
        Trident.LOGGER.info("[AutoUpdater] Checking for updates at {}", UPDATE_URL);

        HttpURLConnection con = (HttpURLConnection) URI.create(UPDATE_URL).toURL().openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", "Trident/" + Trident.MOD_VERSION);
        con.setConnectTimeout(10_000);
        con.setReadTimeout(10_000);

        int responseCode = con.getResponseCode();
        if (responseCode != 200) {
            Trident.LOGGER.warn("[AutoUpdater] Server returned HTTP {}", responseCode);
            return;
        }

        String body;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            body = sb.toString();
        } finally {
            con.disconnect();
        }

        JsonObject json = gson.fromJson(body, JsonObject.class);
        String remoteVersion = json.get("version").getAsString();
        String downloadUrl = json.get("url").getAsString();
        String changelog = json.has("changelog") ? json.get("changelog").getAsString() : "";

        if (isNewerVersion(remoteVersion, Trident.MOD_VERSION)) {
            Trident.LOGGER.info("[AutoUpdater] New version available: {} (current: {})", remoteVersion, Trident.MOD_VERSION);
            sendChatMessage("§3[§bTrident§3] §aUpdate available: §fv" + remoteVersion);
            if (!changelog.isEmpty()) {
                sendChatMessage("§3[§bTrident§3] §7Changelog: §f" + changelog);
            }

            downloadUpdate(downloadUrl, remoteVersion);
        } else {
            Trident.LOGGER.info("[AutoUpdater] Already on latest version ({})", Trident.MOD_VERSION);
        }
    }

    private static void downloadUpdate(String downloadUrl, String version) {
        try {
            Trident.LOGGER.info("[AutoUpdater] Downloading update from {}", downloadUrl);

            HttpURLConnection con = (HttpURLConnection) URI.create(downloadUrl).toURL().openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", "Trident/" + Trident.MOD_VERSION);
            con.setConnectTimeout(30_000);
            con.setReadTimeout(60_000);

            if (con.getResponseCode() != 200) {
                Trident.LOGGER.error("[AutoUpdater] Download failed: HTTP {}", con.getResponseCode());
                sendChatMessage("§3[§bTrident§3] §cUpdate download failed (HTTP " + con.getResponseCode() + ")");
                return;
            }

            // Determine mods folder
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");

            // Find and mark current jar for deletion
            Path currentJar = findCurrentJar(modsDir);

            // Download to a temp file first, then rename
            Path tempFile = modsDir.resolve("trident-" + version + ".jar.tmp");
            Path targetFile = modsDir.resolve("trident-" + version + ".jar");

            try (InputStream in = con.getInputStream();
                 OutputStream out = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            // Rename temp -> final
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);

            // Mark old jar for deletion on next launch
            if (currentJar != null && !currentJar.equals(targetFile)) {
                Path deleteMarker = modsDir.resolve(".trident_delete_old");
                Files.writeString(deleteMarker, currentJar.getFileName().toString());
            }

            Trident.LOGGER.info("[AutoUpdater] Update downloaded successfully: {}", targetFile.getFileName());
            sendChatMessage("§3[§bTrident§3] §aUpdate downloaded! §7Restart to apply v" + version);

        } catch (Exception e) {
            Trident.LOGGER.error("[AutoUpdater] Download failed", e);
            sendChatMessage("§3[§bTrident§3] §cUpdate download failed: " + e.getMessage());
        }
    }

    /**
     * On startup, clean up old jars marked for deletion.
     */
    public static void cleanupOldJar() {
        try {
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            Path marker = modsDir.resolve(".trident_delete_old");
            if (Files.exists(marker)) {
                String oldJarName = Files.readString(marker).trim();
                Path oldJar = modsDir.resolve(oldJarName);
                if (Files.exists(oldJar)) {
                    Files.delete(oldJar);
                    Trident.LOGGER.info("[AutoUpdater] Cleaned up old jar: {}", oldJarName);
                }
                Files.delete(marker);
            }
        } catch (Exception e) {
            Trident.LOGGER.warn("[AutoUpdater] Cleanup failed: {}", e.getMessage());
        }
    }

    private static Path findCurrentJar(Path modsDir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "trident-*.jar")) {
            for (Path path : stream) {
                return path; // Return first match
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Simple semver comparison: "1.0.1" > "1.0.0"
     */
    private static boolean isNewerVersion(String remote, String current) {
        try {
            String[] remoteParts = remote.split("\\.");
            String[] currentParts = current.split("\\.");
            int length = Math.max(remoteParts.length, currentParts.length);

            for (int i = 0; i < length; i++) {
                int r = i < remoteParts.length ? Integer.parseInt(remoteParts[i]) : 0;
                int c = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (r > c) return true;
                if (r < c) return false;
            }
        } catch (NumberFormatException e) {
            Trident.LOGGER.warn("[AutoUpdater] Version parse failed: remote={}, current={}", remote, current);
        }
        return false;
    }

    private static void sendChatMessage(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                PlayerEntity player = mc.player;
                if (player != null) {
                    player.sendMessage(Text.literal(message), false);
                }
            });
        }
    }
}
