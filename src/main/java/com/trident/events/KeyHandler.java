package com.trident.events;

import com.trident.config.MacroConfig;

import com.trident.core.TridentLoader;
import com.trident.gui.CustomGUIScreen;
import com.trident.gui.SeaLumiesHUD;
import com.trident.modules.AutoTunneler;
import com.trident.modules.MobActionModule;
import com.trident.pathing.PathVisualizer;
import com.trident.tracking.MacroTracker;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuppressWarnings("null")
public class KeyHandler {
        private static final KeyBinding.Category MACRO_CATEGORY = KeyBinding.Category.create(Identifier.of("macromod"));
        public static KeyBinding savecoord, startmacro, clearall, menu, custommenu, fish, shardkill, dwarvenMacro, foragingMacro, autotunneler;

        // All registered keybinds for the GUI rebinding screen
        private static final List<KeyBinding> ALL_BINDINGS = new ArrayList<>();

        public static void register() {
                savecoord = reg(new KeyBinding(
                        "key.macromod.savecoord",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_C,
                        MACRO_CATEGORY));
                startmacro = reg(new KeyBinding(
                        "key.macromod.startmacro",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_K,
                        MACRO_CATEGORY));
                clearall = reg(new KeyBinding(
                        "key.macromod.clearall",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_N,
                        MACRO_CATEGORY));
                menu = reg(new KeyBinding(
                        "key.macromod.menu",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_B,
                        MACRO_CATEGORY));
                custommenu = reg(new KeyBinding(
                        "key.macromod.custommenu",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_RIGHT_SHIFT,
                        MACRO_CATEGORY));
                fish = reg(new KeyBinding(
                        "key.macromod.fish",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_J,
                        MACRO_CATEGORY));
                shardkill = reg(new KeyBinding(
                        "key.macromod.shardkill",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_Y,
                        MACRO_CATEGORY));
                dwarvenMacro = reg(new KeyBinding(
                        "key.macromod.dwarvenmacro",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_M,
                        MACRO_CATEGORY));
                foragingMacro = reg(new KeyBinding(
                        "key.macromod.foragingmacro",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_G,
                        MACRO_CATEGORY));
                autotunneler = reg(new KeyBinding(
                        "key.macromod.autotunneler",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_H,
                        MACRO_CATEGORY));

                applyOverrides();
        }

        private static KeyBinding reg(KeyBinding binding) {
                KeyBinding registered = KeyBindingHelper.registerKeyBinding(binding);
                ALL_BINDINGS.add(registered);
                return registered;
        }

        /**
         * Returns all Trident keybindings for the GUI rebinding screen.
         */
        public static List<KeyBinding> getAllBindings() {
                return ALL_BINDINGS;
        }

        /**
         * Applies saved keybind overrides from config.
         */
        public static void applyOverrides() {
                MacroConfig config = MacroConfig.get();
                if (config.keybindOverrides == null || config.keybindOverrides.isEmpty()) return;

                for (Map.Entry<String, Integer> entry : config.keybindOverrides.entrySet()) {
                        for (KeyBinding binding : ALL_BINDINGS) {
                                if (binding.getId().equals(entry.getKey())) {
                                        binding.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(entry.getValue()));
                                        break;
                                }
                        }
                }
                KeyBinding.updateKeysByCode();
        }

        /**
         * Gets a human-readable label for a keybind translation key.
         */
        public static String getLabel(String translationKey) {
                return switch (translationKey) {
                        case "key.macromod.savecoord" -> "Save Coord";
                        case "key.macromod.startmacro" -> "Start Macro";
                        case "key.macromod.clearall" -> "Clear All";
                        case "key.macromod.menu" -> "Menu";
                        case "key.macromod.custommenu" -> "Custom Menu";
                        case "key.macromod.fish" -> "Fish";
                        case "key.macromod.shardkill" -> "Shard Kill";
                        case "key.macromod.dwarvenmacro" -> "Dwarven Macro";
                        case "key.macromod.foragingmacro" -> "Foraging Macro";
                        case "key.macromod.autotunneler" -> "Auto Tunneler";
                        default -> translationKey;
                };
        }
        public static void tick(MinecraftClient client, TridentLoader mod) {

                if (shardkill.wasPressed()) {
                        if (MobActionModule.isOn) {
                                TridentLoader.sendPlayerMessage(client, "§3[§bTrident§3] §7Shard Kill: §cFalse");
                                MobActionModule.isOn = false;
                        } else {
                                TridentLoader.sendPlayerMessage(client, "§3[§bTrident§3] §7Shard Kill: §aTrue");
                                MobActionModule.isOn = true;
                        }
                }

                while (autotunneler.wasPressed()) {
                        AutoTunneler.setToggle(!AutoTunneler.isEnabled());
                }

                if (MobActionModule.isOn) {
                        MobActionModule.onTick();
                }

                if (custommenu.wasPressed()) {
                        MinecraftClient.getInstance().setScreen(new CustomGUIScreen());
                }

                if (menu.wasPressed()) {
                        client.execute(() -> client.mouse.unlockCursor());
                }

                if (startmacro.wasPressed()) {
                        if (TridentLoader.macroActive || TridentLoader.macrotoggle) {
                                TridentLoader.macrotoggle = false;
                                SeaLumiesHUD.setActive(false);
                                MacroTracker.stopTracking();
                                mod.stopMacro(client);
                                WorldChecker.reset();
                                TridentLoader.sendPlayerMessage(client, "§3[§bTrident§3] §7Macro stopped");
                        } else if (!mod.blockList.isEmpty()) {
                                SeaLumiesHUD.setActive(true);
                                MacroTracker.startTracking(client.player);
                                TridentLoader.macrotoggle = true;
                                mod.relistCooldownTicks = TridentLoader.RELIST_INTERVAL_TICKS;
                                WorldChecker.reset();
                                TridentLoader.sendPlayerMessage(client,
                                        "§3[§bTrident§3] §7Macro started with " + mod.blockList.size() + " points");
                        } else {
                                TridentLoader.sendPlayerMessage(client, "§3[§bTrident§3] §7No positions saved");
                        }
                }

                if (savecoord.wasPressed()) {
                        BlockPos pos = client.player.getBlockPos();
                        mod.blockList.add(pos);
                        CameraManager.addsave(pos);

                        TridentLoader.sendPlayerMessage(client,
                                "§3[§bTrident§3] §7Saved position #" + mod.blockList.size() + " at: " + pos.toShortString());

                        PathVisualizer.spawnPathMarkers(mod.blockList);
                }

                if (fish.wasPressed()) {
                        mod.autoFish.toggle();
                }

                if (clearall.wasPressed()) {
                        mod.stopMacro(client);
                        TridentLoader.macrotoggle = false;
                        mod.blockList.clear();
                        CameraManager.clearsave();
                        WorldChecker.reset();
                        PathVisualizer.spawnPathMarkers(mod.blockList);

                        TridentLoader.sendPlayerMessage(client, "§3[§bTrident§3] §7Cleared all positions");
                }
        }

}