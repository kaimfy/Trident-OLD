package com.trident.gui;

import com.trident.config.MacroConfig;
import com.trident.core.Trident;
import com.trident.core.TridentLoader;
import com.trident.events.CameraManager;
import com.trident.events.KeyHandler;
import com.trident.modules.DwarvenMiningModule;
import com.trident.modules.ForagingModule;
import com.trident.modules.MobActionModule;
import com.trident.modules.Nuker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class CustomGUIScreen extends Screen {
    private int fullPanelWidth, fullPanelHeight;
    private float animationProgress = 0f;
    private final float animationSpeed = 0.06f;
    private int panelX, panelY;

    private final int sidebarWidth = 130;
    private int activeSectionIndex = 0;

    private final List<Sezione> sezioni = new ArrayList<>();
    private final int[] sidebarButtonColors = new int[20];

    // Scrolling
    private int scrollOffset = 0;
    private int maxScrollOffset = 0;

    // Keybind listening state
    private CustomKeybindButton listeningButton = null;

    // Icons for categories
    private static final String[] CATEGORY_ICONS = {"⛏", "🌲", "🎣", "⚔", "📦", "🛡", "🌐", "⌨", "⚙"};

    public CustomGUIScreen() {
        super(Text.literal("Custom GUI"));
    }

    @Override
    protected void init() {
        super.init();
        sezioni.clear();
        scrollOffset = 0;

        fullPanelWidth = (int) (this.width * 0.65f);
        fullPanelHeight = (int) (this.height * 0.75f);
        panelX = (this.width - fullPanelWidth) / 2;
        panelY = (this.height - fullPanelHeight) / 2;

        MacroConfig.load();
        MacroConfig config = MacroConfig.get();

        // ===== 1. Dwarven Mining =====
        Sezione dwarven = new Sezione("Dwarven Mining");
        dwarven.addControllo(new CustomToggleButton(0, 0, 100, 20, DwarvenMiningModule.isActive(), val -> {
            if (val) DwarvenMiningModule.start();
            else DwarvenMiningModule.stop();
        }, "Mining Toggle"));
        dwarven.addControllo(new CustomToggleButton(0, 0, 100, 20, config.dwarvenPrioritizeTitanium, val -> {
            config.dwarvenPrioritizeTitanium = val;
            MacroConfig.save();
        }, "Prioritize Titanium"));
        dwarven.addControllo(new CustomSlider(0, 0, 150, 12, 0.06f, 0.25f, config.dwarvenRotationSpeedMean, val -> {
            config.dwarvenRotationSpeedMean = val;
            MacroConfig.save();
        }, "Rotation Speed"));
        dwarven.addControllo(new CustomSlider(0, 0, 150, 12, 3f, 20f, config.dwarvenDelayMean, val -> {
            config.dwarvenDelayMean = val;
            MacroConfig.save();
        }, "Delay Mean (ticks)"));
        dwarven.addControllo(new CustomSlider(0, 0, 150, 12, 1f, 10f, config.dwarvenDelayStddev, val -> {
            config.dwarvenDelayStddev = val;
            MacroConfig.save();
        }, "Delay Variance"));
        dwarven.addControllo(new CustomToggleButton(0, 0, 100, 20, config.dwarvenLookAround, val -> {
            config.dwarvenLookAround = val;
            MacroConfig.save();
        }, "Look Around"));
        dwarven.addControllo(new CustomSlider(0, 0, 150, 12, 0f, 0.2f, config.dwarvenMicroPauseChance, val -> {
            config.dwarvenMicroPauseChance = val;
            MacroConfig.save();
        }, "Micro-Pause Chance"));

        // ===== 2. Foraging =====
        Sezione foraging = new Sezione("Foraging");
        foraging.addControllo(new CustomToggleButton(0, 0, 100, 20, ForagingModule.isActive(), val -> {
            if (val) ForagingModule.start();
            else ForagingModule.stop();
        }, "Foraging Toggle"));
        foraging.addControllo(new CustomLabel(0, 0, "--- Tree Types ---"));
        foraging.addControllo(new CustomToggleButton(0, 0, 100, 20, config.foragingOak, val -> {
            config.foragingOak = val;
            MacroConfig.save();
        }, "Oak"));
        foraging.addControllo(new CustomToggleButton(0, 0, 100, 20, config.foragingSpruce, val -> {
            config.foragingSpruce = val;
            MacroConfig.save();
        }, "Spruce"));
        foraging.addControllo(new CustomToggleButton(0, 0, 100, 20, config.foragingBirch, val -> {
            config.foragingBirch = val;
            MacroConfig.save();
        }, "Birch"));
        foraging.addControllo(new CustomToggleButton(0, 0, 100, 20, config.foragingJungle, val -> {
            config.foragingJungle = val;
            MacroConfig.save();
        }, "Jungle"));
        foraging.addControllo(new CustomToggleButton(0, 0, 100, 20, config.foragingAcacia, val -> {
            config.foragingAcacia = val;
            MacroConfig.save();
        }, "Acacia"));
        foraging.addControllo(new CustomToggleButton(0, 0, 100, 20, config.foragingDarkOak, val -> {
            config.foragingDarkOak = val;
            MacroConfig.save();
        }, "Dark Oak"));
        foraging.addControllo(new CustomToggleButton(0, 0, 100, 20, config.foragingMangrove, val -> {
            config.foragingMangrove = val;
            MacroConfig.save();
        }, "Mangrove (high stats)"));
        foraging.addControllo(new CustomToggleButton(0, 0, 100, 20, config.foragingCherry, val -> {
            config.foragingCherry = val;
            MacroConfig.save();
        }, "Cherry"));
        foraging.addControllo(new CustomLabel(0, 0, "--- Humanization ---"));
        foraging.addControllo(new CustomSlider(0, 0, 150, 12, 0.06f, 0.25f, config.foragingRotationSpeedMean, val -> {
            config.foragingRotationSpeedMean = val;
            MacroConfig.save();
        }, "Rotation Speed"));
        foraging.addControllo(new CustomSlider(0, 0, 150, 12, 3f, 20f, config.foragingDelayMean, val -> {
            config.foragingDelayMean = val;
            MacroConfig.save();
        }, "Delay Mean (ticks)"));
        foraging.addControllo(new CustomSlider(0, 0, 150, 12, 1f, 10f, config.foragingDelayStddev, val -> {
            config.foragingDelayStddev = val;
            MacroConfig.save();
        }, "Delay Variance"));
        foraging.addControllo(new CustomToggleButton(0, 0, 100, 20, config.foragingLookAround, val -> {
            config.foragingLookAround = val;
            MacroConfig.save();
        }, "Look Around"));
        foraging.addControllo(new CustomSlider(0, 0, 150, 12, 0f, 0.2f, config.foragingMicroPauseChance, val -> {
            config.foragingMicroPauseChance = val;
            MacroConfig.save();
        }, "Micro-Pause Chance"));
        foraging.addControllo(new CustomSlider(0, 0, 150, 12, 5f, 30f, config.foragingScanRadius, val -> {
            config.foragingScanRadius = val;
            MacroConfig.save();
        }, "Scan Radius"));

        // ===== 3. Fishing =====
        Sezione fishing = new Sezione("Fishing");
        fishing.addControllo(new CustomLabel(0, 0, "AutoFish is toggled via keybind (J)"));
        fishing.addControllo(new CustomLabel(0, 0, "Status is shown in-game when active"));

        // ===== 3. Combat =====
        Sezione combat = new Sezione("Combat");
        combat.addControllo(new CustomToggleButton(0, 0, 100, 20, MobActionModule.isOn, val -> {
            MobActionModule.isOn = val;
        }, "Mob Action Toggle"));
        combat.addControllo(new CustomLabel(0, 0, "Targets: Taurus > Eel > Flame > Slug > Moogma/Worm/Leech"));

        // ===== 4. Path =====
        Sezione path = new Sezione("Path");
        path.addControllo(new CustomSlider(0, 0, 150, 12, 0.01F, 0.5F, config.pathRotationSpeed, val -> {
            config.pathRotationSpeed = val;
            MacroConfig.save();
            CameraManager.editspeed(val);
        }, "Rotation Speed"));
        path.addControllo(new CustomSlider(0, 0, 150, 12, 35, 130, config.pathPickleSwapDetection, val -> {
            config.pathPickleSwapDetection = val;
            MacroConfig.save();
            CameraManager.setpickles(val);
        }, "Pickle Swap Detection"));
        path.addControllo(new CustomToggleButton(0, 0, 100, 20, config.pathLoopActive, val -> {
            config.pathLoopActive = val;
            MacroConfig.save();
            TridentLoader.getInstance().shouldLoop = val;
        }, "Loop Toggle"));

        // ===== 5. Failsafe =====
        Sezione failsafe = new Sezione("Failsafe");
        failsafe.addControllo(new CustomToggleButton(0, 0, 100, 20, config.failsafeEnabled, val -> {
            config.failsafeEnabled = val;
            MacroConfig.save();
        }, "Failsafe Enabled"));
        failsafe.addControllo(new CustomDropdown(0, 0, 120, 20,
                new String[]{"WARN", "WARP", "DISCONNECT"}, config.failsafeAction, val -> {
            config.failsafeAction = val;
            MacroConfig.save();
        }, "Escape Action"));
        failsafe.addControllo(new CustomToggleButton(0, 0, 100, 20, config.failsafeReactNaturally, val -> {
            config.failsafeReactNaturally = val;
            MacroConfig.save();
        }, "React Naturally"));
        failsafe.addControllo(new CustomToggleButton(0, 0, 100, 20, config.failsafeSendChat, val -> {
            config.failsafeSendChat = val;
            MacroConfig.save();
        }, "Send Chat (risky!)"));
        failsafe.addControllo(new CustomToggleButton(0, 0, 100, 20, config.failsafeTeleportDetect, val -> {
            config.failsafeTeleportDetect = val;
            MacroConfig.save();
        }, "Teleport Detection"));
        failsafe.addControllo(new CustomToggleButton(0, 0, 100, 20, config.failsafeInventoryDetect, val -> {
            config.failsafeInventoryDetect = val;
            MacroConfig.save();
        }, "Inventory Detection"));
        failsafe.addControllo(new CustomToggleButton(0, 0, 100, 20, config.failsafeRotationSnapDetect, val -> {
            config.failsafeRotationSnapDetect = val;
            MacroConfig.save();
        }, "Rotation Snap Detection"));
        failsafe.addControllo(new CustomSlider(0, 0, 150, 12, 20, 120, config.failsafeReactionDurationTicks, val -> {
            config.failsafeReactionDurationTicks = (int) val.floatValue();
            MacroConfig.save();
        }, "Reaction Duration (ticks)"));

        // ===== 6. Discord =====
        Sezione discord = new Sezione("Discord");
        discord.addControllo(new CustomToggleButton(0, 0, 100, 20, config.discordEnabled, val -> {
            config.discordEnabled = val;
            MacroConfig.save();
        }, "Discord Enabled"));
        discord.addControllo(new CustomLabel(0, 0, "API: " + config.discordApiBase));
        discord.addControllo(new CustomSlider(0, 0, 150, 12, 30, 1800, config.discordPingIntervalSeconds, val -> {
            config.discordPingIntervalSeconds = (int) val.floatValue();
            MacroConfig.save();
        }, "Ping Interval (sec)"));
        discord.addControllo(new CustomSlider(0, 0, 150, 12, 2, 30, config.discordPollIntervalSeconds, val -> {
            config.discordPollIntervalSeconds = (int) val.floatValue();
            MacroConfig.save();
        }, "Poll Interval (sec)"));

        // ===== 8. Keybinds =====
        Sezione keybinds = new Sezione("Keybinds");
        for (KeyBinding binding : KeyHandler.getAllBindings()) {
            keybinds.addControllo(new CustomKeybindButton(0, 0, 120, 20, binding));
        }

        // ===== 9. General =====
        Sezione general = new Sezione("General");
        general.addControllo(new CustomToggleButton(0, 0, 100, 20, config.espEnabled, val -> {
            config.espEnabled = val;
            MacroConfig.save();
            TridentLoader.getInstance().ESPToggle(val);
        }, "Toggle ESP"));
        general.addControllo(new CustomToggleButton(0, 0, 100, 20, config.nukerToggle, val -> {
            config.nukerToggle = val;
            MacroConfig.save();
            Nuker.setToggle(val);
        }, "Nuker Toggle"));
        general.addControllo(new CustomLabel(0, 0, "Theme: " + Trident.getThemecolor()));

        sezioni.add(dwarven);
        sezioni.add(foraging);
        sezioni.add(fishing);
        sezioni.add(combat);
        sezioni.add(path);
        sezioni.add(failsafe);
        sezioni.add(discord);
        sezioni.add(keybinds);
        sezioni.add(general);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        if (animationProgress < 1f) {
            animationProgress = Math.min(1f, animationProgress + animationSpeed);
        }

        // Cubic ease-out
        float easedProgress = 1.0f - (float) Math.pow(1.0f - animationProgress, 3);
        int alphaInt = (int) (easedProgress * 255) & 0xFF;

        int currentPanelY = panelY + (int) ((1.0f - easedProgress) * 20);

        // ========== Draw panel background image ==========
        String theme = Trident.getThemecolor();
        Identifier bg = Identifier.of("trident", "textures/background/" + theme + "/" + TridentLoader.getFrame() + ".png");
        context.drawTexture(
                net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                bg,
                panelX, currentPanelY,
                0, 0,
                fullPanelWidth, fullPanelHeight,
                fullPanelWidth, fullPanelHeight
        );

        // Dimmer overlay for readability
        context.fill(panelX, currentPanelY, panelX + fullPanelWidth, currentPanelY + fullPanelHeight,
                ((int) (easedProgress * 180) << 24) | 0x000000);

        // Draw sidebar background
        int sidebarBgColor = ((int) (easedProgress * 220) << 24) | 0x141414;
        context.fill(panelX, currentPanelY, panelX + sidebarWidth, currentPanelY + fullPanelHeight, sidebarBgColor);

        // Sidebar separator line
        int separatorColor = (alphaInt << 24) | 0x333333;
        context.fill(panelX + sidebarWidth - 1, currentPanelY, panelX + sidebarWidth, currentPanelY + fullPanelHeight, separatorColor);

        // Title: Trident
        int tridentColor = (alphaInt << 24) | 0x3399ff;
        context.drawText(this.textRenderer, Text.literal("Trident"), panelX + 10, currentPanelY + 8, tridentColor, false);
        // Subtitle
        int subtitleColor = (alphaInt << 24) | 0x666666;
        context.drawText(this.textRenderer, Text.literal("Settings"), panelX + 60, currentPanelY + 8, subtitleColor, false);

        // Draw sidebar items (sections)
        int itemStartY = currentPanelY + 28;
        int itemHeight = 22;
        for (int i = 0; i < sezioni.size(); i++) {
            Sezione sezione = sezioni.get(i);
            int itemY = itemStartY + i * (itemHeight + 4);

            boolean isActive = (i == activeSectionIndex);
            boolean isHovered = mouseX >= panelX + 4 && mouseX <= panelX + sidebarWidth - 4
                    && mouseY >= itemY - 2 && mouseY <= itemY + itemHeight - 2;
            int offsetY = (isHovered && !isActive) ? 1 : 0;

            int targetHighlightColor = isActive ? 0x3399FF : (isHovered ? 0x3A3A3A : 0x1E1E1E);
            int currentColor = sidebarButtonColors[i];
            sidebarButtonColors[i] = lerpColor(currentColor, (alphaInt << 24) | targetHighlightColor, 0.15f);

            // Active indicator bar
            if (isActive) {
                int accentColor = (alphaInt << 24) | 0x3399FF;
                context.fill(panelX + 2, itemY - 1 + offsetY, panelX + 4, itemY + itemHeight - 3 + offsetY, accentColor);
            }

            context.fill(panelX + 6, itemY - 2 + offsetY, panelX + sidebarWidth - 4, itemY + itemHeight - 2 + offsetY,
                    sidebarButtonColors[i]);

            // Icon + Text
            String icon = i < CATEGORY_ICONS.length ? CATEGORY_ICONS[i] + " " : "";
            int textColor = (alphaInt << 24) | (isActive ? 0xFFFFFF : 0xAAAAAA);
            context.drawText(this.textRenderer, Text.literal(icon + sezione.nome), panelX + 12, itemY + 3 + offsetY, textColor, false);
        }

        // ========== Draw controls of active section with scrolling ==========
        Sezione active = sezioni.get(activeSectionIndex);
        int controlsStartX = panelX + sidebarWidth + 16;
        int controlsAreaTop = currentPanelY + 16;
        int controlsAreaBottom = currentPanelY + fullPanelHeight - 8;

        // Section title
        int sectionTitleColor = (alphaInt << 24) | 0x3399FF;
        context.drawText(this.textRenderer, Text.literal(active.nome), controlsStartX, controlsAreaTop, sectionTitleColor, false);

        int controlsStartY = controlsAreaTop + 18 - scrollOffset;
        int spacingY = 40;

        // Calculate max scroll
        int totalContentHeight = active.controlli.size() * spacingY;
        int visibleHeight = controlsAreaBottom - (controlsAreaTop + 18);
        maxScrollOffset = Math.max(0, totalContentHeight - visibleHeight);

        // Enable scissor for controls area
        context.enableScissor(controlsStartX, controlsAreaTop + 14, panelX + fullPanelWidth - 8, controlsAreaBottom);

        for (int i = 0; i < active.controlli.size(); i++) {
            Controllo c = active.controlli.get(i);
            int cy = controlsStartY + i * spacingY;
            c.setPosition(controlsStartX, cy);
            c.render(context, mouseX, mouseY, easedProgress);
        }

        context.disableScissor();

        // Scroll indicator
        if (maxScrollOffset > 0) {
            int scrollBarX = panelX + fullPanelWidth - 6;
            float scrollRatio = (float) scrollOffset / maxScrollOffset;
            int scrollBarHeight = Math.max(20, visibleHeight * visibleHeight / totalContentHeight);
            int scrollBarY = controlsAreaTop + 18 + (int) (scrollRatio * (visibleHeight - scrollBarHeight));

            int scrollTrackColor = (alphaInt << 24) | 0x222222;
            int scrollThumbColor = (alphaInt << 24) | 0x555555;
            context.fill(scrollBarX, controlsAreaTop + 18, scrollBarX + 3, controlsAreaBottom, scrollTrackColor);
            context.fill(scrollBarX, scrollBarY, scrollBarX + 3, scrollBarY + scrollBarHeight, scrollThumbColor);
        }
    }

    private static int lerpColor(int a, int b, float t) {
        int aA = (a >> 24) & 0xFF, aR = (a >> 16) & 0xFF, aG = (a >> 8) & 0xFF, aB = a & 0xFF;
        int bA = (b >> 24) & 0xFF, bR = (b >> 16) & 0xFF, bG = (b >> 8) & 0xFF, bB = b & 0xFF;
        return (((int) (aA + (bA - aA) * t)) << 24) | (((int) (aR + (bR - aR) * t)) << 16)
                | (((int) (aG + (bG - aG) * t)) << 8) | ((int) (aB + (bB - aB) * t));
    }

    // ========== INPUT HANDLING ==========
    @Override
    public boolean mouseClicked(Click click, boolean doubleClicked) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        // Check sidebar clicks
        int itemStartY = panelY + 28;
        int itemHeight = 22;
        if (mouseX >= panelX + 4 && mouseX <= panelX + sidebarWidth - 4
                && mouseY >= itemStartY - 2 && mouseY <= panelY + fullPanelHeight) {
            for (int i = 0; i < sezioni.size(); i++) {
                int itemY = itemStartY + i * (itemHeight + 4);
                if (mouseY >= itemY - 2 && mouseY <= itemY + itemHeight - 2) {
                    activeSectionIndex = i;
                    scrollOffset = 0; // Reset scroll on section change
                    return true;
                }
            }
        }

        // Forward to active controls
        Sezione active = sezioni.get(activeSectionIndex);
        for (Controllo c : active.controlli) {
            if (c.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        return super.mouseClicked(click, doubleClicked);
    }

    @Override
    public boolean mouseReleased(Click click) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        Sezione active = sezioni.get(activeSectionIndex);
        for (Controllo c : active.controlli) {
            c.mouseReleased(mouseX, mouseY, button);
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        Sezione active = sezioni.get(activeSectionIndex);
        for (Controllo c : active.controlli) {
            if (c.mouseDragged(mouseX, mouseY, button)) return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollOffset -= (int) (verticalAmount * 20);
        scrollOffset = Math.max(0, Math.min(maxScrollOffset, scrollOffset));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        int keyCode = keyInput.key();

        // Forward to keybind listener if active
        if (listeningButton != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                // Cancel listening
                listeningButton.cancelListening();
                listeningButton = null;
                return true;
            }
            listeningButton.captureKey(keyCode);
            listeningButton = null;
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.client != null) this.client.setScreen(null);
            return true;
        }
        return super.keyPressed(keyInput);
    }

    // ========== INNER CLASSES ==========
    private static class Sezione {
        final String nome;
        final List<Controllo> controlli = new ArrayList<>();
        Sezione(String nome) { this.nome = nome; }
        void addControllo(Controllo c) { controlli.add(c); }
    }

    private static abstract class Controllo {
        protected int x, y;
        public void setPosition(int x, int y) { this.x = x; this.y = y; }
        abstract void render(DrawContext context, int mouseX, int mouseY, float alpha);
        abstract boolean mouseClicked(double mouseX, double mouseY, int button);
        void mouseReleased(double mouseX, double mouseY, int button) {}
        boolean mouseDragged(double mouseX, double mouseY, int button) { return false; }
        protected boolean isInside(double mouseX, double mouseY, int width, int height) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    // ===== Label (read-only text) =====
    private static class CustomLabel extends Controllo {
        String text;
        CustomLabel(int x, int y, String text) {
            this.x = x; this.y = y; this.text = text;
        }
        @Override void render(DrawContext context, int mouseX, int mouseY, float alpha) {
            int alphaInt = (int) (alpha * 255) & 0xFF;
            int textColor = (alphaInt << 24) | 0x999999;
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(text), x, y + 4, textColor, false);
        }
        @Override boolean mouseClicked(double mouseX, double mouseY, int button) { return false; }
    }

    // ===== Slider =====
    private static class CustomSlider extends Controllo {
        int width, height; float value, min, max; boolean dragging = false;
        Consumer<Float> onChange; String label;
        public CustomSlider(int x, int y, int width, int height, float min, float max, float initialValue,
                            Consumer<Float> onChange, String label) {
            this.x = x; this.y = y; this.width = width; this.height = height; this.min = min; this.max = max;
            this.value = (initialValue - min) / (max - min); this.onChange = onChange; this.label = label;
        }
        @Override void render(DrawContext context, int mouseX, int mouseY, float alpha) {
            int alphaInt = (int) (alpha * 255) & 0xFF;
            boolean isHovered = isInside(mouseX, mouseY, width, height);
            int hoverOffset = (isHovered && !dragging) ? 1 : 0;
            int currentY = y + hoverOffset;

            int bgColor = (alphaInt << 24) | 0x4B6E6E;
            int fillColor = (alphaInt << 24) | 0x66CCFF;
            int borderColor = (alphaInt << 24) | (isHovered ? 0xC2FFFF : 0xA3E0E0);
            int textColor = (alphaInt << 24) | 0x66CCFF;
            int labelY = currentY - 14;
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(label), x, labelY, textColor, false);
            float valFloat = min + value * (max - min);
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(String.format("%.2f", valFloat)), x + width + 8, labelY, textColor, false);
            context.fill(x, currentY, x + width, currentY + height, bgColor);
            int filled = (int) (width * value);
            context.fill(x, currentY, x + filled, currentY + height, fillColor);
            context.fill(x, currentY, x + width, currentY + 1, borderColor);
            context.fill(x, currentY + height - 1, x + width, currentY + height, borderColor);
            context.fill(x, currentY, x + 1, currentY + height, borderColor);
            context.fill(x + width - 1, currentY, x + width, currentY + height, borderColor);
        }
        @Override boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isInside(mouseX, mouseY, width, height)) { dragging = true; updateValue(mouseX); return true; }
            return false;
        }
        @Override void mouseReleased(double mouseX, double mouseY, int button) { dragging = false; }
        @Override boolean mouseDragged(double mouseX, double mouseY, int button) {
            if (dragging) { updateValue(mouseX); return true; }
            return false;
        }
        private void updateValue(double mouseX) {
            float val = (float) ((mouseX - x) / (float) width);
            val = Math.min(1, Math.max(0, val));
            if (val != value) { value = val; if (onChange != null) onChange.accept(min + value * (max - min)); }
        }
    }

    // ===== Toggle Button =====
    private static class CustomToggleButton extends Controllo {
        int width, height; boolean state; Consumer<Boolean> onToggle; String label;
        int currentColor, targetColor;
        final int offColor = 0xFF777777; final int onColor = 0xFF3399FF;
        final float colorAnimationSpeed = 0.1f;
        public CustomToggleButton(int x, int y, int width, int height, boolean initialState,
                                  Consumer<Boolean> onToggle, String label) {
            this.x = x; this.y = y; this.width = width; this.height = height; this.state = initialState;
            this.onToggle = onToggle; this.label = label;
            this.currentColor = state ? onColor : offColor; this.targetColor = currentColor;
        }
        @Override void render(DrawContext context, int mouseX, int mouseY, float alpha) {
            int alphaInt = (int) (alpha * 255) & 0xFF;
            currentColor = lerpColor(currentColor, targetColor, colorAnimationSpeed);

            boolean isHovered = isInside(mouseX, mouseY, width, height);
            int hoverOffset = isHovered ? 1 : 0;
            int currentY = y + hoverOffset;

            int bgColor = (alphaInt << 24) | 0x1E1E1E;
            int borderColor = (alphaInt << 24) | (isHovered ? 0x777777 : 0x555555);
            int textColor = (alphaInt << 24) | 0x66CCFF;
            int labelWidth = MinecraftClient.getInstance().textRenderer.getWidth(Text.literal(label));
            int labelX = x + (width / 2) - (labelWidth / 2);
            int labelY = currentY - 14;
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(label), labelX, labelY, textColor, false);
            context.fill(x, currentY, x + width, currentY + height, bgColor);
            int fillWithAlpha = (alphaInt << 24) | (currentColor & 0x00FFFFFF);
            context.fill(x + 2, currentY + 2, x + width - 2, currentY + height - 2, fillWithAlpha);
            context.fill(x, currentY, x + width, currentY + 1, borderColor);
            context.fill(x, currentY + height - 1, x + width, currentY + height, borderColor);
            context.fill(x, currentY, x + 1, currentY + height, borderColor);
            context.fill(x + width - 1, currentY, x + width, currentY + height, borderColor);
            textColor = (alphaInt << 24) | 0xFFFFFFFF;
            String stateText = state ? "ON" : "OFF";
            int stateTextWidth = MinecraftClient.getInstance().textRenderer.getWidth(Text.literal(stateText));
            int textX = x + (width / 2) - (stateTextWidth / 2);
            int textY = currentY + (height / 2) - 4;
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(stateText), textX, textY, textColor, false);
        }
        @Override boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isInside(mouseX, mouseY, width, height)) {
                state = !state;
                targetColor = state ? onColor : offColor;
                if (onToggle != null) onToggle.accept(state);
                return true;
            }
            return false;
        }
    }

    // ===== Keybind Button =====
    private class CustomKeybindButton extends Controllo {
        int width, height;
        KeyBinding binding;
        boolean listening = false;
        String label;

        public CustomKeybindButton(int x, int y, int width, int height, KeyBinding binding) {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.binding = binding;
            this.label = KeyHandler.getLabel(binding.getId());
        }

        @Override void render(DrawContext context, int mouseX, int mouseY, float alpha) {
            int alphaInt = (int) (alpha * 255) & 0xFF;
            int textColor = (alphaInt << 24) | 0x66CCFF;
            int labelY = y - 14;
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(label), x, labelY, textColor, false);

            boolean isHovered = isInside(mouseX, mouseY, width, height);
            int bgColor = (alphaInt << 24) | (listening ? 0x2A1A3A : 0x1E1E1E);
            int borderColor = (alphaInt << 24) | (listening ? 0xFF9933 : (isHovered ? 0x3399FF : 0x555555));
            context.fill(x, y, x + width, y + height, bgColor);
            context.fill(x, y, x + width, y + 1, borderColor);
            context.fill(x, y + height - 1, x + width, y + height, borderColor);
            context.fill(x, y, x + 1, y + height, borderColor);
            context.fill(x + width - 1, y, x + width, y + height, borderColor);

            String keyName = listening ? "..." : getKeyName();
            int valueColor = (alphaInt << 24) | (listening ? 0xFF9933 : 0xFFFFFF);
            int textWidth = MinecraftClient.getInstance().textRenderer.getWidth(Text.literal(keyName));
            int textX = x + (width / 2) - (textWidth / 2);
            int textY2 = y + (height / 2) - 4;
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(keyName), textX, textY2, valueColor, false);
        }

        private String getKeyName() {
            return binding.getBoundKeyLocalizedText().getString();
        }

        @Override boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isInside(mouseX, mouseY, width, height)) {
                listening = true;
                listeningButton = this;
                return true;
            }
            return false;
        }

        void captureKey(int keyCode) {
            binding.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(keyCode));
            KeyBinding.updateKeysByCode();
            MacroConfig config = MacroConfig.get();
            config.keybindOverrides.put(binding.getId(), keyCode);
            MacroConfig.save();
            listening = false;
        }

        void cancelListening() {
            listening = false;
        }
    }

    // ===== Dropdown =====
    private static class CustomDropdown extends Controllo {
        int width, height;
        String[] options;
        int selectedIndex;
        boolean expanded = false;
        Consumer<String> onChange;
        String label;

        public CustomDropdown(int x, int y, int width, int height, String[] options, String currentValue,
                              Consumer<String> onChange, String label) {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.options = options; this.onChange = onChange; this.label = label;
            this.selectedIndex = 0;
            for (int i = 0; i < options.length; i++) {
                if (options[i].equalsIgnoreCase(currentValue)) {
                    selectedIndex = i;
                    break;
                }
            }
        }

        @Override void render(DrawContext context, int mouseX, int mouseY, float alpha) {
            int alphaInt = (int) (alpha * 255) & 0xFF;
            int textColor = (alphaInt << 24) | 0x66CCFF;
            int labelY = y - 14;
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(label), x, labelY, textColor, false);

            // Main box
            boolean isHovered = isInside(mouseX, mouseY, width, height);
            int bgColor = (alphaInt << 24) | 0x1E1E1E;
            int borderColor = (alphaInt << 24) | (isHovered ? 0x3399FF : 0x555555);
            context.fill(x, y, x + width, y + height, bgColor);
            context.fill(x, y, x + width, y + 1, borderColor);
            context.fill(x, y + height - 1, x + width, y + height, borderColor);
            context.fill(x, y, x + 1, y + height, borderColor);
            context.fill(x + width - 1, y, x + width, y + height, borderColor);

            int valueColor = (alphaInt << 24) | 0xFFFFFF;
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(options[selectedIndex] + " ▼"),
                    x + 6, y + (height / 2) - 4, valueColor, false);

            // Expanded options
            if (expanded) {
                for (int i = 0; i < options.length; i++) {
                    int optY = y + height + i * height;
                    boolean optHovered = mouseX >= x && mouseX <= x + width && mouseY >= optY && mouseY <= optY + height;
                    int optBg = (alphaInt << 24) | (optHovered ? 0x3399FF : 0x2A2A2A);
                    context.fill(x, optY, x + width, optY + height, optBg);
                    int optBorder = (alphaInt << 24) | 0x444444;
                    context.fill(x, optY + height - 1, x + width, optY + height, optBorder);
                    int optText = (alphaInt << 24) | (i == selectedIndex ? 0x3399FF : 0xCCCCCC);
                    context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(options[i]),
                            x + 6, optY + (height / 2) - 4, optText, false);
                }
            }
        }

        @Override boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (expanded) {
                // Check if clicking on an option
                for (int i = 0; i < options.length; i++) {
                    int optY = y + height + i * height;
                    if (mouseX >= x && mouseX <= x + width && mouseY >= optY && mouseY <= optY + height) {
                        selectedIndex = i;
                        expanded = false;
                        if (onChange != null) onChange.accept(options[selectedIndex]);
                        return true;
                    }
                }
                expanded = false;
                return true;
            }
            if (isInside(mouseX, mouseY, width, height)) {
                expanded = !expanded;
                return true;
            }
            return false;
        }
    }
}