<#
    Trident watchdog — keeps the game alive and remotely controllable.

    Runs on the gaming PC (installed as a scheduled task by install-watchdog.ps1) and:
      1. Watches the game process via <gameDir>\config\trident_restart\runtime.json,
         which the mod writes at startup (its PID + launch command line).
      2. When the game exits/crashes, reads state.json (also written by the mod): if the
         auto-restart toggle was on AND a macro was running, relaunches the game. The mod
         then resumes the macro on its own after boot.
      3. Polls the Discord bot's API for remote commands (START / STOP / RESTART / STATUS)
         so the game can be started from Discord even when it is not running.

    Requires Windows PowerShell 5.1+. Configure via watchdog.config.json (see the example file).
#>
[CmdletBinding()]
param(
    # Default resolved in the body: $PSScriptRoot is empty inside param() defaults in
    # some Windows PowerShell 5.1 invocation contexts (e.g. scheduled tasks).
    [string]$ConfigPath
)

$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $ConfigPath) { $ConfigPath = Join-Path $ScriptDir 'watchdog.config.json' }

if (-not (Test-Path $ConfigPath)) {
    throw "Config not found: $ConfigPath  (copy watchdog.config.example.json to watchdog.config.json and edit it)"
}
$cfg = Get-Content $ConfigPath -Raw | ConvertFrom-Json

$LogFile     = Join-Path $ScriptDir 'watchdog.log'
$RestartDir  = Join-Path $cfg.gameDir 'config\trident_restart'
$StateFile   = Join-Path $RestartDir 'state.json'
$RuntimeFile = Join-Path $RestartDir 'runtime.json'

function Write-Log([string]$msg) {
    $line = '{0:yyyy-MM-dd HH:mm:ss}  {1}' -f (Get-Date), $msg
    Write-Host $line
    try {
        if ((Test-Path $LogFile) -and ((Get-Item $LogFile).Length -gt 1MB)) {
            $tail = Get-Content $LogFile -Tail 200
            Set-Content $LogFile $tail
        }
        Add-Content $LogFile $line
    } catch {}
}

function Read-JsonFile([string]$path) {
    try {
        if (Test-Path $path) { return (Get-Content $path -Raw | ConvertFrom-Json) }
    } catch {}
    return $null
}

# The game process, or $null. Identified by the PID the mod wrote at startup; the name
# check guards against Windows reusing the PID for something else after a crash.
function Get-GameProcess {
    $rt = Read-JsonFile $RuntimeFile
    if ($null -eq $rt -or $null -eq $rt.pid) { return $null }
    try {
        $p = Get-Process -Id ([int]$rt.pid) -ErrorAction Stop
        if ($p.ProcessName -match 'java') { return $p }
    } catch {}
    return $null
}

