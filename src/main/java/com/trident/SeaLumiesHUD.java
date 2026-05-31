package com.trident;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.FontStorage;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.*;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;

public class SeaLumiesHUD {

    private static final Identifier HUD_TEXTURE = Identifier.of("trident", "textures/gui/hud.png");
    private static final Identifier TextFont = Identifier.ofVanilla("verdana");

    private static double totalValue = 0;
    private static int itemsCollected = 0;
    public static int lastPickleCount = 0;
    private static long macroStartTime = -1;
    private static long lastUpdateTime = 0;
    private static boolean isActive = false;
    private static boolean isManualStop = true;
    private static String theme = Trident.getThemecolor();

    public static void setActive(boolean active) {
        isActive = active;
        if (!active) resetTimer();
    }

    public static void setManualStop(boolean manual) {
        isManualStop = manual;
    }

    public static void startTimer() {
        if (macroStartTime == -1) {
            macroStartTime = System.currentTimeMillis();
        }
        isManualStop = false;
    }

    public static void resetTimer() {
        if (isManualStop) {
            macroStartTime = -1;
            totalValue = 0;
            itemsCollected = 0;
            lastPickleCount = 0;
        }
    }

    public static void updateValues(double newValue, int newItems) {
        totalValue = newValue;
        itemsCollected = newItems;
        lastUpdateTime = System.currentTimeMillis();
    }

    public static void setValue(double v) { totalValue = v; }
    public static void setItems(int i) { itemsCollected = i; }

    public static void render(DrawContext context, RenderTickCounter tickCounter)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden || !isActive) return;



        int x = 12;
        int y = 12;
        int width = 160;
        int height = 74;

        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                Identifier.of("trident", "textures/gui/"+theme+"/hudbg.png"),
                0, 0,
                0, 0,
                137, 150,
                137, 150
        );
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                Identifier.of("trident", "textures/gui/"+theme+"/hudfront.png"),
                0, 0,
                0, 0,
                137, 150,
                137, 150
        );

        NumberFontRenderer.drawNumberCompact(
                context,
                (long) SeaLumiesTracker.getCoinsPerHour(),
                70, 50-9, 0.05f
        );

        NumberFontRenderer.drawNumberCompact(
                context,
                (long) SeaLumiesTracker.getSessionCoins(),
                76, 80-13, 0.05f
        );

        NumberFontRenderer.drawNumberCompact(
                context,
                SeaLumiesTracker.getLifetimeCoins(),
                76, 110-16, 0.05f
        );

        NumberFontRenderer.drawTimeNumber(
                context,
                SeaLumiesTracker.getLifetimeMacroMinutes() / 1000,
                false,
                67, 140-19, 0.05f
        );



    }


    private static void draw(TextRenderer font, DrawContext context, String text, int x, int y, int color) {
        context.drawText(font, Text.literal(text), x, y, color, false);
    }

    private static String formatValue(double value) {
        if (value >= 1_000_000_000) return String.format("%.2fB", value / 1_000_000_000);
        if (value >= 1_000_000) return String.format("%.2fM", value / 1_000_000);
        if (value >= 1_000) return String.format("%.1fK", value / 1_000);
        return String.valueOf((int)value);
    }
}
