# One-click start for TraceLens on Windows.
# 1) caps Docker/WSL memory at 6 GB (.wslconfig)  2) starts Docker Desktop
# 3) builds and starts the stack                  4) opens the UI when the platform is up
$ErrorActionPreference = "Continue"   # native tools write progress to stderr; checks are explicit
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

function Info($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "ERROR: $m" -ForegroundColor Red; Read-Host "Press Enter to close"; exit 1 }

# Docker Desktop can be installed for all users (Program Files) or per user (AppData\Local\Programs)
$candidates = @(
  @{ exe = "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe";          bin = "$env:ProgramFiles\Docker\Docker\resources\bin" },
  @{ exe = "$env:LOCALAPPDATA\Programs\DockerDesktop\Docker Desktop.exe"; bin = "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin" }
)
$found = $candidates | Where-Object { Test-Path $_.exe } | Select-Object -First 1
if (-not $found) { Fail "Docker Desktop is not installed. Install it from https://www.docker.com/products/docker-desktop/ (Windows AMD64), then run this again." }
$dockerExe = $found.exe
Info "Docker Desktop found at $dockerExe"
# make sure the docker CLI is on PATH for this window (a fresh install only updates PATH for new sessions)
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  if (Test-Path $found.bin) { $env:Path = "$($found.bin);$env:Path" }
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "The docker command was not found. Restart the laptop once after installing Docker Desktop, then run this again." }
}

# 1. Memory cap
$wslconfig = Join-Path $env:USERPROFILE ".wslconfig"
$newConfig = -not (Test-Path $wslconfig)
if ($newConfig) {
  Info "Creating $wslconfig (Docker limited to 6 GB RAM)"
  "[wsl2]`nmemory=6GB`nprocessors=4`nswap=4GB`n" | Set-Content -Path $wslconfig -Encoding ASCII
  Info "Restarting WSL so the limit applies"
  Get-Process "Docker Desktop" -ErrorAction SilentlyContinue | Stop-Process -Force
  wsl --shutdown 2>$null
  Start-Sleep -Seconds 3
} else {
  Info ".wslconfig already exists, leaving it as is"
}

# 2. Docker engine
if (-not (Get-Process "Docker Desktop" -ErrorAction SilentlyContinue)) {
  Info "Starting Docker Desktop"
  Start-Process $dockerExe
}
Info "Waiting for the Docker engine (first start can take a couple of minutes)"
$ok = $false
for ($i = 0; $i -lt 90; $i++) {
  docker info *> $null
  if ($LASTEXITCODE -eq 0) { $ok = $true; break }
  Start-Sleep -Seconds 4
}
if (-not $ok) { Fail "Docker engine did not start. Open Docker Desktop, accept its terms if asked, wait for 'Engine running', then run this again." }

# 3. Build + start
Info "Building and starting TraceLens (first build: 5-10 minutes)"
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { Fail "docker compose failed - copy the output above and send it to Claude." }

# 4. Wait for the platform, then open the UI
Info "Waiting for the platform to become healthy"
$up = $false
for ($i = 0; $i -lt 60; $i++) {
  try {
    $h = Invoke-RestMethod "http://localhost:8080/actuator/health" -TimeoutSec 3
    if ($h.status -eq "UP") { $up = $true; break }
  } catch { }
  Start-Sleep -Seconds 5
}
docker compose ps
if (-not $up) {
  Write-Host "`nPlatform is not healthy yet. Last platform logs:" -ForegroundColor Yellow
  docker compose logs --tail 60 platform
  Fail "Send the logs above to Claude."
}
Info "TraceLens is up - opening http://localhost:3000 (data appears in ~15 s)"
Start-Process "http://localhost:3000"
Read-Host "Press Enter to close this window (TraceLens keeps running; stop it with: docker compose down)"
