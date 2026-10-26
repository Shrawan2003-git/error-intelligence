$ErrorActionPreference = 'Stop'
$outageProjectRoot = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot -Parent)).Path
$outageStageRoot = Join-Path $outageProjectRoot '.local\staging'
$outageRedisRoot = Join-Path $outageStageRoot 'redis'
$outageEnvFile = Join-Path $outageStageRoot 'staging.env'
if (-not (Test-Path -LiteralPath $outageEnvFile)) { throw 'Start local staging first.' }
$outagePasswordLine = Get-Content -LiteralPath $outageEnvFile | Where-Object { $_ -like 'REDIS_PASSWORD=*' }
$outagePassword = $outagePasswordLine.Substring('REDIS_PASSWORD='.Length)
if ($outagePassword -notmatch '^[a-f0-9]{64}$') { throw 'Invalid project staging Redis credential.' }
$outageDistro = 'ErrorIntelligenceDev'
$outageShutdown = (& wsl.exe -d $outageDistro -- wslpath -a (Join-Path $outageRedisRoot 'shutdown.sh')).Trim()
$outageConfig = (& wsl.exe -d $outageDistro -- wslpath -a (Join-Path $outageRedisRoot 'redis.conf')).Trim()
if ($LASTEXITCODE -ne 0 -or -not $outageShutdown -or -not $outageConfig) { throw 'Project staging Redis files are unavailable.' }
try {
  $outagePassword | & wsl.exe -d $outageDistro -- sh $outageShutdown
  if ($LASTEXITCODE -ne 0) { throw 'The project staging Redis process did not stop.' }
  & node (Join-Path $PSScriptRoot 'Probe-Local-StagingRedisOutage.mjs')
  if ($LASTEXITCODE -ne 0) { throw 'The staged Redis outage probe failed.' }
} finally {
  & wsl.exe -d $outageDistro -u root -- redis-server $outageConfig
  if ($LASTEXITCODE -ne 0) { Write-Warning 'Project staging Redis did not restart; inspect .local/staging/redis/redis.log.' }
}
$recovered = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
  try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/actuator/health/readiness' -TimeoutSec 2
    if ($health.status -eq 'UP') { $recovered = $true; break }
  } catch { }
  Start-Sleep -Seconds 1
}
if (-not $recovered) { throw 'Local staging readiness did not recover after Redis restarted.' }
Write-Host 'Local staging Redis recovery passed: readiness returned to UP.'
