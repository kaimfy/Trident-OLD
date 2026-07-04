package com.trident.modules;

import com.bazaarmod.BazaarModClient;
import com.trident.core.Trident;
import com.trident.core.TridentLoader;
import com.trident.events.CameraManager;
import com.trident.events.WorldChecker;
import com.trident.gui.SeaLumiesHUD;
import com.trident.pathing.PathVisualizer;
import com.trident.tracking.MacroTracker;
import com.trident.tracking.SeaLumiesTracker;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sea Lumies macro — the flagship Murkwater sea-pickle route runner.
 *
 * <p>Previously this logic lived directly inside {@link TridentLoader}; it now lives here alongside
 * the other automation modules. The module owns the waypoint list, the location state machine
 * (limbo/lobby/wrong-zone recovery, island bazaar relist, item dropping) and the world-rendered
 * path overlay. Movement itself is delegated to {@link CameraManager}.
 */
@SuppressWarnings({ "deprecation", "null" })
public class SeaLumies {

    private static final MinecraftClient client = MinecraftClient.getInstance();

    // ===== Waypoints / path =====
    private static final List<BlockPos> blockList = new ArrayList<>();
    private static float r, g, b;

    // ===== Macro state (public flags read by tracker / auto-join / key handler) =====
    public static boolean macroActive = false;
    public static boolean macrotoggle = false;
    public static boolean commandsSent = false;

    private static boolean joinedServer = false;
    private static long islandArrivalTime = 0;

    private static boolean DoneDropping = true;
    private static boolean droppingInProgress = false;
    private static int dropIndex = 0;

    // ===== Bazaar relist =====
    private static boolean bazaarLoaded = false;
    private static boolean onIsland = false;
    public static int relistCooldownTicks = 0;
    private static boolean relistInProgress = false;
    public static final int RELIST_INTERVAL_TICKS = 20 * 60 * 15;
    private static long stuckSince = 0;

    // ===== World safety / warp retry =====
    private static long worldTime = 0;
    private static final long WARP_RETRY_MS = 100;
    private static long lastWarpAttempt = 0;

    // ===== Join manager =====
    private static int joincd = 40;
    private static boolean started = false;

    // ===== Movement stuck tracking =====
    private static int tickCounter = 0;
    private static Vec3d lastPlayerPos = new Vec3d(0, 0, 0);

