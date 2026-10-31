# Kill-the-server test.
#
# Starts the real server on a file-backed H2 database, attaches one SDK-based probe,
# publishes a change, kills the JVM, proves reads keep working from the SDK snapshot,
# restarts the server and proves the change is still there and the client reconnects.
#
# Usage:  pwsh ./scripts/kill-server-test.ps1 [-SkipBuild]

param(
    [int]$Port = 8091,
    [string]$DataDir = "test-data/kill",
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

$server = $null
$probe = $null

try {
    if (-not $env:JAVA_HOME) { throw "JAVA_HOME is not set" }
    $java = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (-not (Test-Path $java)) { throw "java not found at $java" }

    if (-not $SkipBuild) {
        Write-Host "building..."
        & .\mvnw.cmd -q -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "build failed" }
    }

    $serverJar = Get-ChildItem "config-server\target\config-server-*.jar" |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    $benchJar = Get-ChildItem "config-benchmarks\target\config-benchmarks-*-jar-with-dependencies.jar" |
        Select-Object -First 1
    if (-not $serverJar -or -not $benchJar) { throw "build artifacts missing; run without -SkipBuild" }

    if (Test-Path $DataDir) { Remove-Item $DataDir -Recurse -Force }
    New-Item -ItemType Directory -Path $DataDir | Out-Null

    $normalizedDataDir = $DataDir.Replace('\', '/')
    $dbUrl = "jdbc:h2:file:./$normalizedDataDir/configdb;DB_CLOSE_ON_EXIT=FALSE"
    $baseUrl = "http://127.0.0.1:$Port"
    $cacheFile = Join-Path $DataDir "config_cache.json"
    $probeOut = Join-Path $DataDir "probe.out"
    $serverOut = Join-Path $DataDir "server.out"
    $serverErr = Join-Path $DataDir "server.err"

    function Start-TestServer {
        $process = Start-Process -FilePath $java -ArgumentList @(
            "-jar", $serverJar.FullName,
            "--server.port=$Port",
            "--spring.datasource.url=$dbUrl",
            "--moveinsync.seed.enabled=true"
        ) -PassThru -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr -WindowStyle Hidden

        for ($i = 0; $i -lt 90; $i++) {
            Start-Sleep -Milliseconds 500
            try {
                if ((Invoke-RestMethod "$baseUrl/actuator/health" -TimeoutSec 2).status -eq 'UP') {
                    return $process
                }
            } catch {
                # still starting
            }
        }
        throw "server did not become healthy; see $serverErr"
    }

    function Get-ProbeLines {
        if (-not (Test-Path $probeOut)) { return @() }
        try {
            return @(Get-Content $probeOut -ErrorAction Stop)
        } catch {
            return @()
        }
    }

    function Wait-Probe([string]$pattern, [int]$timeoutSeconds) {
        $deadline = (Get-Date).AddSeconds($timeoutSeconds)
        while ((Get-Date) -lt $deadline) {
            if (Get-ProbeLines | Select-String -Pattern $pattern -Quiet) { return $true }
            Start-Sleep -Milliseconds 250
        }
        return $false
    }

    Write-Host "1) starting server on $baseUrl (file-backed H2)"
    $server = Start-TestServer

    Write-Host "   waiting for demo data"
    $seeded = $false
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $version = (Invoke-RestMethod "$baseUrl/api/v1/config/version" `
                    -Headers @{ "X-API-Key" = "demo-admin-key" } -TimeoutSec 2).version
            if ($version -ge 1) { $seeded = $true; break }
        } catch {
            # seeding in progress
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $seeded) { throw "demo data was never seeded" }

    Write-Host "2) starting SDK probe"
    $probe = Start-Process -FilePath $java -ArgumentList @(
        "-jar", $benchJar.FullName, "probe",
        "--url", $baseUrl,
        "--cache", $cacheFile,
        "--seconds", "40"
    ) -PassThru -RedirectStandardOutput $probeOut -RedirectStandardError (Join-Path $DataDir "probe.err") -WindowStyle Hidden

    if (-not (Wait-Probe 'connected=true' 30)) { throw "probe never connected to the server" }
    Write-Host "   probe connected"

    Write-Host "3) publishing booking_cutoff_minutes 30 -> 29"
    $body = @{
        author  = "kill-test"
        reason  = "prove live propagation and durability"
        changes = @(@{
            key      = "booking_cutoff_minutes"
            level    = "OFFICE"
            entityId = "blr-office-1"
            value    = "29"
            clientId = "acme-corp"
        })
    } | ConvertTo-Json -Depth 5

    Invoke-RestMethod "$baseUrl/api/v1/change-sets" -Method Post `
        -Headers @{ "X-API-Key" = "demo-operator-key" } `
        -ContentType "application/json" -Body $body | Out-Null

    if (-not (Wait-Probe 'cutoff=29' 10)) { throw "probe never observed the pushed change" }
    Write-Host "   change observed by the probe"

    Write-Host "4) killing the server JVM"
    Stop-Process -Id $server.Id -Force
    $server = $null
    Start-Sleep -Seconds 3

    $offline = Get-ProbeLines | Select-String -Pattern 'connected=false' | Select-Object -Last 1
    if (-not $offline) { throw "probe did not notice the outage" }
    if ($offline.Line -notmatch 'cutoff=29') {
        throw "probe stopped serving the last good value during the outage"
    }
    Write-Host "   during outage: $($offline.Line.Trim())"

    Write-Host "5) restarting the server against the same database"
    $linesBeforeRestart = (Get-ProbeLines).Count
    $server = Start-TestServer

    $reconnectDeadline = (Get-Date).AddSeconds(30)
    $reconnected = $false
    while ((Get-Date) -lt $reconnectDeadline) {
        $newLines = @(Get-ProbeLines | Select-Object -Skip $linesBeforeRestart)
        if ($newLines | Select-String -Pattern 'connected=true' -Quiet) {
            $reconnected = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $reconnected) { throw "probe did not reconnect after restart" }
    Start-Sleep -Seconds 2

    $recovered = Get-ProbeLines |
        Select-Object -Skip $linesBeforeRestart |
        Select-String -Pattern 'version=\d+ cutoff=29 connected=true' |
        Select-Object -Last 1
    if (-not $recovered) { throw "probe did not converge to the persisted value after restart" }

    Write-Host "   after restart: $($recovered.Line.Trim())"
    Write-Host ""
    Write-Host "PASS: reads survived the outage; the published change survived the restart."
}
finally {
    if ($probe -and -not $probe.HasExited) {
        Stop-Process -Id $probe.Id -Force -ErrorAction SilentlyContinue
    }
    if ($server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}
