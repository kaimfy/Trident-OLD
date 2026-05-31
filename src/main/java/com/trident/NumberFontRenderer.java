package com.trident;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

public class NumberFontRenderer {

    private static final Map<Character, Identifier> textureMap = new HashMap<>();
    private static final Map<Character, Integer> charWidthMap = new HashMap<>();
    private static String theme = Trident.getThemecolor();

    static {
        for (char c = '0'; c <= '9'; c++) {
            textureMap.put(c, Identifier.of("trident", "textures/font/"+theme+"/" + c + ".png"));
            charWidthMap.put(c, 160);
        }
        textureMap.put('.', Identifier.of("trident", "textures/font/"+theme+"/dot.png"));
        textureMap.put(',', Identifier.of("trident", "textures/font/"+theme+"/comma.png"));
        textureMap.put('k', Identifier.of("trident", "textures/font/"+theme+"/k.png"));
        textureMap.put('M', Identifier.of("trident", "textures/font/"+theme+"/m.png"));
        textureMap.put('h', Identifier.of("trident", "textures/font/"+theme+"/h.png"));

        charWidthMap.put('.', 60);
        charWidthMap.put(',', 60);
        charWidthMap.put('k', 120);
        charWidthMap.put('M', 140);
        charWidthMap.put('h', 120);
    }

    private static final int DEFAULT_WIDTH = 160;
    private static final int DEFAULT_HEIGHT = 160;

    public static void drawNumber(DrawContext context, String number, int x, int y, float scale) {
        if (number == null || number.isEmpty()) return;

        float cursorX = x;

        for (char c : number.toCharArray()) {
            Identifier texture = textureMap.get(c);
            if (texture == null) continue;

            int charWidth = charWidthMap.getOrDefault(c, DEFAULT_WIDTH);
            float drawWidth = charWidth * scale;
            float drawHeight = DEFAULT_HEIGHT * scale;

            try {
                context.drawTexture(
                        RenderPipelines.GUI_TEXTURED,
                        texture,
                        (int) cursorX, y,
                        0, 0,
                        (int) drawWidth, (int) drawHeight,
                        DEFAULT_WIDTH, DEFAULT_HEIGHT,
                        DEFAULT_WIDTH, DEFAULT_HEIGHT
                );
            } catch (Exception e) {
                context.fill((int) cursorX, y, (int) (cursorX + drawWidth), (int) (y + drawHeight), 0x80FF0000);
                System.err.println("Error drawing texture for character '" + c + "': " + e.getMessage());
            }

            cursorX += drawWidth - (30 * scale);
        }
    }


    public static void drawNumberCompact(DrawContext context, long value, int x, int y, float scale) {
        String display;

        if (value >= 1_000_000) {
            if (value % 1_000_000 == 0) display = (value / 1_000_000) + "M";
            else display = String.format("%.1fM", value / 1_000_000.0).replace(".0", "");
        } else if (value >= 10_000) {
            if (value % 1_000 == 0) display = (value / 1_000) + "k";
            else display = String.format("%.1fk", value / 1_000.0).replace(".0", "");
        } else {
            display = String.valueOf(value);
        }

        drawNumber(context, display, x, y, scale);
    }


    public static void drawTimeNumber(DrawContext context, int value, boolean minutes, int x, int y, float scale) {
        int hours = minutes ? value / 60 : value;
        String display = hours + "h";
        drawNumber(context, display, x, y, scale);
    }
}