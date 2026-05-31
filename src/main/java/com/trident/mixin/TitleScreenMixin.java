package com.trident.mixin;

import com.trident.gui.CustomMainMenuScreen;

import com.trident.gui.CustomMainMenuScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void replace(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new CustomMainMenuScreen());
        ci.cancel();
    }
}
