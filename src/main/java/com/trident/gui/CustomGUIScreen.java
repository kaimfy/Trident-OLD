package com.trident.gui;

import com.trident.config.MacroConfig;
import com.trident.core.Trident;
import com.trident.core.TridentLoader;
import com.trident.events.CameraManager;
import com.trident.modules.Nuker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.client.gl.RenderPipelines;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class CustomGUIScreen extends Screen {
    private int fullPanelWidth, fullPanelHeight;
    private float animationProgress = 0f;
    private final float animationSpeed = 0.06f;
    private int panelX, panelY;

    private final int sidebarWidth = 120;
    private int activeSectionIndex = 0;

    private final List<Sezione> sezioni = new ArrayList<>();
    private final int[] sidebarButtonColors = new int[20];

    // Image identifiers - place your images in assets/trident/textures/gui/

    private static final Identifier LOGO_IMAGE = Identifier.of("trident", "textures/gui/menu/logo.png");
    private static final Identifier DECORATION_IMAGE = Identifier.of("trident", "textures/gui/menu/decoration.png");

    // Section icons
    private static final Identifier ICON_ESP = Identifier.of("trident", "textures/gui/menu/icon_esp.png");
    private static final Identifier ICON_PATH = Identifier.of("trident", "textures/gui/menu/icon_path.png");
    private static final Identifier ICON_SETTINGS = Identifier.of("trident", "textures/gui/menu/icon_settings.png");

    public CustomGUIScreen() {
        super(Text.literal("Custom GUI"));
    }

    @Override
    protected void init() {
        super.init();
        sezioni.clear();

        fullPanelWidth = (int) (this.width * 0.6f);
        fullPanelHeight = (int) (this.height * 0.7f);
        panelX = (this.width - fullPanelWidth) / 2;
        panelY = (this.height - fullPanelHeight) / 2;

        MacroConfig.load();
        MacroConfig config = MacroConfig.get();

        Sezione esp = new Sezione("ESP");
        esp.addControllo(new CustomToggleButton(0, 0, 100, 20, config.espEnabled, val -> {
            config.espEnabled = val;
            MacroConfig.save();
            TridentLoader.getInstance().ESPToggle(val);
        }, "Toggle ESP"));

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

        Sezione settings = new Sezione("Settings");
        settings.addControllo(new CustomToggleButton(0, 0, 100, 20, config.nukerToggle, val -> {
            config.nukerToggle = val;
            MacroConfig.save();
            Nuker.setToggle(val);
        }, "Nuker Toggle"));

        sezioni.add(esp);
        sezioni.add(path);
        sezioni.add(settings);

        applicaConfigurazioni();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        if (animationProgress < 1f) {
            animationProgress = Math.min(1f, animationProgress + animationSpeed);
        }

        int alphaInt = (int) (animationProgress * 255) & 0xFF;

        // ========== Draw panel background image (exactly where the menu panel was) ==========
        // This REPLACES the solid color background
        context.fill(
                panelX, panelY,
                panelX + fullPanelWidth, panelY + fullPanelHeight,
                0x99000000  // ARGB: ~60% opacity black
        );
        // ========== REMOVED: No more solid color fill for the panel ==========
        // The panel background is now just the image

        // Draw sidebar background (semi-transparent over the image)
        int sidebarBgColor = (200 << 24) | 0x2A2A2A; // Semi-transparent dark background for sidebar
        context.fill(panelX, panelY, panelX + sidebarWidth, panelY + fullPanelHeight, sidebarBgColor);

        // Title: Trident
        int tridentColor = (alphaInt << 24) | 0x3399ff;
        int tridentX = panelX + 10;
        int tridentY = panelY + 6;
        context.drawText(this.textRenderer, Text.literal("Trident"), tridentX, tridentY, tridentColor, false);

        // Draw sidebar items (sections) below the title
        int itemStartY = panelY + 26;
        int itemHeight = 24;
        for (int i = 0; i < sezioni.size(); i++) {
            Sezione sezione = sezioni.get(i);
            int itemY = itemStartY + i * (itemHeight + 6);
            int baseAlpha = alphaInt;

            boolean isActive = (i == activeSectionIndex);

            int targetHighlightColor = isActive ? 0x3399FF : 0x1E1E1E;
            int currentColor = sidebarButtonColors[i];
            sidebarButtonColors[i] = lerpColor(currentColor, (baseAlpha << 24) | targetHighlightColor, 0.1f);

            context.fill(panelX + 4, itemY - 2, panelX + sidebarWidth - 4, itemY + itemHeight - 2, sidebarButtonColors[i]);

            // ========== Draw section icons ==========
            // Uncomment to add icons next to section names
            /*
            Identifier iconId = switch(sezione.nome) {
                case "ESP" -> ICON_ESP;
                case "Path" -> ICON_PATH;
                case "Settings" -> ICON_SETTINGS;
                default -> null;
            };
            if (iconId != null) {
                context.drawTexture(RenderPipelines.GUI_TEXTURED, iconId, panelX + 10, itemY - 2, 0, 0, 16, 16, 16, 16, -1);
            }
            */

            int textColor = (baseAlpha << 24) | (isActive ? 0xFFFFFF : 0xAAAAAA);
            // Adjust text X position if using icons (change 10 to 30)
            context.drawText(this.textRenderer, sezione.nome, panelX + 10, itemY, textColor, false);
        }

        // Draw controls of active section
        Sezione active = sezioni.get(activeSectionIndex);
        int controlsStartX = panelX + sidebarWidth + 20;
        int controlsStartY = panelY + 20;

        int spacingY = 40;
        for (int i = 0; i < active.controlli.size(); i++) {
            Controllo c = active.controlli.get(i);
            c.setPosition(controlsStartX, controlsStartY + i * spacingY);
            c.render(context, mouseX, mouseY, animationProgress);
        }
    }

    private static int lerpColor(int a, int b, float t) {
        int aA = (a >> 24) & 0xFF;
        int aR = (a >> 16) & 0xFF;
        int aG = (a >> 8) & 0xFF;
        int aB = a & 0xFF;

        int bA = (b >> 24) & 0xFF;
        int bR = (b >> 16) & 0xFF;
        int bG = (b >> 8) & 0xFF;
        int bB = b & 0xFF;

        int cA = (int) (aA + (bA - aA) * t);
        int cR = (int) (aR + (bR - aR) * t);
        int cG = (int) (aG + (bG - aG) * t);
        int cB = (int) (aB + (bB - aB) * t);

        return (cA << 24) | (cR << 16) | (cG << 8) | cB;
    }

    public void applicaConfigurazioni() {
        for (Sezione sezione : sezioni) {
            for (Controllo c : sezione.controlli) {
                if (c instanceof CustomSlider slider && slider.onChange != null) {
                    float actualValue = slider.min + slider.value * (slider.max - slider.min);
                    slider.onChange.accept(actualValue);
                } else if (c instanceof CustomToggleButton toggle && toggle.onToggle != null) {
                    toggle.onToggle.accept(toggle.state);
                }
            }
        }
    }

    // ========== INPUT HANDLING FOR 1.21.11 ==========
    @Override
    public boolean mouseClicked(Click click, boolean doubleClicked) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        // Check sidebar clicks
        if (mouseX >= panelX + 4 && mouseX <= panelX + sidebarWidth - 4 &&
                mouseY >= panelY + 26 - 2 && mouseY <= panelY + fullPanelHeight) {
            int itemHeight = 24;
            int itemStartY = panelY + 26;
            for (int i = 0; i < sezioni.size(); i++) {
                int itemY = itemStartY + i * (itemHeight + 6);
                if (mouseY >= itemY - 2 && mouseY <= itemY + itemHeight - 2) {
                    activeSectionIndex = i;
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
    public boolean keyPressed(KeyInput keyInput) {
        int keyCode = keyInput.key();
        int scanCode = keyInput.scancode();
        int modifiers = keyInput.modifiers();

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
            int bgColor = (alphaInt << 24) | 0x4B6E6E;
            int fillColor = (alphaInt << 24) | 0x66CCFF;
            int borderColor = (alphaInt << 24) | 0xA3E0E0;
            int textColor = (alphaInt << 24) | 0x66CCFF;
            int labelY = y - 14;
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(label), x, labelY, textColor, false);
            float valFloat = min + value * (max - min);
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(String.format("%.2f", valFloat)), x + width + 8, labelY, textColor, false);
            context.fill(x, y, x + width, y + height, bgColor);
            int filled = (int) (width * value);
            context.fill(x, y, x + filled, y + height, fillColor);
            context.fill(x, y, x + width, y + 1, borderColor);
            context.fill(x, y + height - 1, x + width, y + height, borderColor);
            context.fill(x, y, x + 1, y + height, borderColor);
            context.fill(x + width - 1, y, x + width, y + height, borderColor);
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
            int bgColor = (alphaInt << 24) | 0x1E1E1E;
            int borderColor = (alphaInt << 24) | 0x555555;
            int textColor = (alphaInt << 24) | 0x66CCFF;
            int labelWidth = MinecraftClient.getInstance().textRenderer.getWidth(Text.literal(label));
            int labelX = x + (width / 2) - (labelWidth / 2);
            int labelY = y - 14;
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(label), labelX, labelY, textColor, false);
            context.fill(x, y, x + width, y + height, bgColor);
            int fillWithAlpha = (alphaInt << 24) | (currentColor & 0x00FFFFFF);
            context.fill(x + 2, y + 2, x + width - 2, y + height - 2, fillWithAlpha);
            context.fill(x, y, x + width, y + 1, borderColor);
            context.fill(x, y + height - 1, x + width, y + height, borderColor);
            context.fill(x, y, x + 1, y + height, borderColor);
            context.fill(x + width - 1, y, x + width, y + height, borderColor);
            textColor = (alphaInt << 24) | 0xFFFFFFFF;
            String stateText = state ? "ON" : "OFF";
            int stateTextWidth = MinecraftClient.getInstance().textRenderer.getWidth(Text.literal(stateText));
            int textX = x + (width / 2) - (stateTextWidth / 2);
            int textY = y + (height / 2) - 4;
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
}