function Invoke-Api([string]$path, [hashtable]$body) {
    $body['agent']  = $cfg.agentId
    $body['secret'] = $cfg.agentSecret
    try {
        return Invoke-RestMethod -Uri ($cfg.apiBase + $path) -Method Post `
            -ContentType 'application/json' -Body ($body | ConvertTo-Json) -TimeoutSec 10
    } catch {
        Write-Log "API $path failed: $($_.Exception.Message)"
        return $null
    }
}

function Send-AgentEvent([string]$eventName, [string]$detail) {
    [void](Invoke-Api '/agent/event' @{ event = $eventName; detail = $detail })
}

function Start-Game {
    Write-Log "Launching game: $($cfg.launchExe) $($cfg.launchArgs -join ' ')"
    try {
        if ($cfg.launchArgs -and @($cfg.launchArgs).Count -gt 0) {
            Start-Process -FilePath $cfg.launchExe -ArgumentList @($cfg.launchArgs)
        } else {
            Start-Process -FilePath $cfg.launchExe
        }
        $script:launchAt = Get-Date
        Send-AgentEvent 'launched' 'Game launch triggered'
    } catch {
        Write-Log "Launch failed: $($_.Exception.Message)"
        Send-AgentEvent 'launch_failed' "Could not start launcher: $($_.Exception.Message)"
    }
}

function Stop-Game {
    $p = Get-GameProcess
    if ($p) {
        Write-Log "Killing game process $($p.Id)"
        try { Stop-Process -Id $p.Id -Force -Confirm:$false -ErrorAction Stop }
        catch { Write-Log "Kill failed: $($_.Exception.Message)" }
    }
}

# ===== main loop =====

$armed = $true            # $false after a remote STOP: no auto-restart until START or a manual launch
$wasRunning = $false
$launchAt = $null         # set while we've triggered a launch and are waiting for the game to appear
$restartTimes = New-Object 'System.Collections.Generic.List[datetime]'
$lastPoll = [datetime]::MinValue

Write-Log '=== Trident watchdog started ==='
Send-AgentEvent 'watchdog_online' "Watchdog started on $env:COMPUTERNAME"

while ($true) {

    # ---- remote commands ----
    if (((Get-Date) - $lastPoll).TotalSeconds -ge [double]$cfg.pollIntervalSeconds) {
        $lastPoll = Get-Date
        $resp = Invoke-Api '/agent/poll' @{}
        if ($resp -and $resp.ok -and $resp.commands) {
            foreach ($c in @($resp.commands)) {
                $cmd = ('' + $c.command).ToUpper()
                Write-Log "Remote command: $cmd"
                switch ($cmd) {
                    'START' {
                        $armed = $true
                        if ((Get-GameProcess) -or $launchAt) {
                            Send-AgentEvent 'info' 'Game is already running/starting'
                        } else {
                            Start-Game
                        }
                    }
                    'STOP' {
                        $armed = $false
                        $launchAt = $null
                        if (Get-GameProcess) {
                            Stop-Game
                            Send-AgentEvent 'stopped' 'Game stopped remotely; auto-restart disarmed until next START'
                        } else {
                            Send-AgentEvent 'info' 'Game not running; auto-restart disarmed'
                        }
                    }
                    'RESTART' {
                        $armed = $true
                        Stop-Game
                        $deadline = (Get-Date).AddSeconds(30)
                        while ((Get-GameProcess) -and ((Get-Date) -lt $deadline)) { Start-Sleep -Seconds 1 }
                        Start-Game
                    }
                    'STATUS' {
                        $p = Get-GameProcess
                        $st = Read-JsonFile $StateFile
                        $running = 'not running'
                        if ($p) { $running = "running (pid $($p.Id))" }
                        elseif ($launchAt) { $running = 'starting...' }
                        $macro = 'macro: off'
                        if ($st -and $st.macroActive) { $macro = "macro: $($st.module)" }
                        $arm = 'disarmed'
                        if ($armed) { $arm = 'armed' }
                        Send-AgentEvent 'status' "Game $running | $macro | auto-restart $arm"
                    }
                }
            }
        }
    }

    # ---- game lifecycle ----
    $proc = Get-GameProcess
    if ($proc) {
        if (-not $wasRunning) {
            Write-Log "Game running (pid $($proc.Id))"
            $armed = $true   # a live game (ours or manually launched) re-arms auto-restart
        }
        $wasRunning = $true
        $launchAt = $null
    }
    else {
        if ($launchAt) {
            # We triggered a launch; the launcher/auth may take a while before the mod
            # writes a fresh runtime.json. Only give up after the timeout.
            if (((Get-Date) - $launchAt).TotalSeconds -gt [double]$cfg.launchTimeoutSeconds) {
                Write-Log 'Launch timed out - game never appeared'
                Send-AgentEvent 'launch_failed' "Game did not start within $($cfg.launchTimeoutSeconds)s"
                $launchAt = $null
            }
        }
        elseif ($wasRunning) {
            $wasRunning = $false
            Write-Log 'Game exited'
            $st = Read-JsonFile $StateFile
            $shouldRestart = $armed -and $st -and $st.autoRestart -and $st.macroActive
            if ($shouldRestart) {
                $cutoff = (Get-Date).AddMinutes(-[double]$cfg.restartWindowMinutes)
                while ($restartTimes.Count -gt 0 -and $restartTimes[0] -lt $cutoff) { $restartTimes.RemoveAt(0) }
                if ($restartTimes.Count -ge [int]$cfg.maxRestarts) {
                    Write-Log "Restart budget exhausted ($($cfg.maxRestarts) in $($cfg.restartWindowMinutes)m) - not restarting"
                    Send-AgentEvent 'restart_budget_exceeded' "Game keeps dying: restarted $($cfg.maxRestarts) times in $($cfg.restartWindowMinutes)m. Send START to try again."
                }
                else {
                    $restartTimes.Add((Get-Date))
                    Write-Log "Game exited mid-macro (module: $($st.module)) - restarting in $($cfg.restartDelaySeconds)s"
                    Send-AgentEvent 'restarting' "Game closed/crashed mid-macro ($($st.module)); relaunching"
                    Start-Sleep -Seconds ([int]$cfg.restartDelaySeconds)
                    Start-Game
                }
            }
            else {
                $why = 'no macro was running'
                if (-not $armed) { $why = 'disarmed by remote STOP' }
                if ($null -eq $st) { $why = 'no state file' }
                if ($st -and -not $st.autoRestart) { $why = 'auto-restart toggle off' }
                Write-Log "Game exited - not restarting ($why)"
                Send-AgentEvent 'exited' "Game closed ($why)"
            }
        }
    }

    Start-Sleep -Seconds 5
}
