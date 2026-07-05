# Builds the mod, stages the release into TridentWebsite, and — if deploy.config.json
# exists next to this script — deploys it to the live server over SSH (e.g. via Tailscale).
#
# Usage: .\release.ps1 [-Changelog "what changed"] [-NoDeploy]
#
# deploy.config.json (gitignored; copy deploy.config.example.json and fill in):
#   host          Tailscale MagicDNS name or IP of the web server / LXC
#   user          SSH user with write access to the docroot
#   docroot       absolute path on the server containing auth/ (e.g. /var/www/tridentclient)
#   identityFile  optional path to an SSH private key ("" to use defaults/agent)
#
# Without deploy.config.json (or with -NoDeploy) it stages locally and prints
# the manual sync instructions instead.
param(
    [string]$Changelog = "",
    [switch]$NoDeploy
)
$ErrorActionPreference = 'Stop'

# ---- Build ----
& .\gradlew.bat build
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$version = (Select-String -Path gradle.properties -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()
$jar = "build\libs\trident-$version.jar"
if (-not (Test-Path $jar)) { throw "Expected jar not found: $jar" }

$hash = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower()

# ---- Stage into TridentWebsite (sibling project: Trident/TridentWebsite) ----
$site = Join-Path $PSScriptRoot "..\TridentWebsite\html\tridentclient"
if (-not (Test-Path (Join-Path $site "api"))) { throw "Website docroot not found at $site" }

New-Item -ItemType Directory -Force (Join-Path $site "downloads") | Out-Null
$stagedJar = Join-Path $site "downloads\trident-$version.jar"
Copy-Item $jar $stagedJar -Force

$escapedChangelog = $Changelog.Replace('\', '\\').Replace('"', '\"')
$json = @"
{
    "version": "$version",
    "url": "https://tridentclient.xyz/downloads/trident-$version.jar",
    "sha256": "$hash",
    "changelog": "$escapedChangelog"
}
"@
$stagedManifest = Join-Path $site "api\update.json"
# UTF-8 without BOM: a BOM would break Gson parsing in the mod.
[System.IO.File]::WriteAllText($stagedManifest, $json, (New-Object System.Text.UTF8Encoding $false))

Write-Host ""
Write-Host "=== Release $version staged ===" -ForegroundColor Cyan
Write-Host "  jar:      $stagedJar"
Write-Host "  manifest: $stagedManifest  (sha256 $hash)"

# ---- Deploy ----
$deployConfigPath = Join-Path $PSScriptRoot "deploy.config.json"
if ($NoDeploy -or -not (Test-Path $deployConfigPath)) {
    Write-Host ""
    Write-Host "No deploy config (or -NoDeploy) - sync manually (jar FIRST, then update.json):"
    Write-Host "  downloads/trident-$version.jar  ->  <docroot>/downloads/"
    Write-Host "  api/update.json                 ->  <docroot>/api/"
    Write-Host "Verify: curl https://tridentclient.xyz/api/update.php"
    Write-Host ""
    exit 0
}

$cfg = Get-Content $deployConfigPath -Raw | ConvertFrom-Json
$target = "$($cfg.user)@$($cfg.host)"
$sshArgs = @("-o", "BatchMode=yes", "-o", "ConnectTimeout=10")
if ($cfg.identityFile) { $sshArgs += @("-i", $cfg.identityFile) }

Write-Host ""
Write-Host "=== Deploying to $target ===" -ForegroundColor Cyan

& ssh @sshArgs $target "mkdir -p '$($cfg.docroot)/downloads' '$($cfg.docroot)/api'"
if ($LASTEXITCODE -ne 0) { throw "ssh failed - is the server reachable (Tailscale up?) and key auth set up?" }

# Endpoint code is tiny and idempotent - push it every time so the server never drifts from the repo.
& scp @sshArgs (Join-Path $site "api\update.php") (Join-Path $site "api\auth.php") "${target}:$($cfg.docroot)/api/"
if ($LASTEXITCODE -ne 0) { throw "endpoint upload failed" }

# Jar BEFORE manifest: the manifest must never point at a jar that isn't uploaded yet.
& scp @sshArgs $stagedJar "${target}:$($cfg.docroot)/downloads/"
if ($LASTEXITCODE -ne 0) { throw "jar upload failed" }

& scp @sshArgs $stagedManifest "${target}:$($cfg.docroot)/api/"
if ($LASTEXITCODE -ne 0) { throw "manifest upload failed" }

# ---- Verify through the public URL (via Cloudflare) ----
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Start-Sleep -Seconds 2
try {
    $live = Invoke-RestMethod -Uri "https://tridentclient.xyz/api/update.php" -TimeoutSec 20
    if ($live.version -eq $version) {
        Write-Host ""
        Write-Host "=== Deployed and verified: live manifest reports v$($live.version) ===" -ForegroundColor Green
    } else {
        Write-Warning "Live manifest reports v$($live.version), expected v$version - check the server."
        exit 1
    }
    $head = Invoke-WebRequest -Uri $live.url -Method Head -TimeoutSec 20 -UseBasicParsing
    Write-Host "Jar reachable: $($live.url) ($($head.Headers['Content-Length']) bytes)"
} catch {
    Write-Warning "Post-deploy verification failed: $($_.Exception.Message)"
    exit 1
}
