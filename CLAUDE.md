# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

This repo (`mcmod`) is one of three sibling projects under the parent `Trident/` folder:
- `../TridentWebsite/` — nginx site for tridentclient.xyz (docroot `html/tridentclient/`: auth whitelist API, auto-update manifest, release jar downloads; see its README for deploy details)
- `../DiscordBot/` — Python Discord bot (Flask API consumed by the mod)

`release.ps1` in this repo's root builds the mod, stages the release into `../TridentWebsite`, and deploys to the live server over SSH (target in gitignored `deploy.config.json`).

A Fabric mod for Minecraft (client-side), built with Gradle + Fabric Loom. Java 21, targeting Minecraft `1.21.11` (see [gradle.properties](gradle.properties) for exact Minecraft/Yarn/Loader/Fabric API versions).

Mod ID: `trident`. Entrypoints (see [fabric.mod.json](src/main/resources/fabric.mod.json)):
- `com.trident.core.Trident` — main `ModInitializer`
- `com.trident.core.TridentLoader` — client `ClientModInitializer`
- `com.trident.network.DiscordIntegration` — client entrypoint

Mixins are configured in [trident.mixins.json](src/main/resources/trident.mixins.json) (package `com.trident.mixin`).

The project depends on a local jar, `libs/bazaarmod.jar`, referenced as `modImplementation files('libs/bazaarmod.jar')` in [build.gradle](build.gradle). Some code paths are gated on `FabricLoader.getInstance().isModLoaded("bazaarmod")` and only run when that mod is present at runtime.

## Commands

Use the Gradle wrapper (`gradlew`/`gradlew.bat`), not a system Gradle install.

```
./gradlew build          # compile, remap, and produce the mod jar (build/libs/)
./gradlew runClient       # launch a dev Minecraft client with the mod loaded
./gradlew runServer       # launch a dev Minecraft server with the mod loaded
```

There is no `src/test` source set — no automated test suite exists in this repo.

CI ([.github/workflows/build.yml](.github/workflows/build.yml)) runs `./gradlew build` on JDK 25 (Microsoft distribution) on every push/PR and uploads `build/libs/` as an artifact.

## Architecture

Source lives under `src/main/java/com/trident/`, organized by concern:

- `core/` — mod entrypoints and bootstrap (`Trident`, `TridentLoader`). `TridentLoader.onInitializeClient()` is the central hub: it registers client tick/render/connection event callbacks and wires together most other packages.
- `config/` — `MacroConfig`: a Gson-backed settings object persisted to `config/trident_macro_config.json`, loaded/saved via static `load()`/`save()`/`get()`.
- `events/` — Fabric event glue: keybindings (`KeyHandler`), camera/view control (`CameraManager`), world/server state detection (`WorldChecker`, `AutoJoinHypixel`), and account gating (`WhitelistChecker`).
- `modules/` — discrete automation features, each typically exposing a static `init()` plus tick-driven logic (`AutoFishModule`, `DwarvenMiningModule`, `ForagingModule`, `MobActionModule`, `Nuker`, `FailsafeManager`).
- `pathing/` — waypoint/route storage and movement (`RouteManager`, `RouteCommands` for in-game commands, `MovementController`, `PathVisualizer` for the world-rendered path overlay).
- `network/` — outbound integrations: `AutoUpdater` (self-update check against a remote endpoint), `BazaarPriceFetcher`, `DiscordIntegration`/`DiscordCommandHandler`.
- `gui/` — custom screens and HUD rendering (`CustomGUIScreen`, `CustomMainMenuScreen`, `SeaLumiesHUD`, `NumberFontRenderer`).
- `tracking/` — session stat tracking (`MacroTracker`, `SeaLumiesTracker`), with lifetime totals persisted through `MacroConfig`.
- `mixin/` — Mixin classes injected per [trident.mixins.json](src/main/resources/trident.mixins.json).
- `utils/` — small shared helpers (e.g. `BlockPosSerializer` for Gson (de)serialization of `BlockPos` in config/routes).

Config and runtime data under `run/` (the Fabric Loom dev environment's game directory) mirror what a real instance would have on disk: `run/config/trident_macro_config.json`, `run/config/seafucker_routes/routes.json`. These are dev-environment artifacts, not source.

Theming: the mod picks a random color theme (`purple`/`red`) at init (`Trident.getThemecolor()`), which selects between parallel asset sets under `src/main/resources/assets/trident/textures/{background,font,gui}/{purple,red}`.
