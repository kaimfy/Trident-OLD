package com.trident.core;

import com.trident.config.MacroConfig;
import com.trident.events.AutoJoinHypixel;
import com.trident.events.CameraManager;
import com.trident.events.KeyHandler;
import com.trident.modules.AutoFish;
import com.trident.modules.AutoTunneler;
import com.trident.modules.DwarvenMining;
import com.trident.modules.FailsafeManager;
import com.trident.modules.Farming;
import com.trident.modules.Foraging;
import com.trident.modules.MobAction;
import com.trident.modules.Nuker;
import com.trident.modules.SeaLumies;
import com.trident.network.BazaarPriceFetcher;
import com.trident.pathing.RouteCommands;
import com.trident.tracking.SeaLumiesTracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

/**
 * Client entrypoint / bootstrap hub. Wires up config, registers every automation module, and keeps
 * a couple of tiny cross-cutting concerns (the animated-menu frame counter, a shared chat helper).
 * All feature logic lives in the {@code modules} package — see {@link SeaLumies} for the
 * flagship Sea Lumies macro that used to be inlined here.
 */
public class TridentLoader implements ClientModInitializer {

    private static int framecount = 0;

    // ESP + path-loop flags surfaced to the settings GUI.
    private boolean espEnabled = false;
    public boolean shouldLoop = true;

    private static TridentLoader instance;

    public TridentLoader() {
        instance = this;
    }

    public static TridentLoader getInstance() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        if (!Trident.access) {
            // Account not verified — still let the Sea Lumies module post the auth-failure notice,
            // but register nothing else.
            SeaLumies.init();
            return;
        }

        // Advance the animated-menu frame counter every tick.
        ClientTickEvents.END_CLIENT_TICK.register(client -> updateFrame());

        // ── Modules ──────────────────────────────────────────────────────
        SeaLumies.init();
        AutoJoinHypixel.init();
        AutoFish.init();
        MobAction.init();
        AutoTunneler.init();
        Nuker.init();
        DwarvenMining.init();
        Foraging.init();
        Farming.init();
        FailsafeManager.init();

        // ── Network / config / commands / keybinds ───────────────────────
        BazaarPriceFetcher.fetchPriceAsync();
        applyConfig();
        RouteCommands.register();
        KeyHandler.register();

        MacroConfig config = MacroConfig.get();
        SeaLumiesTracker.loadFromConfig(config.lifetimeCoins, config.lifetimeMacroMinutes);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            MacroConfig cfg = MacroConfig.get();
            cfg.lifetimeCoins = SeaLumiesTracker.getLifetimeCoinsForSave();
            cfg.lifetimeMacroMinutes = SeaLumiesTracker.getLifetimeMinutesForSave();
            MacroConfig.save();
        }));
    }

    /** Pushes persisted settings into the modules that need them at startup. */
    public void applyConfig() {
        MacroConfig.load();
        MacroConfig config = MacroConfig.get();
        espEnabled = config.espEnabled;
        Nuker.setToggle(config.nukerToggle);
        CameraManager.editspeed(config.pathRotationSpeed);
        CameraManager.setpickles(config.pathPickleSwapDetection);
        this.shouldLoop = config.pathLoopActive;
    }

    public void ESPToggle(boolean val) {
        espEnabled = val;
    }

    public boolean isEspEnabled() {
        return espEnabled;
    }

    // ===== Shared helpers =====

    public static void sendPlayerMessage(MinecraftClient client, String message) {
        PlayerEntity player = client.player;
        if (player != null) {
            player.sendMessage(Text.literal(message), false);
        }
    }

    // ===== Animated-menu frame counter =====

    private static void updateFrame() {
        if (framecount < 59) {
            framecount++;
        } else {
            framecount = 0;
        }
    }

    public static String getFrame() {
        return "" + framecount;
    }
}
