package com.trident.modules;

import com.trident.core.TridentLoader;
import com.trident.events.KeyHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

import java.util.List;

@SuppressWarnings("null")
public class MobAction {

    private static final MinecraftClient client = MinecraftClient.getInstance();

    private static long lastAttackTime = 0;
    private static long lastRightClickTime = 0;
    private static long healTime = 0;
    private static LivingEntity currentTarget = null;
    public static boolean isOn = false;

    /** Registers the {@code shardkill} keybind toggle and the per-tick combat loop. */
    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (KeyHandler.shardkill != null && KeyHandler.shardkill.wasPressed()) {
                isOn = !isOn;
                TridentLoader.sendPlayerMessage(mc,
                        "§3[§bTrident§3] §7Shard Kill: " + (isOn ? "§aTrue" : "§cFalse"));
                if (!isOn) currentTarget = null;
            }
            if (isOn) {
                onTick();
            }
        });
    }

    public static void stop() {
        isOn = false;
        currentTarget = null;
    }

    public static void onTick() {
        if (client.player == null || client.world == null) return;

        // If current target is invalid, dead, or "special-dead", pick a new one
        if (currentTarget == null || !isValidTarget(currentTarget)) {
            currentTarget = findTarget();
        }

        if (currentTarget == null) return;

        // ✅ Only do actions if within 7 blocks
        if (client.player.distanceTo(currentTarget) > 7.0) {
            currentTarget = null;
            return;
        }

        long now = System.currentTimeMillis();
        if (now - healTime < 10000) {
            healTime = now;
        }

        String name = currentTarget.getName().getString();

        if (containsAny(name, "Eel", "Taurus", "Flame")&& !name.contains("Baby")) {
            equipItem("Huntaxe");
            attack(currentTarget);
        } else if (containsAny(name, "Moogma", "Worm", "Leech")) {
            equipItem("Hyperion");
            attack(currentTarget);
        } else if (name.contains("Slug")) {
            if (name.contains("Baby")) {
                equipItem("Hyperion");
                rightClickOnce();
            } else {
                equipItem("Huntaxe");
                attack(currentTarget);
            }
        } else {
            currentTarget = null;
        }
    }

    private static LivingEntity findTarget() {
        List<LivingEntity> entities = client.world.getEntitiesByClass(
                LivingEntity.class,
                client.player.getBoundingBox().expand(6.0),
                MobAction::isValidTarget
        );

        if (entities.isEmpty()) return null;

        // Sort by custom priority
        entities.sort((a, b) -> Integer.compare(getPriority(b), getPriority(a)));

        return entities.get(0); // highest priority first
    }

    private static int getPriority(LivingEntity entity) {
        String name = entity.getName().getString();

        if (name.contains("Taurus")) return 100;
        if (name.contains("Eel")) return 90;
        if (name.contains("Flame")) return 80;
        if (name.contains("Baby")) return 75;
        if (name.contains("Slug") && !name.contains("Baby")) return 70;
        if (containsAny(name, "Moogma", "Worm", "Leech")) return 50;


        return 0; // lowest priority
    }

    private static boolean isValidTarget(LivingEntity entity) {
        if (entity == null || !entity.isAlive() || entity.isRemoved() || entity == client.player) return false;

        String name = entity.getName().getString();

        // Skip if Slug/Eel/Taurus/Flame with < 1000 HP
        if (containsAny(name, "Slug", "Eel", "Taurus", "Flame") && entity.getHealth() < 2.0f) {
            return false;
        }

        // Must match one of the target keywords
        return containsAny(name, "Slug", "Eel", "Taurus", "Flame") ||
                containsAny(name, "Moogma", "Worm", "Leech") ||
                name.contains("Baby");
    }

    private static boolean containsAny(String name, String... keywords) {
        for (String key : keywords) {
            if (name.contains(key)) return true;
        }
        return false;
    }

    private static void equipItem(String keyword) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getName().getString().contains(keyword)) {
                player.getInventory().setSelectedSlot(i);
                break;
            }
        }
    }

    private static void attack(LivingEntity entity) {
        long now = System.currentTimeMillis();
        if (now - lastAttackTime < 100) return; // 5 hits per second max
        lastAttackTime = now;

        if (client.interactionManager != null && client.player != null) {
            client.interactionManager.attackEntity(client.player, entity);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private static void rightClickOnce() {
        long now = System.currentTimeMillis();
        if (now - lastRightClickTime < 1000) return; // 1 per second max
        lastRightClickTime = now;

        if (client.interactionManager != null && client.player != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
