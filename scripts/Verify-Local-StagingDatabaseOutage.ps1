$ErrorActionPreference = 'Stop'
$dbOutageProjectRoot = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot -Parent)).Path
$dbOutageData = Join-Path $dbOutageProjectRoot '.local\staging\postgres\data'
$dbOutagePathFile = Join-Path $dbOutageProjectRoot '.local\staging\backend.path'
$dbOutageJar = if (Test-Path -LiteralPath $dbOutagePathFile) { Get-Content -LiteralPath $dbOutagePathFile -Raw } else { Join-Path $dbOutageProjectRoot 'back-end\target\error-intelligence-backend-0.1.0-SNAPSHOT.jar' }
$dbOutageBin = if ($env:PG_BIN) { $env:PG_BIN } else { 'C:\Program Files\PostgreSQL\18\bin' }
if (-not $dbOutageJar.StartsWith($dbOutageProjectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    -not (Test-Path -LiteralPath (Join-Path $dbOutageData 'PG_VERSION')) -or
    -not (Test-Path -LiteralPath $dbOutageJar)) {
  throw 'The project-local staging database and active JAR must exist before the outage drill.'
}
$health = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/actuator/health/readiness' -TimeoutSec 5
if ($health.status -ne 'UP') { throw 'Local staging must be ready before the database outage drill.' }
try {
  & (Join-Path $dbOutageBin 'pg_ctl.exe') -D $dbOutageData -m fast -w stop
  if ($LASTEXITCODE -ne 0) { throw 'Project-local staging PostgreSQL did not stop.' }
  & node (Join-Path $PSScriptRoot 'Probe-Local-StagingDatabaseOutage.mjs')
  if ($LASTEXITCODE -ne 0) { throw 'Database outage readiness probe failed.' }
} finally {
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'Local-Staging.ps1') -Action Start -JarPath $dbOutageJar
  if ($LASTEXITCODE -ne 0) { Write-Warning 'Project staging did not fully recover; inspect its local logs.' }
}
$recovered = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/actuator/health/readiness' -TimeoutSec 5
if ($recovered.status -ne 'UP') { throw 'Local staging readiness did not recover after PostgreSQL restarted.' }
Write-Host 'Local staging PostgreSQL recovery passed: readiness returned to UP.'