    public static void init() {
        // Greeting / auth-failure notice always fires so the user gets feedback either way.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            if (!joinedServer) {
                joinedServer = true;
                if (Trident.access) {
                    sendPlayerMessage(mc, "§3[§bTrident§3] §7Account Verified. Have fun!");
                } else {
                    sendPlayerMessage(mc,
                            "§3[§bTrident§3] §7Couldnt Verify Your Account, Please Check Your Authentication.");
                }
            }
        });

        // Nothing else runs for unverified accounts.
        if (!Trident.access)
            return;

        bazaarLoaded = FabricLoader.getInstance().isModLoaded("bazaarmod");

        switch (Trident.getThemecolor()) {
            case "purple" -> { r = 0.7f; g = 0.2f; b = 1f; }
            case "red" -> { r = 1f; g = 0.2f; b = 0.2f; }
        }

        HudRenderCallback.EVENT.register(SeaLumiesHUD::render);
        ClientTickEvents.END_CLIENT_TICK.register(SeaLumies::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(SeaLumies::renderPath);
    }

    private static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null)
            return;

        boolean loadingScreen = mc.currentScreen != null &&
                mc.currentScreen.getClass().getName().contains("class_3928");

        if (!loadingScreen) {
            worldTime++; // ONLY advances when world is stable
        }

        SeaLumiesTracker.tick(mc.player);

        // Auto-start sequence once the account is verified and we've joined
        if (joinedServer && !started) {
            if (joincd > 0) {
                joincd--;
            }
            switch (joincd) {
                case 5 -> {
                    Objects.requireNonNull(mc.getNetworkHandler()).sendChatCommand("trident load default");
                    SeaLumiesHUD.setActive(true);
                }
                case 0 -> {
                    MacroTracker.startTracking(mc.player);
                    macrotoggle = true;
                    relistCooldownTicks = RELIST_INTERVAL_TICKS;
                    WorldChecker.reset();
                    sendPlayerMessage(mc,
                            "§3[§bTrident§3] §7Macro started with " + blockList.size() + " points");
                    started = true;
                }
            }
        }

        if (mc.currentScreen != null && mc.currentScreen.shouldPause()) {
            stopMacro();
        }

        tickCounter++;

        // Track movement for stuck detection
        if (tickCounter % 3 == 0) {
            Vec3d currentPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            if (currentPos.squaredDistanceTo(lastPlayerPos) < 0.05) {
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

        WorldChecker.tick(mc);

        // ── Limbo detection (no scoreboard for 10s) ──────────────────────
        if (WorldChecker.isScoreboardTimedOut()) {
            if (macroActive)
                stopMacro();
            if (worldTime - lastWarpAttempt > WARP_RETRY_MS) {
                sendPlayerMessage(mc, "§3[§bTrident§3] §7Limbo detected, sending /lobby");
                sendServerCommand(mc, "lobby");
                lastWarpAttempt = worldTime;
            }
            CameraManager.tick();
            return;
        }

        // ── Relist countdown (runs while macro active or on island) ──────
        if (bazaarLoaded && (macroActive || commandsSent)) {
            if (relistCooldownTicks > 0) {
                if (relistCooldownTicks % 200 == 0)
                    sendPlayerMessage(mc, "§3[§bTrident§3] §7Relist in: " + relistCooldownTicks / 20 + "s");
                relistCooldownTicks--;
            }
        }

        // ── Location state machine ────────────────────────────────────────
        WorldChecker.Location loc = WorldChecker.getLocation();

        if (loc == null) {
            // Scoreboard not loaded yet, wait
        } else if (loc == WorldChecker.Location.LOBBY) {
            if (macroActive)
                stopMacro();
            if (worldTime - lastWarpAttempt > WARP_RETRY_MS + 100) {
                sendPlayerMessage(mc, "§3[§bTrident§3] §7Lobby detected, sending /play skyblock");
                sendServerCommand(mc, "play skyblock");
                WorldChecker.reset();
                lastWarpAttempt = worldTime;
            }
        } else if (loc == WorldChecker.Location.TANGLEBURG || loc == WorldChecker.Location.SKYBLOCK_UNKNOWN) {
            if (macroActive)
                stopMacro();
            if (worldTime - lastWarpAttempt > WARP_RETRY_MS) {
                sendPlayerMessage(mc, "§3[§bTrident§3] §7Wrong zone (" + loc + "), retrying /warp murk");
                sendServerCommand(mc, "warp murk");
                WorldChecker.reset();
                lastWarpAttempt = worldTime;
            }
        } else if (loc == WorldChecker.Location.MURKWATER) {
            // Reset warp flag — we're in the correct zone now
            commandsSent = false;

            if (!macroActive) {
                int pickles = CameraManager.countSeaPicklesInArea();
                if (worldTime - lastWarpAttempt > WARP_RETRY_MS) {
                    sendPlayerMessage(mc, "§3[§bTrident§3] §7Sea Pickles Found: " + pickles);
                    if (pickles < CameraManager.pickleamount) {
                        stopMacro();
                        commandsSent = true;
                        onIsland = false;
                        sendServerCommand(mc, "is");
                        WorldChecker.reset(); // force re-confirmation after warp
                        lastWarpAttempt = worldTime;
                    }
                }
                if (pickles > CameraManager.pickleamount) {
                    sendPlayerMessage(mc, "§3[§bTrident§3] §7In Murkwater, starting macro");
                    startMacro();
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
                        stopMacro();
                        commandsSent = true;
                        onIsland = false;
                        sendServerCommand(mc, "is");
                        WorldChecker.reset();
                        lastWarpAttempt = worldTime;
                        sendPlayerMessage(mc, "§3[§bTrident§3] §7Path stalled, restarting macro...");
                        stuckSince = 0;
                        return;
                    }
                } else {
                    stuckSince = 0;
                }
            }

            // Check if path finished
            if (macroActive && CameraManager.finishedRoute()) {
                stopMacro();
                commandsSent = true;
                onIsland = false;
                sendServerCommand(mc, "is");
                WorldChecker.reset(); // force re-confirmation after warp
                lastWarpAttempt = worldTime;
                sendPlayerMessage(mc, "§3[§bTrident§3] §7Path complete, warping to island");
            }

            if (!macroActive && commandsSent && !onIsland && worldTime - lastWarpAttempt > WARP_RETRY_MS) {
                sendPlayerMessage(mc, "§3[§bTrident§3] §7Island warp failed, retrying...");
                sendServerCommand(mc, "is");
                lastWarpAttempt = worldTime;
            }
        } else if (loc == WorldChecker.Location.YOUR_ISLAND) {
            if (macroActive)
                stopMacro();
            commandsSent = true;

            if (!onIsland) {
                onIsland = true;
                islandArrivalTime = worldTime;
                sendPlayerMessage(mc, "§3[§bTrident§3] §7On island.");
            }

            long timeOnIsland = worldTime - islandArrivalTime;

            // Trigger relist after 5s if cooldown is done
            if (bazaarLoaded && relistCooldownTicks == 0 && !relistInProgress && timeOnIsland >= 100) {
                relistInProgress = true;
                BazaarModClient.MANUAL_RELIST.trigger(mc);
                sendPlayerMessage(mc, "§3[§bTrident§3] §7Starting bazaar relist...");
            }

            // Wait for relist to finish
            if (bazaarLoaded && relistInProgress) {
                if (!BazaarModClient.MANUAL_RELIST.isRunning()) {
                    relistInProgress = false;
                    relistCooldownTicks = RELIST_INTERVAL_TICKS;
                    sendPlayerMessage(mc, "§3[§bTrident§3] §7Relist finished.");
                } else {
                    CameraManager.tick();
                    return; // still relisting, wait
                }
            }

            // Drop lumies if needed (only without bazaarmod)
            if (!bazaarLoaded && countSeaLumies(mc.player) >= 500) {
                if (DoneDropping)
                    DoneDropping = false;
                if (!DoneDropping) {
                    dropSeaLumies(mc);
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
                    sendServerCommand(mc, "warp murk");
                    WorldChecker.reset();
                    sendPlayerMessage(mc, "§3[§bTrident§3] §7Warping to Murkwater...");
                    lastWarpAttempt = worldTime;
                }
            }
        }

        CameraManager.tick();
    }

    // ===== Macro lifecycle =====

    private static void startMacro() {
        macroActive = true;
        commandsSent = false;
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

    public static void stopMacro() {
        if (!macroActive)
            return;
        macroActive = false;
        onIsland = false;
        relistInProgress = false;
        SeaLumiesTracker.tick(client.player);
        SeaLumiesHUD.setManualStop(true);
        SeaLumiesHUD.resetTimer();
        CameraManager.clearAllTargets();
        if (client.player != null) {
            client.options.forwardKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
        }
    }

    /** Toggles the macro on/off from the keybind, mirroring the old KeyHandler behaviour. */
    public static void toggleFromKey(MinecraftClient mc) {
        if (macroActive || macrotoggle) {
            macrotoggle = false;
            SeaLumiesHUD.setActive(false);
            MacroTracker.stopTracking();
            stopMacro();
            WorldChecker.reset();
            sendPlayerMessage(mc, "§3[§bTrident§3] §7Macro stopped");
        } else if (!blockList.isEmpty()) {
            SeaLumiesHUD.setActive(true);
            MacroTracker.startTracking(mc.player);
            macrotoggle = true;
            relistCooldownTicks = RELIST_INTERVAL_TICKS;
            WorldChecker.reset();
            sendPlayerMessage(mc, "§3[§bTrident§3] §7Macro started with " + blockList.size() + " points");
        } else {
            sendPlayerMessage(mc, "§3[§bTrident§3] §7No positions saved");
        }
    }

    // ===== Waypoint management =====

    public static List<BlockPos> getBlockList() {
        return blockList;
    }

    public static void saveCoord(MinecraftClient mc) {
        if (mc.player == null)
            return;
        BlockPos pos = mc.player.getBlockPos();
        blockList.add(pos);
        CameraManager.addsave(pos);
        sendPlayerMessage(mc,
                "§3[§bTrident§3] §7Saved position #" + blockList.size() + " at: " + pos.toShortString());
        PathVisualizer.spawnPathMarkers(blockList);
    }

    public static void clearAll(MinecraftClient mc) {
        stopMacro();
        macrotoggle = false;
        blockList.clear();
        CameraManager.clearsave();
        WorldChecker.reset();
        PathVisualizer.spawnPathMarkers(blockList);
        sendPlayerMessage(mc, "§3[§bTrident§3] §7Cleared all positions");
    }

    public static void setBlockList(List<BlockPos> list) {
        blockList.clear();
        blockList.addAll(list);
        PathVisualizer.spawnPathMarkers(blockList);
    }

    public static void editBlockPoint(int point) {
        PlayerEntity player = client.player;
        if (player != null && point >= 0 && point < blockList.size()) {
            blockList.set(point, player.getBlockPos());
            PathVisualizer.spawnPathMarkers(blockList);
        }
    }

    public static void removeBlockPoint(int point) {
        PlayerEntity player = client.player;
        if (player != null && point >= 0 && point < blockList.size()) {
            blockList.remove(point);
            PathVisualizer.spawnPathMarkers(blockList);
        }
    }

    // ===== Sea Lumies counting / dropping =====

    public static int countSeaLumies(PlayerEntity player) {
        if (player == null)
            return 0;
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

    private static void dropSeaLumies(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null)
            return;
        if (droppingInProgress)
            return;
        droppingInProgress = true;
        dropIndex = 0;
        mc.execute(() -> mc.setScreen(new InventoryScreen(mc.player)));
        sendPlayerMessage(mc, "§3[§bTrident§3] §7Dropping Enchanted Sea Lumies...");
        scheduleNextDrop(mc);
    }

    private static void scheduleNextDrop(MinecraftClient mc) {
        PlayerEntity player = mc.player;
        if (player == null || mc.interactionManager == null) {
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
                mc.interactionManager.clickSlot(
                        player.currentScreenHandler.syncId,
                        slotId,
                        1,
                        SlotActionType.THROW,
                        player);
                CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS)
                        .execute(() -> scheduleNextDrop(mc));
                return;
            }
        }
        mc.execute(() -> mc.setScreen(null));
        DoneDropping = true;
        droppingInProgress = false;
        sendPlayerMessage(mc, "§3[§bTrident§3] §7Finished dropping all Enchanted Sea Lumies.");
    }

    // ===== HUD / status accessors =====

    public static int getRelistCooldownTicks() {
        return relistCooldownTicks;
    }

    public static boolean isRelistInProgress() {
        return relistInProgress;
    }

    public static boolean isBazaarLoaded() {
        return bazaarLoaded;
    }

    public static boolean isOnIsland() {
        return onIsland;
    }

    // ===== Chat helpers =====

    private static void sendServerCommand(MinecraftClient mc, String command) {
        var handler = mc.getNetworkHandler();
        PlayerEntity player = mc.player;
        if (handler != null && player != null) {
            try {
                handler.sendChatCommand(command);
            } catch (Exception e) {
                player.sendMessage(Text.literal("§3[§bTrident§3] §7Failed to send command: " + e.getMessage()), false);
            }
        }
    }

    private static void sendPlayerMessage(MinecraftClient mc, String message) {
        PlayerEntity player = mc.player;
        if (player != null) {
            player.sendMessage(Text.literal(message), false);
        }
    }

    // ===== Path rendering =====

    private static void renderPath(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context) {
        CameraManager.updateViewSmooth(1.0F);

        if (client.player == null || client.world == null)
            return;
        if (blockList.isEmpty())
            return;

        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getCameraPos();

        MatrixStack matrices = new MatrixStack();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer buffer = immediate.getBuffer(RenderLayers.LINES);

        for (BlockPos pos : blockList) {
            drawBoxEdges(matrices, buffer, new Box(pos), r, g, b, 1f);
        }

        for (int i = 0; i < blockList.size() - 1; i++) {
            Vec3d start = Vec3d.ofCenter(blockList.get(i));
            Vec3d end = Vec3d.ofCenter(blockList.get(i + 1));
            drawLine(matrices, buffer, start, end, r, g, b, 1f);
        }

        immediate.draw();
    }

    private static void drawBoxEdges(MatrixStack matrices, VertexConsumer buffer, Box box,
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

    private static void drawLine(MatrixStack matrices, VertexConsumer buffer,
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

    private static void line(MatrixStack matrices, VertexConsumer buffer,
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
}
