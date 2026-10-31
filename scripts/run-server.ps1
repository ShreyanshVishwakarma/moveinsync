# Starts the configuration server from the packaged jar.
#
# Usage:  pwsh ./scripts/run-server.ps1 [-Port 8080] [-SkipBuild]

param(
    [int]$Port = 8080,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

try {
    if (-not $env:JAVA_HOME) { throw "JAVA_HOME is not set" }
    $java = Join-Path $env:JAVA_HOME "bin\java.exe"

    if (-not $SkipBuild) {
        & .\mvnw.cmd -q -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "build failed" }
    }

    $jar = Get-ChildItem "config-server\target\config-server-*.jar" |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    if (-not $jar) { throw "server jar not found; run without -SkipBuild" }

    Write-Host "Starting $($jar.Name) on http://localhost:$Port"
    & $java -jar $jar.FullName "--server.port=$Port"
}
finally {
    Pop-Location
}
