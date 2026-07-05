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
import java.security.DigestInputStream;
import java.security.MessageDigest;

/**
 * Auto-updater that checks tridentclient.xyz for new mod versions.
 *
 * Flow: the new jar is downloaded into a staging directory ({@code <gameDir>/trident-updates}),
 * verified against the sha256 from the update manifest, and swapped into {@code mods/} by a JVM
 * shutdown hook once the game closes. The swap must not happen while the game is running:
 * dropping a second trident jar into mods/ would make Fabric refuse to launch (duplicate mod id),
 * and Windows locks loaded jars so the old one can't be deleted mid-session. If the shutdown hook
 * never runs (crash/kill), mods/ is untouched and the update simply re-stages next session.
 */
@SuppressWarnings("null")
public class AutoUpdater {

    private static final String UPDATE_URL = "https://tridentclient.xyz/api/update.php";
    private static final String STAGING_DIR_NAME = "trident-updates";
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
        String sha256 = json.has("sha256") ? json.get("sha256").getAsString() : "";

        if (isNewerVersion(remoteVersion, Trident.MOD_VERSION)) {
            Trident.LOGGER.info("[AutoUpdater] New version available: {} (current: {})", remoteVersion, Trident.MOD_VERSION);
            sendChatMessage("§3[§bTrident§3] §aUpdate available: §fv" + remoteVersion);
            if (!changelog.isEmpty()) {
                sendChatMessage("§3[§bTrident§3] §7Changelog: §f" + changelog);
            }

            downloadUpdate(downloadUrl, remoteVersion, sha256);
        } else {
            Trident.LOGGER.info("[AutoUpdater] Already on latest version ({})", Trident.MOD_VERSION);
        }
    }

    private static void downloadUpdate(String downloadUrl, String version, String expectedSha256) {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            // A release jar staged into the dev run dir would be loaded alongside the dev
            // classpath on the next launch and crash Fabric with a duplicate mod id.
            Trident.LOGGER.info("[AutoUpdater] Dev environment - skipping download.");
            return;
        }

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

            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path modsDir = gameDir.resolve("mods");
            Path stagingDir = gameDir.resolve(STAGING_DIR_NAME);
            Files.createDirectories(stagingDir);

            Path currentJar = findCurrentJar(modsDir);
            Path stagedFile = stagingDir.resolve("trident-" + version + ".jar");
            Path targetFile = modsDir.resolve("trident-" + version + ".jar");

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(con.getInputStream(), digest);
                 OutputStream out = Files.newOutputStream(stagedFile)) {
                in.transferTo(out);
            }

            if (!expectedSha256.isEmpty()) {
                String actual = toHex(digest.digest());
                if (!actual.equalsIgnoreCase(expectedSha256)) {
                    Files.deleteIfExists(stagedFile);
                    Trident.LOGGER.error("[AutoUpdater] Checksum mismatch: expected {}, got {}", expectedSha256, actual);
                    sendChatMessage("§3[§bTrident§3] §cUpdate download failed (checksum mismatch)");
                    return;
                }
            } else {
                Trident.LOGGER.warn("[AutoUpdater] Update manifest has no sha256 - skipping verification.");
            }

            scheduleSwapOnExit(stagedFile, targetFile, currentJar);

            Trident.LOGGER.info("[AutoUpdater] Update staged: {}", stagedFile.getFileName());
            sendChatMessage("§3[§bTrident§3] §aUpdate downloaded! §7v" + version + " will be applied when you close the game.");

        } catch (Exception e) {
            Trident.LOGGER.error("[AutoUpdater] Download failed", e);
            sendChatMessage("§3[§bTrident§3] §cUpdate download failed: " + e.getMessage());
        }
    }

    /**
     * Swaps the staged jar into mods/ once the JVM exits. On POSIX the loaded old jar can be
     * unlinked directly; on Windows it is locked, so a detached helper process does the swap
     * after this JVM has fully terminated.
     */
    private static void scheduleSwapOnExit(Path staged, Path target, Path oldJar) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (oldJar != null && !oldJar.equals(target)) {
                    Files.deleteIfExists(oldJar);
                }
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                Trident.LOGGER.info("[AutoUpdater] Applied update: {}", target.getFileName());
            } catch (Exception e) {
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    try {
                        String script = "ping -n 4 127.0.0.1 > nul"
                                + (oldJar != null && !oldJar.equals(target)
                                        ? " & del /f /q \"" + oldJar.toAbsolutePath() + "\"" : "")
                                + " & move /y \"" + staged.toAbsolutePath() + "\" \"" + target.toAbsolutePath() + "\"";
                        new ProcessBuilder("cmd", "/c", script).start();
                    } catch (IOException ex) {
                        Trident.LOGGER.warn("[AutoUpdater] Could not start swap helper: {}", ex.getMessage());
                    }
                } else {
                    Trident.LOGGER.warn("[AutoUpdater] Update swap failed: {}", e.getMessage());
                }
            }
        }, "Trident-UpdateSwap"));
    }

    /**
     * On startup, remove leftovers from previous sessions: staged jars whose swap never ran
     * (they will be re-downloaded if still relevant) and the legacy delete-marker mechanism.
     */
    public static void cleanupOnStartup() {
        Path gameDir = FabricLoader.getInstance().getGameDir();

        // Staged jars left behind by a crash/kill before the shutdown hook could run.
        Path stagingDir = gameDir.resolve(STAGING_DIR_NAME);
        if (Files.isDirectory(stagingDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDir)) {
                for (Path leftover : stream) {
                    Files.deleteIfExists(leftover);
                    Trident.LOGGER.info("[AutoUpdater] Removed stale staged update: {}", leftover.getFileName());
                }
            } catch (Exception e) {
                Trident.LOGGER.warn("[AutoUpdater] Staging cleanup failed: {}", e.getMessage());
            }
        }

        // Legacy marker written by older versions of the updater.
        try {
            Path modsDir = gameDir.resolve("mods");
            Path marker = modsDir.resolve(".trident_delete_old");
            if (Files.exists(marker)) {
                String oldJarName = Files.readString(marker).trim();
                Files.deleteIfExists(modsDir.resolve(oldJarName));
                Files.delete(marker);
                Trident.LOGGER.info("[AutoUpdater] Cleaned up old jar: {}", oldJarName);
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

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Simple semver comparison: "1.0.1" > "1.0.0". Build/pre-release suffixes
     * ("2.0.1-beta", "2.0.1+mc1.21") are ignored for the comparison.
     */
    private static boolean isNewerVersion(String remote, String current) {
        try {
            String[] remoteParts = remote.split("[-+]", 2)[0].split("\\.");
            String[] currentParts = current.split("[-+]", 2)[0].split("\\.");
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
