package com.trident;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import com.trident.TridentLoader;

public class CustomMainMenuScreen extends Screen {


    // For your texture
    private final java.util.Map<ButtonWidget, Float> hoverProgress = new java.util.HashMap<>();
    private static String theme = Trident.getThemecolor();
    private static final Identifier LOGO = Identifier.of("trident", "textures/gui/"+theme+"/icon.png");

    private static final Identifier QUITING = Identifier.of("trident", "textures/gui/"+theme+"/quit.png");
    private static final Identifier OPTIONBUTTON = Identifier.of("trident", "textures/gui/"+theme+"/options.png");
    private static final Identifier HYPIXEL = Identifier.of("trident", "textures/gui/"+theme+"/hypixel.png");
    private static final Identifier SINGLE = Identifier.of("trident", "textures/gui/"+theme+"/singleplayer.png");
    private static final Identifier MULTI = Identifier.of("trident", "textures/gui/"+theme+"/multi.png");

    public CustomMainMenuScreen() {
        super(Text.literal("Custom Menu"));
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int centerY = height / 2;

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Singleplayer"),
                b -> client.setScreen(new net.minecraft.client.gui.screen.world.SelectWorldScreen(this))
        ).dimensions(centerX - 59, centerY - 40, 58, 30).build());
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Multiplayer"),
                b -> client.setScreen(new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(this))
        ).dimensions(centerX + 2, centerY - 40, 58, 30).build());

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Hypixel"),
                b -> {
                    try {
                        ServerAddress address = new ServerAddress("mc.hypixel.net", 25565);
                        ServerInfo info = new ServerInfo("Hypixel", "mc.hypixel.net", ServerInfo.ServerType.OTHER);

                        // Set up proper server info
                        info.setResourcePackPolicy(ServerInfo.ResourcePackPolicy.ENABLED);

                        ConnectScreen.connect(
                                new MultiplayerScreen(null),
                                client,
                                address,
                                info,
                                false,
                                null // Use default cookie storage
                        );

                    } catch (Exception e) {
                        System.err.println("Failed to connect to Hypixel: " + e.getMessage());
                    }
                }
        ).dimensions(centerX - 60, centerY - 5, 120, 30).build());

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Options"),
                b -> client.setScreen(new net.minecraft.client.gui.screen.option.OptionsScreen(this, client.options))
        ).dimensions(centerX + 10, centerY + 40, 30, 30).build());

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Quit"),
                b -> MinecraftClient.getInstance().scheduleStop()
        ).dimensions(centerX - 40, centerY + 40, 30, 30).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Draw background
        super.render(context, mouseX, mouseY, 0);
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                Identifier.of("trident", "textures/background/"+ theme +"/"+ TridentLoader.getFrame()+".png"),
                0, 0,
                0, 0,
                1901, 1022,
                960, 520
        );


        // Draw logo
        int logoWidth = 128;
        int logoHeight = 128;
        int posX = width / 2 - logoWidth / 2;
        int posY = height / 2 - 200;
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                LOGO,
                posX, posY,
                0, 0,
                logoWidth, logoHeight,
                128, 128
        );
        // Draw the actual buttons (text, hover effect)



        for (var widget : this.children()) {
            if (widget instanceof ButtonWidget button) {
                int bx = button.getX();
                int by = button.getY();
                int bw = button.getWidth();
                int bh = button.getHeight();

                // Determine if hovered
                boolean hovered = mouseX >= bx && mouseX <= bx + bw &&
                        mouseY >= by && mouseY <= by + bh;

                // Offset downward if hovered
                int offsetY = hovered ? 2 : 0; // move 2 pixels down on hover

                // Determine texture based on text
                Identifier tex = switch (button.getMessage().getString()) {
                    case "Hypixel" -> HYPIXEL;
                    case "Options" -> OPTIONBUTTON;
                    case "Quit" -> QUITING;
                    case "Singleplayer" -> SINGLE;
                    case "Multiplayer" -> MULTI;
                    default -> null;
                };

                if (tex != null) {
                    context.drawTexture(
                            RenderPipelines.GUI_TEXTURED,
                            tex,
                            bx, by + offsetY,
                            0, 0,
                            bw, bh,
                            bw, bh
                    );
                }
            }


    }








    }



}
