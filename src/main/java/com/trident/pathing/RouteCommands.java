package com.trident.pathing;

import com.trident.events.CameraManager;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

import java.util.List;

public class RouteCommands {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Save command
            var saveCommand = ClientCommandManager.literal("save")
                    .then(ClientCommandManager.argument("name", StringArgumentType.string())
                            .executes(context -> {
                                String name = StringArgumentType.getString(context, "name");
                                if (name.isEmpty()) {
                                    context.getSource().sendFeedback(Text.literal("§3[§bSeaFucker§3] §7Please provide a route name"));
                                    return 0;
                                }
                                CameraManager.saveCurrentRoute(name);
                                return Command.SINGLE_SUCCESS;
                            }));

            // Load command
            var loadCommand = ClientCommandManager.literal("load")
                    .then(ClientCommandManager.argument("name", StringArgumentType.string())
                            .executes(context -> {
                                String name = StringArgumentType.getString(context, "name");
                                CameraManager.loadPresetRoute(name);
                                return Command.SINGLE_SUCCESS;
                            }));


            // Delete command
            var deleteCommand = ClientCommandManager.literal("delete")
                    .then(ClientCommandManager.argument("name", StringArgumentType.string())
                            .executes(context -> {
                                String name = StringArgumentType.getString(context, "name");
                                CameraManager.deletePresetRoute(name);
                                return Command.SINGLE_SUCCESS;
                            }));
            var editRoute = ClientCommandManager.literal("edit")
                    .then(ClientCommandManager.argument("point", IntegerArgumentType.integer())
                            .executes(context -> {
                                int point = IntegerArgumentType.getInteger(context, "point");
                                CameraManager.editPoint(point);
                                return Command.SINGLE_SUCCESS;
                            }));
            var deleteRoute = ClientCommandManager.literal("remove")
                    .then(ClientCommandManager.argument("point", IntegerArgumentType.integer())
                            .executes(context -> {
                                int point = IntegerArgumentType.getInteger(context, "point");
                                CameraManager.removePoint(point);
                                return Command.SINGLE_SUCCESS;
                            }));


            // List command
            var listCommand = ClientCommandManager.literal("list")
                    .executes(context -> {
                        List<String> names = CameraManager.getPresetNames();
                        if (names.isEmpty()) {
                            context.getSource().sendFeedback(Text.literal("§3[§bSeaFucker§3] §7No saved routes"));
                        } else {
                            context.getSource().sendFeedback(Text.literal("§3[§bSeaFucker§3] §7Saved routes:"));
                            for (String name : names) {
                                context.getSource().sendFeedback(Text.literal("§7- " + name));
                            }
                        }
                        return Command.SINGLE_SUCCESS;
                    });

            // Register all commands under /route
            dispatcher.register(
                    ClientCommandManager.literal("trident")
                            .then(saveCommand)
                            .then(loadCommand)
                            .then(deleteCommand)
                            .then(listCommand)
                            .then(editRoute)
                            .then(deleteRoute)
            );

        });
    }
}