package com.trident.core;

import com.bazaarmod.BazaarModClient;
import com.trident.config.MacroConfig;
import com.trident.events.AutoJoinHypixel;
import com.trident.events.CameraManager;
import com.trident.events.KeyHandler;
import com.trident.events.WorldChecker;

import com.trident.gui.SeaLumiesHUD;
import com.trident.modules.*;
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

@SuppressWarnings({ "deprecation", "null" })
public class TridentLoader implements ClientModInitializer {



    private static boolean joinedServer = false;
    private long islandArrivalTime = 0;
    public final AutoFishModule autoFish = new AutoFishModule();
    private static int framecount = 0;
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
    public int relistCooldownTicks = 0;
    private boolean relistInProgress = false;
    public static final int RELIST_INTERVAL_TICKS = 20 * 60 * 15;
    private long stuckSince = 0;

    //World safety

    private long worldTime = 0;
    // Warp retry
    private static final long WARP_RETRY_MS = 100;
    private long lastWarpAttempt = 0;

    // join manager
    private int joincd = 40;
    private boolean started = false;

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

        TridentInit();


        HudRenderCallback.EVENT.register(SeaLumiesHUD::render);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!joinedServer) {
                joinedServer = true;
                sendPlayerMessage(client, "§3[§bTrident§3] §7Account Verified. Have fun!");
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            UpdateFrame();
            if (client.world == null || client.player == null || client.getNetworkHandler() == null)
                return;


            boolean loadingScreen =
                    client.currentScreen != null &&
                            client.currentScreen.getClass().getName().contains("class_3928");

            if (client.world != null &&
                    client.player != null &&
                    client.getNetworkHandler() != null &&
                    !loadingScreen) {


                worldTime++; // ONLY advances when world is stable
            } else {

            }
            SeaLumiesTracker.tick(client.player);

            if (joinedServer && !started) {
                if (joincd > 0) {
                    joincd--;
                }
                switch (joincd) {
                    case 5:
                        Objects.requireNonNull(client.getNetworkHandler()).sendChatCommand("trident load default");
                        SeaLumiesHUD.setActive(true);
                        break;
                    case 0:
                        MacroTracker.startTracking(client.player);
                        macrotoggle = true;
                        relistCooldownTicks = RELIST_INTERVAL_TICKS;
                        WorldChecker.reset();
                        sendPlayerMessage(client,
                                "§3[§bTrident§3] §7Macro started with " + blockList.size() + " points");
                        started = true;
                        break;
                }

            }
            autoFish.tick();
            // ── Key handlers ────────────────────────────────────────────────
            KeyHandler.tick(client, this);


            if (client.currentScreen != null && client.currentScreen.shouldPause()) {
                stopMacro(client);
            }

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
                if (macroActive)
                    stopMacro(client);
                if (worldTime - lastWarpAttempt > WARP_RETRY_MS) {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Limbo detected, sending /lobby");
                    sendServerCommand(client, "lobby");
                    lastWarpAttempt = worldTime;
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


            if (loc == null) {
                // Scoreboard not loaded yet, wait
            } else if (loc == WorldChecker.Location.LOBBY) {
                if (macroActive)
                    stopMacro(client);
                if (worldTime - lastWarpAttempt > WARP_RETRY_MS+100) {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Lobby detected, sending /play skyblock");
                    sendServerCommand(client, "play skyblock");
                    WorldChecker.reset();
                    lastWarpAttempt = worldTime;
                }
            } else if (loc == WorldChecker.Location.TANGLEBURG || loc == WorldChecker.Location.SKYBLOCK_UNKNOWN) {
                if (macroActive)
                    stopMacro(client);
                if (worldTime - lastWarpAttempt > WARP_RETRY_MS) {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Wrong zone (" + loc + "), retrying /warp murk");
                    sendServerCommand(client, "warp murk");
                    WorldChecker.reset();
                    lastWarpAttempt = worldTime;
                }
            } else if (loc == WorldChecker.Location.MURKWATER) {

                // Reset warp flag — we're in the correct zone now
                commandsSent = false;
                // Correct zone — start macro if not already running

                if (!macroActive) {
                    int pickles = CameraManager.countSeaPicklesInArea();
                    if (worldTime - lastWarpAttempt > WARP_RETRY_MS) {

                        sendPlayerMessage(client, "§3[§bTrident§3] §7Sea Pickles Found: " + pickles);
                        if (pickles < CameraManager.pickleamount) {

                            stopMacro(client);
                            commandsSent = true;
                            onIsland = false;
                            sendServerCommand(client, "is");
                            WorldChecker.reset(); // force re-confirmation after warp
                            lastWarpAttempt = worldTime;
                        }
                    }
                    if(pickles > CameraManager.pickleamount){
                        sendPlayerMessage(client, "§3[§bTrident§3] §7In Murkwater, starting macro");
                        startMacro(client);
                    }

                }
                if (macroActive) {

                    boolean moving = CameraManager.isMoving;


                    if (!moving) {

                        if (stuckSince == 0) {
                            stuckSince = worldTime;
                        }

                        long stuckTime = worldTime - stuckSince;

                        if (stuckTime > 160) {

                            stopMacro(client);
                            commandsSent = true;
                            onIsland = false;
                            sendServerCommand(client, "is");
                            WorldChecker.reset();
                            lastWarpAttempt = worldTime;
                            sendPlayerMessage(client,
                                    "§3[§bTrident§3] §7Path stalled, restarting macro...");




                            stuckSince = 0;
                            return;
                        }

                    } else {
                        stuckSince = 0;
                    }
                }

                // Check if path finished
                if (macroActive && CameraManager.finishedRoute()) {
                    stopMacro(client);
                    commandsSent = true;
                    onIsland = false;
                    sendServerCommand(client, "is");
                    WorldChecker.reset(); // force re-confirmation after warp
                    lastWarpAttempt = worldTime;
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Path complete, warping to island");
                }

                if (!macroActive && commandsSent && !onIsland && worldTime - lastWarpAttempt > WARP_RETRY_MS) {
                    sendPlayerMessage(client, "§3[§bTrident§3] §7Island warp failed, retrying...");
                    sendServerCommand(client, "is");
                    lastWarpAttempt = worldTime;
                }
            } else if (loc == WorldChecker.Location.YOUR_ISLAND) {
                if (macroActive)
                    stopMacro(client);
                commandsSent = true;

                if (!onIsland) {
                    onIsland = true;
                    islandArrivalTime = worldTime;
                    sendPlayerMessage(client, "§3[§bTrident§3] §7On island.");
                }

                long timeOnIsland = worldTime - islandArrivalTime;

                // Trigger relist after 5s if cooldown is done
                if (bazaarLoaded && relistCooldownTicks == 0 && !relistInProgress && timeOnIsland >= 100) {
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
                        return; // still relisting, wait
                    }
                }

                // Drop lumies if needed (only without bazaarmod)
                if (!bazaarLoaded && countSeaLumies(client.player) >= 500) {
                    if (DoneDropping)
                        DoneDropping = false;
                    if (!DoneDropping) {
                        dropSeaLumies(client);
                        CameraManager.tick();
                        return;
                    }
                }

                // Ready to leave — but wait at least 5s AND for relist to be done
                boolean relistDone = !bazaarLoaded
                        || (!relistInProgress && (relistCooldownTicks > 0 || timeOnIsland >= 100));
                if (DoneDropping && relistDone) {
                    if (worldTime - lastWarpAttempt > WARP_RETRY_MS) {
                        onIsland = false;
                        commandsSent = false;
                        DoneDropping = true;
                        sendServerCommand(client, "warp murk");
                        WorldChecker.reset();
                        sendPlayerMessage(client, "§3[§bTrident§3] §7Warping to Murkwater...");
                        lastWarpAttempt = worldTime;
                    }
                }
            }

            CameraManager.tick();
        });

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            CameraManager.updateViewSmooth(1.0F);

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null)
                return;

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
        if (client.player == null || client.interactionManager == null)
            return;
        if (droppingInProgress)
            return;
        droppingInProgress = true;
        dropIndex = 0;
        client.execute(() -> client.setScreen(new InventoryScreen(client.player)));
        sendPlayerMessage(client, "§3[§bTrident§3] §7Dropping Enchanted Sea Lumies...");
        scheduleNextDrop(client);
    }

    private void scheduleNextDrop(MinecraftClient client) {
        PlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            droppingInProgress = false;
            DoneDropping = true;
            return;
        }
        PlayerInventory inv = player.getInventory();
        while (dropIndex < inv.size()) {
            ItemStack stack = inv.getStack(dropIndex);
            dropIndex++;
            if (stack.isEmpty())
                continue;
            String name = stack.getName().getString().replaceAll("§.", "").trim().toLowerCase(Locale.ROOT);
            if (name.contains("enchanted sea lumies")) {
                int slotId = dropIndex - 1 < 9 ? 36 + (dropIndex - 1) : (dropIndex - 1);
                client.interactionManager.clickSlot(
                        player.currentScreenHandler.syncId,
                        slotId,
                        1,
                        SlotActionType.THROW,
                        player);
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

        CameraManager.toggleMacro();
    }

    public void stopMacro(MinecraftClient client) {
        if (!macroActive)
            return;
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
        var handler = client.getNetworkHandler();
        PlayerEntity player = client.player;
        if (handler != null && player != null) {
            try {
                handler.sendChatCommand(command);
            } catch (Exception e) {
                player.sendMessage(Text.literal("§3[§bTrident§3] §7Failed to send command: " + e.getMessage()), false);
            }
        }
    }

    public static void sendPlayerMessage(MinecraftClient client, String message) {
        PlayerEntity player = client.player;
        if (player != null) {
            player.sendMessage(Text.literal(message), false);
        }
    }

    public void setBlockList(List<BlockPos> list) {
        blockList.clear();
        blockList.addAll(list);
        PathVisualizer.spawnPathMarkers(blockList);
    }

    public void editblockpoint(int point) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            blockList.set(point, player.getBlockPos());
            PathVisualizer.spawnPathMarkers(blockList);
        }
    }

    public void removeblockpoint(int point) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            blockList.remove(point);
            PathVisualizer.spawnPathMarkers(blockList);
        }
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
    public void TridentInit(){
        bazaarLoaded = FabricLoader.getInstance().isModLoaded("bazaarmod");

        if (!Trident.access) {
            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
                if (!joinedServer) {
                    joinedServer = true;
                    sendPlayerMessage(client,
                            "§3[§bTrident§3] §7Couldnt Verify Your Account, Please Check Your Authentication.");
                }
            });
            return;
        }

        switch (theme) {
            case "purple":
                r = 0.7f;
                g = 0.2f;
                b = 1f;
                break;
            case "red":
                r = 1f;
                g = 0.2f;
                b = 0.2f;
                break;
        }

        AutoJoinHypixel.init();
        AutoTunneler.init();
        Nuker.init();
        DwarvenMiningModule.init();
        ForagingModule.init();
        FailsafeManager.init();
        BazaarPriceFetcher.fetchPriceAsync();
        ApplicaConfigurazioni();
        RouteCommands.register();
        KeyHandler.register();

        MacroConfig config = MacroConfig.get();
        SeaLumiesTracker.loadFromConfig(
                config.lifetimeCoins,
                config.lifetimeMacroMinutes);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            MacroConfig meow = MacroConfig.get();
            meow.lifetimeCoins = SeaLumiesTracker.getLifetimeCoinsForSave();
            meow.lifetimeMacroMinutes = SeaLumiesTracker.getLifetimeMinutesForSave();
            MacroConfig.save();
        }));
    }
}