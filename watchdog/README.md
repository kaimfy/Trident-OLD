# Trident Watchdog

Keeps the game alive: relaunches Minecraft if it closes or crashes while a macro is
running, and lets you start/stop/restart the game remotely from Discord (`/pc`).

## How it works

```
Discord /pc start ──► trident_bot.py (Flask queue) ◄── poll every 15s ── watchdog (this PC)
                                                                            │
mod writes config/trident_restart/{runtime,state}.json ◄────────────────── watches PID,
mod resumes macro after relaunch                                            relaunches game
```

- The **mod** writes `runtime.json` (its PID) at startup and keeps `state.json` updated
  (auto-restart toggle + which macro is running).
- The **watchdog** watches that PID. When the game dies, it relaunches it **only if** the
  in-game *Auto Restart (Watchdog)* toggle (General section of the GUI) was on **and** a
  macro was running. A restart budget (default 4 per 30 min) prevents crash loops.
- After relaunch the mod sees the stale `state.json`, auto-joins Hypixel and resumes the
  macro (Sea Lumies rides its normal join sequence; other modules are re-enabled once
  back in the right SkyBlock zone).
- Remote `STOP` disarms auto-restart until the next `START` (or a manual game launch).

## Setup (one time, on the gaming PC)

1. Install [Prism Launcher](https://prismlauncher.org/), import/create the instance,
   log in to your Microsoft account with "stay signed in". Test that
   `prismlauncher.exe --launch <InstanceName>` starts the game.
2. Copy `watchdog.config.example.json` → `watchdog.config.json` and edit:
   - `gameDir` — the instance's `.minecraft` folder (must contain `config/trident_restart` after the mod has run once)
   - `launchExe` / `launchArgs` — your Prism path and instance name
   - `agentId` / `agentSecret` — must match an entry in `PC_AGENTS` in `../DiscordBot/trident_bot.py`
3. Run `.\install-watchdog.ps1` — registers a hidden scheduled task at logon and starts it.
   (`.\install-watchdog.ps1 -Uninstall` removes it.)

Check `watchdog.log` next to the script to see what it's doing. You can also run
`.\trident-watchdog.ps1` directly in a console for a foreground test.

## Discord commands

`/pc action:start|stop|restart|status` — queued by the bot, picked up by the watchdog
within its poll interval. Events (launched, restarted, crashed, budget exceeded, ...)
are posted back to your `mc-<name>` channel.

## Notes

- The Microsoft session lives in Prism; refresh tokens last ~90 days, so unattended
  relaunches keep working as long as you launch/play occasionally.
- Powering on a fully shut-down PC is out of scope here (needs Wake-on-LAN from another
  device on the LAN).
- `watchdog.config.json` contains the agent secret — it is gitignored, don't commit it.
