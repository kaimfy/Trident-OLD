<#
    Installs (or removes, with -Uninstall) the Trident watchdog as a Windows scheduled task
    that starts hidden at logon and restarts itself if it ever dies.

    Run from an elevated PowerShell:  .\install-watchdog.ps1
#>
[CmdletBinding()]
param(
    [switch]$Uninstall
)

$TaskName = 'TridentWatchdog'

if ($Uninstall) {
    try { Stop-ScheduledTask -TaskName $TaskName -ErrorAction Stop } catch {}
    try {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop
        Write-Host "Removed scheduled task '$TaskName'."
    } catch {
        Write-Host "Scheduled task '$TaskName' was not installed."
    }
    return
}

$configPath = Join-Path $PSScriptRoot 'watchdog.config.json'
if (-not (Test-Path $configPath)) {
    Copy-Item (Join-Path $PSScriptRoot 'watchdog.config.example.json') $configPath
    Write-Host "Created $configPath"
    Write-Host '>>> EDIT IT NOW (gameDir, launchExe/launchArgs, agentId, agentSecret) then re-run this installer. <<<'
    return
}

$scriptPath = Join-Path $PSScriptRoot 'trident-watchdog.ps1'
$action   = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$scriptPath`""
$trigger  = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Write-Host "Scheduled task '$TaskName' installed and started."
Write-Host "Log file: $(Join-Path $PSScriptRoot 'watchdog.log')"
