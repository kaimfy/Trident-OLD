package com.trident.core;

import com.bazaarmod.BazaarModClient;
import com.trident.config.MacroConfig;
import com.trident.events.AutoJoinHypixel;
import com.trident.events.CameraManager;
import com.trident.events.KeyHandler;
import com.trident.events.WorldChecker;
import com.trident.gui.CustomGUIScreen;
import com.trident.gui.SeaLumiesHUD;
import com.trident.modules.AutoFishModule;
import com.trident.modules.MobActionModule;
import com.trident.modules.Nuker;
import com.trident.network.BazaarPriceFetcher;
import com.trident.pathing.PathVisualizer;
import com.trident.pathing.RouteCommands;
import com.trident.tracking.MacroTracker;
import com.trident.tracking.SeaLumiesTracker;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.util.math.MatrixStack;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.screen.slot.SlotActionType;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.client.MinecraftClient;

import net.minecraft.client.render.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import net.minecraft.text.Text;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class TridentLoader implements ClientModInitializer {

    public static final Set<String> TARGET_PLAYERS = new HashSet<>(Arrays.asList(
            "yuvet",
            "FunDaOne"
    ));

    private static boolean joinedServer = false;
    private long islandArrivalTime = 0;
    private final AutoFishModule autoFish = new AutoFishModule();
    private static int framecount = 0;
    private final KeyHandler keyHandler = new KeyHandler();
    public List<BlockPos> blockList = new ArrayList<>();
    private int currentTargetIndex = 0;

    public static boolean macroActive = false;
    public static boolean macrotoggle = false;
    public static boolean commandsSent = false;

    private static boolean ESPToggle = false;
    private static boolean DoneDropping = true;
    private boolean droppingInProgress = false;
    private int dropIndex = 0;

    private static String theme = Trident.getThemecolor();
    private float r, g, b;

    // Bazaar relist
    private static boolean bazaarLoaded = false;
    private static boolean onIsland = false;
    private int relistCooldownTicks = 0;
    private boolean relistInProgress = false;
    private static final int RELIST_INTERVAL_TICKS = 20 * 20 ;

    // Warp retry
    private static final long WARP_RETRY_MS = 4_000;
    private long lastWarpAttempt = 0;

    private int tickCounter = 0;
    Vec3d lastPlayerPos = new Vec3d(0, 0, 0);
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
        bazaarLoaded = FabricLoader.getInstance().isModLoaded("bazaarmod");

        if (!Trident.access) {
            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
                if (!joinedServer) {
                    joinedServer = true;
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Couldnt Verify Your Account, Please Check Your Authentication.");
                }
            });
            return;
        }

        switch (theme) {
            case "purple": r = 0.7f; g = 0.2f; b = 1f;   break;
            case "red":    r = 1f;   g = 0.2f; b = 0.2f; break;
            case "green":  r = 0.2f; g = 1f;   b = 0.2f; break;
            case "blue":   r = 0f;   g = 0.5f; b = 1f;   break;
        }

        AutoJoinHypixel.init();
        Nuker.init();
        BazaarPriceFetcher.fetchPriceAsync();
        ApplicaConfigurazioni();
        RouteCommands.register();
        keyHandler.register();

        MacroConfig config = MacroConfig.get();
        SeaLumiesTracker.loadFromConfig(
                config.lifetimeCoins,
                config.lifetimeMacroMinutes
        );
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            MacroConfig meow = MacroConfig.get();
            meow.lifetimeCoins = SeaLumiesTracker.getLifetimeCoinsForSave();
            meow.lifetimeMacroMinutes = SeaLumiesTracker.getLifetimeMinutesForSave();
            MacroConfig.save();
        }));

        HudRenderCallback.EVENT.register(SeaLumiesHUD::render);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!joinedServer) {
                joinedServer = true;
                sendPlayerMessage(client, "§3[§bTrident§3] §7Account Verified. Have fun!");
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            UpdateFrame();
            SeaLumiesTracker.tick(client.player);

            // ── Key handlers ────────────────────────────────────────────────
            if (KeyHandler.shardkill.wasPressed()) {
                if (MobActionModule.isOn) {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Shard Kill: §cFalse");
                    MobActionModule.isOn = false;
                } else {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Shard Kill: §aTrue");
                    MobActionModule.isOn = true;
                }
            }
            if (MobActionModule.isOn) MobActionModule.onTick();

            autoFish.tick();

            if (keyHandler.custommenu.wasPressed()) {
                MinecraftClient.getInstance().setScreen(new CustomGUIScreen());
            }
            if (keyHandler.menu.wasPressed()) {
                client.execute(() -> client.mouse.unlockCursor());
            }

            if (KeyHandler.startmacro.wasPressed()) {
                if (macroActive || macrotoggle) {
                    macrotoggle = false;
                    SeaLumiesHUD.setActive(false);
                    MacroTracker.stopTracking();
                    stopMacro(client);
                    WorldChecker.reset();
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Macro stopped");
                } else if (!blockList.isEmpty()) {
                    SeaLumiesHUD.setActive(true);
                    MacroTracker.startTracking(client.player);
                    macrotoggle = true;
                    relistCooldownTicks = RELIST_INTERVAL_TICKS;
                    WorldChecker.reset();
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Macro started with " + blockList.size() + " points");
                    // Don't call startMacro here — let the location state machine do it
                    // once it confirms we're in MURKWATER
                } else {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7No positions saved");
                }
            }

            if (KeyHandler.savecoord.wasPressed()) {
                BlockPos pos = client.player.getBlockPos();
                blockList.add(pos);
                CameraManager.addsave(pos);
                sendPlayerMessage(client, "§3[§bTrident§3] §7Saved position #" + blockList.size() + " at: " + pos.toShortString());
                PathVisualizer.spawnPathMarkers(blockList);
            }

            if (keyHandler.fish.wasPressed()) {
                autoFish.toggle();
            }

            if (KeyHandler.clearall.wasPressed()) {
                stopMacro(client);
                macrotoggle = false;
                blockList.clear();
                CameraManager.clearsave();
                WorldChecker.reset();
                PathVisualizer.spawnPathMarkers(blockList);
                sendPlayerMessage(client, "§3[§bTrident§3] §7Cleared all positions");
            }

            if (client.currentScreen != null && client.currentScreen.shouldPause()) {
                stopMacro(client);
            }

            if (client.world == null || client.player == null || client.getNetworkHandler() == null) return;

            tickCounter++;

            // Track movement for stuck detection
            if (tickCounter % 3 == 0) {
                Vec3d currentPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
                if (lastPlayerPos != null && currentPos.squaredDistanceTo(lastPlayerPos) < 0.05) {
                    CameraManager.setmoving(false);
                } else {
                    CameraManager.setmoving(true);
                    lastPlayerPos = currentPos;
                }
            }

            // ── Only run location logic when macro is toggled on ─────────────
            if (!macrotoggle) {
                CameraManager.tick();
                return;
            }

            // Tick world checker
            WorldChecker.tick(client);

            // ── Limbo detection (no scoreboard for 10s) ──────────────────────
            if (WorldChecker.isScoreboardTimedOut()) {
                if (macroActive) stopMacro(client);
                long now = System.currentTimeMillis();
                if (now - lastWarpAttempt > WARP_RETRY_MS) {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Limbo detected, sending /lobby");
                    sendServerCommand(client, "lobby");
                    lastWarpAttempt = now;
                }
                CameraManager.tick();
                return;
            }

            // ── Relist countdown (runs while macro active or on island) ──────
            if (bazaarLoaded && (macroActive || commandsSent)) {
                if (relistCooldownTicks > 0) {
                    if (relistCooldownTicks % 200 == 0)
                        sendPlayerMessage(client, "§3[§bTrident§3] §7Relist in: " + relistCooldownTicks / 20 + "s");
                    relistCooldownTicks--;
                }
            }

            // ── Location state machine ────────────────────────────────────────
            WorldChecker.Location loc = WorldChecker.getLocation();
            long now = System.currentTimeMillis();

            if (loc == null) {
                // Scoreboard not loaded yet, wait
            }
            else if (loc == WorldChecker.Location.LOBBY) {
                if (macroActive) stopMacro(client);
                if (now - lastWarpAttempt > WARP_RETRY_MS) {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Lobby detected, sending /play skyblock");
                    sendServerCommand(client, "play skyblock");
                    lastWarpAttempt = now;
                }
            }
            else if (loc == WorldChecker.Location.TANGLEBURG || loc == WorldChecker.Location.SKYBLOCK_UNKNOWN) {
                if (macroActive) stopMacro(client);
                if (now - lastWarpAttempt > WARP_RETRY_MS) {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Wrong zone (" + loc + "), retrying /warp murk");
                    sendServerCommand(client, "warp murk");
                    lastWarpAttempt = now;
                }
            }
            else if (loc == WorldChecker.Location.MURKWATER) {
                // Correct zone — start macro if not already running
                if (!macroActive && !commandsSent) {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7In Murkwater, starting macro");
                    startMacro(client);
                }

                // Check if path finished
                if (macroActive && CameraManager.finishedRoute()) {
                    stopMacro(client);
                    commandsSent = true;
                    onIsland = false;
                    sendServerCommand(client, "is");
                    WorldChecker.reset(); // force re-confirmation after warp
                    lastWarpAttempt = now;
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Path complete, warping to island");
                }

                if (!macroActive && commandsSent && !onIsland && now - lastWarpAttempt > WARP_RETRY_MS) {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Island warp failed, retrying...");
                    sendServerCommand(client, "is");
                    lastWarpAttempt = now;
                }
            }
            else if (loc == WorldChecker.Location.YOUR_ISLAND) {
                if (macroActive) stopMacro(client);
                commandsSent = true;

                // First time arriving on island this cycle
                if (!onIsland) {
                    onIsland = true;
                    islandArrivalTime = System.currentTimeMillis();
                    sendPlayerMessage(client, "§3[§bTrident§3] §7On island.");
                }

                if (onIsland && bazaarLoaded && relistCooldownTicks == 0 && !relistInProgress
                        && System.currentTimeMillis() - islandArrivalTime >= 5_000) {
                    relistInProgress = true;
                    BazaarModClient.MANUAL_RELIST.trigger(client);
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Starting bazaar relist...");
                }

                // Wait for relist to finish
                if (bazaarLoaded && relistInProgress) {
                    if (!BazaarModClient.MANUAL_RELIST.isRunning()) {
                        relistInProgress = false;
                        relistCooldownTicks = RELIST_INTERVAL_TICKS;
                        sendPlayerMessage(client, "§3[§bTrident§3] §7Relist finished.");
                    } else {
                        CameraManager.tick();
                        return; // still relisting
                    }
                }

                // Drop lumies if needed (only without bazaarmod)
                if (!bazaarLoaded && countSeaLumies(client.player) >= 500) {
                    if (DoneDropping) DoneDropping = false;
                    if (!DoneDropping) {
                        dropSeaLumies(client);
                        CameraManager.tick();
                        return;
                    }
                }

                // Ready to leave — warp to murk
                if (DoneDropping && !relistInProgress) {
                    if (now - lastWarpAttempt > WARP_RETRY_MS) {
                        onIsland = false;
                        commandsSent = false;
                        DoneDropping = true;
                        sendServerCommand(client, "warp murk");
                        WorldChecker.reset(); // force re-confirmation after warp
                        sendPlayerMessage(client, "§3[§bTrident§3] §7Warping to Murkwater...");
                        lastWarpAttempt = now;
                    }
                }
            }

            CameraManager.tick();
        });

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            CameraManager.updateViewSmooth(1.0F);

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return;

            Camera camera = client.gameRenderer.getCamera();
            Vec3d cameraPos = camera.getCameraPos();

            MatrixStack matrices = new MatrixStack();
            matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

            VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
            VertexConsumer buffer = immediate.getBuffer(RenderLayers.LINES);

            for (BlockPos pos : blockList) {
                Box box = new Box(pos);
                drawBoxEdges(matrices, buffer, box, r, g, b, 1f);
            }

            for (int i = 0; i < blockList.size() - 1; i++) {
                Vec3d start = Vec3d.ofCenter(blockList.get(i));
                Vec3d end = Vec3d.ofCenter(blockList.get(i + 1));
                drawLine(matrices, buffer, start, end, r, g, b, 1f);
            }

            immediate.draw();
        });
    }

    public void ApplicaConfigurazioni() {
        MacroConfig.load();
        MacroConfig config = MacroConfig.get();
        ESPToggle(config.espEnabled);
        Nuker.setToggle(config.nukerToggle);
        CameraManager.editspeed(config.pathRotationSpeed);
        CameraManager.setpickles(config.pathPickleSwapDetection);
        this.shouldLoop = config.pathLoopActive;
    }

    private static void checkPlayersOnce(MinecraftClient client) {
        for (PlayerEntity player : client.world.getPlayers()) {
            String playerName = player.getName().getString();
            if (TARGET_PLAYERS.contains(playerName)) {
                sendPlayerMessage(client, "§3[§bTrident§3] §7FOUND OPP RUN BOYYY");
                CameraManager.stopMacro();
                return;
            }
        }
    }

    public static int countSeaLumies(PlayerEntity player) {
        int count = 0;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.PLAYER_HEAD) &&
                    stack.getName().getString().toLowerCase().contains("enchanted sea lumies")) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void dropSeaLumies(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;
        if (droppingInProgress) return;
        droppingInProgress = true;
        dropIndex = 0;
        client.execute(() -> client.setScreen(new InventoryScreen(client.player)));
        sendPlayerMessage(client, "§3[§bTrident§3] §7Dropping Enchanted Sea Lumies...");
        scheduleNextDrop(client);
    }

    private void scheduleNextDrop(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            droppingInProgress = false;
            DoneDropping = true;
            return;
        }
        PlayerInventory inv = client.player.getInventory();
        while (dropIndex < inv.size()) {
            ItemStack stack = inv.getStack(dropIndex);
            dropIndex++;
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().replaceAll("§.", "").trim().toLowerCase(Locale.ROOT);
            if (name.contains("enchanted sea lumies")) {
                int slotId = dropIndex - 1 < 9 ? 36 + (dropIndex - 1) : (dropIndex - 1);
                client.interactionManager.clickSlot(
                        client.player.currentScreenHandler.syncId,
                        slotId,
                        1,
                        SlotActionType.THROW,
                        client.player
                );
                CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS)
                        .execute(() -> scheduleNextDrop(client));
                return;
            }
        }
        client.execute(() -> client.setScreen(null));
        DoneDropping = true;
        droppingInProgress = false;
        sendPlayerMessage(client, "§3[§bTrident§3] §7Finished dropping all Enchanted Sea Lumies.");
    }

    private void startMacro(MinecraftClient client) {
        macroActive = true;
        commandsSent = false;
        currentTargetIndex = 0;
        onIsland = false;
        relistInProgress = false;
        SeaLumiesHUD.setManualStop(false);
        SeaLumiesTracker.tick(client.player);
        SeaLumiesHUD.lastPickleCount = countSeaLumies(client.player);
        CameraManager.clearAllTargets();
        for (BlockPos pos : blockList) {
            CameraManager.addTarget(pos);
        }
        checkPlayersOnce(client);
        CameraManager.toggleMacro();
    }

    private void stopMacro(MinecraftClient client) {
        if (!macroActive) return;
        macroActive = false;
        onIsland = false;
        relistInProgress = false;
        SeaLumiesTracker.tick(client.player);
        SeaLumiesHUD.setManualStop(true);
        SeaLumiesHUD.resetTimer();
        CameraManager.clearAllTargets();
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    public void ESPToggle(boolean val) {
        ESPToggle = val;
    }

    public static int getRelistCooldownTicks() {
        return instance != null ? instance.relistCooldownTicks : 0;
    }

    public static boolean isRelistInProgress() {
        return instance != null && instance.relistInProgress;
    }

    public static boolean isBazaarLoaded() {
        return bazaarLoaded;
    }

    public static boolean isOnIsland() {
        return onIsland;
    }

    private void sendServerCommand(MinecraftClient client, String command) {
        if (client.getNetworkHandler() != null && client.player != null) {
            try {
                client.getNetworkHandler().sendChatCommand(command);
            } catch (Exception e) {
                client.player.sendMessage(Text.literal("§3[§bTrident§3] §7Failed to send command: " + e.getMessage()), false);
            }
        }
    }

    private static void sendPlayerMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    public void setBlockList(List<BlockPos> list) {
        blockList.clear();
        blockList.addAll(list);
        PathVisualizer.spawnPathMarkers(blockList);
    }

    public void editblockpoint(int point) {
        assert MinecraftClient.getInstance().player != null;
        blockList.set(point, MinecraftClient.getInstance().player.getBlockPos());
        PathVisualizer.spawnPathMarkers(blockList);
    }

    public void removeblockpoint(int point) {
        assert MinecraftClient.getInstance().player != null;
        blockList.remove(point);
        PathVisualizer.spawnPathMarkers(blockList);
    }

    private void drawBoxEdges(MatrixStack matrices, VertexConsumer buffer, Box box,
                              float r, float g, float b, float a) {
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;

        line(matrices, buffer, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(matrices, buffer, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(matrices, buffer, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(matrices, buffer, x1, y1, z2, x1, y1, z1, r, g, b, a);

        line(matrices, buffer, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(matrices, buffer, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(matrices, buffer, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(matrices, buffer, x1, y2, z2, x1, y2, z1, r, g, b, a);

        line(matrices, buffer, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(matrices, buffer, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(matrices, buffer, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(matrices, buffer, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private void drawLine(MatrixStack matrices, VertexConsumer buffer,
                          Vec3d start, Vec3d end,
                          float r, float g, float b, float a) {
        MatrixStack.Entry entry = matrices.peek();
        buffer.vertex(entry.getPositionMatrix(), (float) start.x, (float) start.y, (float) start.z)
                .color(r, g, b, a)
                .normal(entry, 0f, 1f, 0f)
                .lineWidth(2f);
        buffer.vertex(entry.getPositionMatrix(), (float) end.x, (float) end.y, (float) end.z)
                .color(r, g, b, a)
                .normal(entry, 0f, 1f, 0f)
                .lineWidth(2f);
    }

    private void line(MatrixStack matrices, VertexConsumer buffer,
                      float x1, float y1, float z1,
                      float x2, float y2, float z2,
                      float r, float g, float b, float a) {
        MatrixStack.Entry entry = matrices.peek();
        buffer.vertex(entry.getPositionMatrix(), x1, y1, z1)
                .color(r, g, b, a)
                .normal(entry, 0f, 1f, 0f)
                .lineWidth(2f);
        buffer.vertex(entry.getPositionMatrix(), x2, y2, z2)
                .color(r, g, b, a)
                .normal(entry, 0f, 1f, 0f)
                .lineWidth(2f);
    }

    private void UpdateFrame() {
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