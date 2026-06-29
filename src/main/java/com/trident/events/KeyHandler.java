package com.trident.events;

import com.trident.config.MacroConfig;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuppressWarnings("null")
public class KeyHandler {
        private static final KeyBinding.Category MACRO_CATEGORY = KeyBinding.Category.create(Identifier.of("macromod"));
        public static KeyBinding savecoord, startmacro, clearall, menu, custommenu, fish, shardkill, dwarvenMacro, foragingMacro;

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
                        default -> translationKey;
                };
        }
}