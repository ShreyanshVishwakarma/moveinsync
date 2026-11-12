# Starts the config server (8080) and the React dashboard (5173) together.
#
# Usage:  pwsh ./scripts/run-demo.ps1 [-SkipBuild] [-ServerPort 8080] [-UiPort 5173]

param(
    [switch]$SkipBuild,
    [int]$ServerPort = 8080,
    [int]$UiPort = 5173
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$uiDir = Join-Path $repoRoot 'config-ui'
Push-Location $repoRoot

$server = $null
$ui = $null

try {
    if (-not $env:JAVA_HOME) { throw "JAVA_HOME is not set" }
    $java = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (-not (Test-Path $java)) { throw "java not found at $java" }

    if (-not $SkipBuild) {
        Write-Host "Building server and dashboard..." -ForegroundColor Cyan
        & .\mvnw.cmd -q -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "server build failed" }

        if (-not (Test-Path (Join-Path $uiDir 'node_modules'))) {
            Push-Location $uiDir
            & npm.cmd install --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { throw "npm install failed" }
            Pop-Location
        }
    }

    $jar = Get-ChildItem "config-server\target\config-server-*.jar" |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    if (-not $jar) { throw "server jar not found; run without -SkipBuild" }

    Write-Host "Starting config server on http://localhost:$ServerPort" -ForegroundColor Cyan
    $server = Start-Process -FilePath $java -ArgumentList @(
        "-jar", $jar.FullName, "--server.port=$ServerPort"
    ) -PassThru -WindowStyle Hidden `
      -RedirectStandardOutput (Join-Path $repoRoot 'config-server\target\demo-server.out') `
      -RedirectStandardError (Join-Path $repoRoot 'config-server\target\demo-server.err')

    $healthy = $false
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Milliseconds 1000
        try {
            if ((Invoke-RestMethod "http://localhost:$ServerPort/actuator/health" -TimeoutSec 2).status -eq 'UP') {
                $healthy = $true
                break
            }
        } catch {
            # still starting
        }
    }
    if (-not $healthy) { throw "server did not become healthy; see config-server\target\demo-server.err" }

    # Wait for the demo seed so the dashboard never opens on version 0.
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $version = (Invoke-RestMethod "http://localhost:$ServerPort/api/v1/config/version" `
                -Headers @{ "X-API-Key" = "demo-admin-key" } -TimeoutSec 2).version
            if ($version -ge 1) { break }
        } catch {
            # seeding
        }
        Start-Sleep -Milliseconds 500
    }

    Write-Host "Starting dashboard on http://localhost:$UiPort" -ForegroundColor Cyan
    $env:CONFIG_SERVER_URL = "http://localhost:$ServerPort"
    Push-Location $uiDir
    $ui = Start-Process -FilePath "npm.cmd" -ArgumentList @("run", "dev", "--", "--port", "$UiPort") `
        -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $repoRoot 'config-ui\vite.out') `
        -RedirectStandardError (Join-Path $repoRoot 'config-ui\vite.err')
    Pop-Location

    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 1000
        try {
            if ((Invoke-WebRequest "http://localhost:$UiPort" -TimeoutSec 2).StatusCode -eq 200) { break }
        } catch {
            # still starting
        }
    }

    Write-Host ""
    Write-Host "Demo ready:" -ForegroundColor Green
    Write-Host "  Dashboard  http://localhost:$UiPort"
    Write-Host "  Server     http://localhost:$ServerPort/actuator/health"
    Write-Host ""
    Write-Host "Sign in with the role selector: Admin, Operator, Employee, or Globex operator."
    Write-Host "Press Ctrl+C to stop both processes."
    Write-Host ""

    while ($true) {
        if ($server.HasExited) { throw "config server exited unexpectedly" }
        if ($ui.HasExited) { throw "dashboard exited unexpectedly" }
        Start-Sleep -Seconds 2
    }
}
finally {
    if ($ui -and -not $ui.HasExited) { Stop-Process -Id $ui.Id -Force -ErrorAction SilentlyContinue }
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    # npm spawns a child node process; stop anything still holding the UI port.
    Get-NetTCPConnection -LocalPort $UiPort -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    Pop-Location
}
