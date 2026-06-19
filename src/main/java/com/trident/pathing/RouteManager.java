package com.trident.pathing;

import com.trident.utils.BlockPosSerializer;

import com.google.common.reflect.TypeToken;
import com.google.gson.*;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.MinecraftClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RouteManager {
    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(BlockPos.class, new BlockPosSerializer())
            .setPrettyPrinting()
            .create();

    private static final Path SAVE_DIR = Paths.get(
            MinecraftClient.getInstance().runDirectory.getAbsolutePath(),
            "config",
            "seafucker_routes"
    );

    private static final Map<String, List<BlockPos>> savedRoutes = new HashMap<>();

    static {
        verifyFileOperations();
        loadAllRoutes();
    }


    public static void saveRoute(String name, List<BlockPos> route) {
        System.out.println("§3[§bTrident§3] §7Saving route: " + name);
        savedRoutes.put(name, new ArrayList<>(route));
        saveToFile();
        sendMessage("§3[§bTrident§3] §7Route '" + name + "' saved with " + route.size() + " Points");
    }

    public static List<BlockPos> loadRoute(String name) {
        List<BlockPos> route = savedRoutes.get(name);
        if (route != null) {
            sendMessage("§3[§bTrident§3] §7Loaded route '" + name + "' with " + route.size() + " Points");
            return new ArrayList<>(route);
        }
        sendMessage("§3[§bTrident§3] §7Route '" + name + "' not found");
        return null;
    }

    public static void deleteRoute(String name) {
        if (savedRoutes.remove(name) != null) {
            saveToFile();
            sendMessage("§3[§bTrident§3] §7Deleted route '" + name + "'");
        } else {
            sendMessage("§3[§bTrident§3] §7Route '" + name + "' not found");
        }
    }

    public static List<String> getRouteNames() {
        return new ArrayList<>(savedRoutes.keySet());
    }

    private static void saveToFile() {
        try {
            if (!Files.exists(SAVE_DIR)) {
                Files.createDirectories(SAVE_DIR);
            }

            Path saveFile = SAVE_DIR.resolve("routes.json");
            String json = gson.toJson(savedRoutes);

            // Write to temp file first
            Path tempFile = SAVE_DIR.resolve("routes.json.tmp");
            Files.write(tempFile, json.getBytes(StandardCharsets.UTF_8));

            // Atomic move operation
            Files.move(tempFile, saveFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            System.out.println("§3[§bTrident§3] §7Routes saved successfully");
        } catch (IOException e) {
            System.err.println("§3[§bTrident§3] §7Failed to save routes: " + e.getMessage());
            e.printStackTrace();
            sendMessage("§3[§bTrident§3] §7Failed to save routes: " + e.getMessage());
        }
    }

    private static void loadAllRoutes() {
        try {
            if (!Files.exists(SAVE_DIR)) {
                Files.createDirectories(SAVE_DIR);
                return;
            }

            Path saveFile = SAVE_DIR.resolve("routes.json");
            if (Files.exists(saveFile) && Files.size(saveFile) > 0) {
                String json = Files.readString(saveFile, StandardCharsets.UTF_8);
                Map<String, List<BlockPos>> loaded = gson.fromJson(json,
                        new TypeToken<Map<String, List<BlockPos>>>(){}.getType());

                if (loaded != null) {
                    savedRoutes.clear();
                    savedRoutes.putAll(loaded);
                    System.out.println("§3[§bTrident§3] §7Loaded " + loaded.size() + " routes");
                }
            }
        } catch (Exception e) {
            System.err.println("§3[§bTrident§3] §7Failed to load routes: " + e.getMessage());
            e.printStackTrace();
            sendMessage("§3[§bTrident§3] §7Failed to load routes: " + e.getMessage());
        }
    }

    public static void verifyFileOperations() {
        try {
            System.out.println("[Seafucker] Config directory: " + SAVE_DIR.toAbsolutePath());
            System.out.println("[Seafucker] Directory exists: " + Files.exists(SAVE_DIR));
            if (Files.exists(SAVE_DIR)) {
                System.out.println("[Seafucker] Directory writable: " + Files.isWritable(SAVE_DIR));
            }
        } catch (Exception e) {
            System.err.println("[Seafucker] File operation verification failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("null")
    private static void sendMessage(String message) {
        if (MinecraftClient.getInstance().player != null) {
            MinecraftClient.getInstance().player.sendMessage(Text.literal(message), false);
        }
    }
}