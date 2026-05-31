package com.trident.events;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class KeyHandler
{
    private static final KeyBinding.Category MACRO_CATEGORY = KeyBinding.Category.create(Identifier.of("macromod"));
    public static KeyBinding savecoord,startmacro,clearall,menu,custommenu,fish,shardkill;

    public static void register(){
        savecoord = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.macromod.savecoord",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                MACRO_CATEGORY
        )   );
        startmacro = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.macromod.startmacro",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                MACRO_CATEGORY
        )   );
        clearall = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.macromod.clearall",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                MACRO_CATEGORY
        )   );
        menu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.macromod.menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                MACRO_CATEGORY
        )   );
        custommenu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.macromod.custommenu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                MACRO_CATEGORY
        )   );
        fish = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.macromod.fish",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                MACRO_CATEGORY
        )   );
        shardkill = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.macromod.shardkill",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,
                MACRO_CATEGORY
        )   );


    }
}