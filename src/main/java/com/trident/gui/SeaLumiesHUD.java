package com.trident.gui;

import com.trident.core.Trident;
import com.trident.tracking.SeaLumiesTracker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

public class SeaLumiesHUD {

    public static int lastPickleCount = 0;
    private static long macroStartTime = -1;
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
            macroStartTime = -1;
            lastPickleCount = 0;
        }
    }



    public static void render(DrawContext context, RenderTickCounter tickCounter)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden || !isActive) return;



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


}
