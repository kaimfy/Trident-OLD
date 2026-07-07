# Builds the mod, stages the release into TridentWebsite, and — if deploy.config.json
# exists next to this script — deploys it to the live server over SSH (e.g. via Tailscale).
#
# Usage: .\release.ps1 [-Bump patch|minor|major] [-Changelog "what changed"] [-NoDeploy]
#
#   -Bump    increments mod_version in gradle.properties before building
#            (patch: 2.0.1 -> 2.0.2, minor: 2.0.1 -> 2.1.0, major: 2.0.1 -> 3.0.0).
#            Without it, the current mod_version is released as-is — but never
#            a version that is already live (see guard below).
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
    [ValidateSet("patch", "minor", "major")]
    [string]$Bump = "",
    [switch]$NoDeploy
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# ---- Version (read, optionally bump) ----
$gradleProps = Join-Path $PSScriptRoot "gradle.properties"
$version = (Select-String -Path $gradleProps -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()

if ($Bump) {
    $parts = $version.Split('.')
    if ($parts.Count -ne 3) { throw "mod_version '$version' is not x.y.z - bump it manually" }
    # Intermediate vars with explicit parens: PS 5.1 misparses casts inside @(...) lists.
    $vMajor = [int]($parts[0]); $vMinor = [int]($parts[1]); $vPatch = [int]($parts[2])
    switch ($Bump) {
        "major" { $newVersion = "$($vMajor + 1).0.0" }
        "minor" { $newVersion = "$vMajor.$($vMinor + 1).0" }
        "patch" { $newVersion = "$vMajor.$vMinor.$($vPatch + 1)" }
    }
    $content = [System.IO.File]::ReadAllText($gradleProps)
    $content = $content -replace "(?m)^mod_version=.+$", "mod_version=$newVersion"
    [System.IO.File]::WriteAllText($gradleProps, $content)
    Write-Host "Bumped mod_version: $version -> $newVersion" -ForegroundColor Cyan
    $version = $newVersion
}

# ---- Guard: never re-release a version that is already live ----
# Released jars are immutable (Cloudflare caches them, and clients on that
# version would never re-update), so a same-version release is always a mistake.
$liveVersion = $null
try {
    $liveVersion = (Invoke-RestMethod -Uri "https://tridentclient.xyz/api/update.php" -TimeoutSec 15).version
} catch {
    Write-Warning "Could not read live manifest to check for version collision: $($_.Exception.Message)"
}
if ($liveVersion -eq $version) {
    throw "v$version is already the live release. Re-run with -Bump patch (or edit mod_version in gradle.properties)."
}

# ---- Build ----
& .\gradlew.bat build
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

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
