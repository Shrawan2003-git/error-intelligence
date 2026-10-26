param(
  [ValidateSet('Start', 'Stop', 'Status')][string]$Action = 'Status',
  [string]$JarPath,
  [string]$ReleaseLabel
)

$ErrorActionPreference = 'Stop'
$stageProjectRoot = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot -Parent)).Path
$stageRoot = Join-Path $stageProjectRoot '.local\staging'
$stageEnvFile = Join-Path $stageRoot 'staging.env'
$stagePgRoot = Join-Path $stageRoot 'postgres'
$stagePgData = Join-Path $stagePgRoot 'data'
$stageRedisRoot = Join-Path $stageRoot 'redis'
$stageAppPidFile = Join-Path $stageRoot 'backend.pid'
$stageAppPathFile = Join-Path $stageRoot 'backend.path'
$stagePgBin = if ($env:PG_BIN) { $env:PG_BIN } else { 'C:\Program Files\PostgreSQL\18\bin' }
$stageRedisDistro = 'ErrorIntelligenceDev'
$stageJar = if ($JarPath) { [IO.Path]::GetFullPath($JarPath) } else { Join-Path $stageProjectRoot 'back-end\target\error-intelligence-backend-0.1.0-SNAPSHOT.jar' }

if (-not $stageRoot.StartsWith($stageProjectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Staging files must stay inside this project.'
}
if (-not $stageJar.StartsWith($stageProjectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'The staging JAR must stay inside this project.'
}
if ($ReleaseLabel -and $ReleaseLabel -notmatch '^[A-Za-z0-9._-]{1,80}$') { throw 'Invalid release label.' }

function New-StageSecret([ValidateSet('Hex', 'Base64')][string]$Format) {
  $bytes = [byte[]]::new(32)
  $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
  try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
  if ($Format -eq 'Base64') { return [Convert]::ToBase64String($bytes) }
  return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Read-StageConfig {
  if (-not (Test-Path -LiteralPath $stageEnvFile)) { throw 'No local staging configuration exists. Run -Action Start first.' }
  $values = @{}
  foreach ($line in Get-Content -LiteralPath $stageEnvFile) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
  }
  if ($values['DB_PASSWORD'] -notmatch '^[a-f0-9]{64}$' -or
      $values['REDIS_PASSWORD'] -notmatch '^[a-f0-9]{64}$' -or
      $values['AUTH_SIGNING_KEY'] -notmatch '^[A-Za-z0-9+/=]{44}$') {
    throw 'Local staging configuration is incomplete.'
  }
  return $values
}

function Test-StageRedis($values) {
  $scriptPath = Join-Path $stageRedisRoot 'health.sh'
  if (-not (Test-Path -LiteralPath $scriptPath)) { return $false }
  $linuxScript = (& wsl.exe -d $stageRedisDistro -- wslpath -a $scriptPath).Trim()
  if ($LASTEXITCODE -ne 0) { return $false }
  $result = $values['REDIS_PASSWORD'] | & wsl.exe -d $stageRedisDistro -- sh $linuxScript
  return ($LASTEXITCODE -eq 0 -and $result -eq 'PONG')
}

function Test-StageApp {
  try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/actuator/health/readiness' -TimeoutSec 2 -MaximumRedirection 0
    return $health.status -eq 'UP'
  } catch { return $false }
}

if ($Action -eq 'Start' -and -not (Test-Path -LiteralPath $stageEnvFile)) {
  New-Item -ItemType Directory -Path $stageRoot -Force | Out-Null
  $dbPassword = New-StageSecret Hex
  $redisPassword = New-StageSecret Hex
  $signingKey = New-StageSecret Base64
  $content = @"
# Project-only local staging credentials; never commit or print values.
DB_URL=jdbc:postgresql://127.0.0.1:15433/error_intelligence_stage
DB_USERNAME=error_intelligence_stage
DB_PASSWORD=$dbPassword
REDIS_HOST=127.0.0.1
REDIS_PORT=16379
REDIS_PASSWORD=$redisPassword
AUTH_SIGNING_KEY=$signingKey
AUTH_ISSUER=error-intelligence-stage
SERVER_PORT=18080
AI_WORKER_ENABLED=false
AI_EMBEDDING_WORKER_ENABLED=false
AI_DAILY_ANALYSIS_LIMIT=1
OTEL_TRACING_ENABLED=false
PROMETHEUS_ALLOWED_CIDRS=127.0.0.1/32,::1/128
"@
  [IO.File]::WriteAllText($stageEnvFile, $content, [Text.UTF8Encoding]::new($false))
}

if ($Action -eq 'Status' -and -not (Test-Path -LiteralPath $stageEnvFile)) {
  Write-Host 'Local staging has not been initialized.'
  exit 0
}
$stageValues = Read-StageConfig

if ($Action -eq 'Stop') {
  if (Test-Path -LiteralPath $stageAppPidFile) {
    $appPid = [int](Get-Content -LiteralPath $stageAppPidFile -Raw)
    $recordedJar = if (Test-Path -LiteralPath $stageAppPathFile) { Get-Content -LiteralPath $stageAppPathFile -Raw } else { $stageJar }
    $appProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $appPid" -ErrorAction SilentlyContinue
    if ($appProcess -and $appProcess.CommandLine -like "*$recordedJar*") { Stop-Process -Id $appPid -Force }
    elseif ($appProcess) { throw 'Recorded backend PID does not match the project staging JAR; refusing to stop it.' }
    Remove-Item -LiteralPath $stageAppPidFile
    Remove-Item -LiteralPath $stageAppPathFile -ErrorAction SilentlyContinue
  }
  if (Test-StageRedis $stageValues) {
    $linuxShutdown = (& wsl.exe -d $stageRedisDistro -- wslpath -a (Join-Path $stageRedisRoot 'shutdown.sh')).Trim()
    $stageValues['REDIS_PASSWORD'] | & wsl.exe -d $stageRedisDistro -- sh $linuxShutdown
    if ($LASTEXITCODE -ne 0) { throw 'Project staging Redis did not stop.' }
  }
  if (Test-Path -LiteralPath (Join-Path $stagePgData 'PG_VERSION')) {
    & (Join-Path $stagePgBin 'pg_ctl.exe') -D $stagePgData status *> $null
    if ($LASTEXITCODE -eq 0) {
      & (Join-Path $stagePgBin 'pg_ctl.exe') -D $stagePgData -m fast -w stop
      if ($LASTEXITCODE -ne 0) { throw 'Project staging PostgreSQL did not stop.' }
    }
  }
  Write-Host 'Local staging stopped. Database files and secrets were preserved.'
  exit 0
}

if ($Action -eq 'Status') {
  $pgUp = $false
  if (Test-Path -LiteralPath (Join-Path $stagePgData 'PG_VERSION')) {
    & (Join-Path $stagePgBin 'pg_ctl.exe') -D $stagePgData status *> $null
    $pgUp = $LASTEXITCODE -eq 0
  }
  Write-Host "Local staging: postgres=$pgUp redis=$(Test-StageRedis $stageValues) backend=$(Test-StageApp)"
  exit 0
}

if (-not (Test-Path -LiteralPath (Join-Path $stagePgBin 'initdb.exe')) -or -not (Test-Path -LiteralPath $stageJar)) {
  throw 'Build the backend JAR and set PG_BIN to an installed PostgreSQL bin directory before starting staging.'
}
New-Item -ItemType Directory -Path $stagePgRoot, $stageRedisRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $stagePgData 'PG_VERSION'))) {
  $passwordFile = Join-Path $stagePgRoot 'init-password.txt'
  [IO.File]::WriteAllText($passwordFile, $stageValues['DB_PASSWORD'])
  try {
    & (Join-Path $stagePgBin 'initdb.exe') -D $stagePgData '--username=error_intelligence_stage' "--pwfile=$passwordFile" --auth-host=scram-sha-256 --auth-local=scram-sha-256 --encoding=UTF8 --locale=C *> (Join-Path $stagePgRoot 'init.log')
    if ($LASTEXITCODE -ne 0) { throw 'Project staging PostgreSQL initialization failed.' }
  } finally { Remove-Item -LiteralPath $passwordFile -ErrorAction SilentlyContinue }
}
$vectorHome = Join-Path $env:TEMP 'error-intelligence-pgvector-v0.8.7'
$vectorShare = Join-Path $vectorHome 'share\extension'
$vectorLib = Join-Path $vectorHome 'lib'
New-Item -ItemType Directory -Path $vectorShare, $vectorLib -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $stageProjectRoot '.tools\pgvector-runtime\share\extension') -File | Copy-Item -Destination $vectorShare -Force
Copy-Item -LiteralPath (Join-Path $stageProjectRoot '.tools\pgvector-runtime\lib\vector.dll') -Destination $vectorLib -Force
& (Join-Path $stagePgBin 'pg_ctl.exe') -D $stagePgData status *> $null
if ($LASTEXITCODE -ne 0) {
  $vectorControlRoot = Join-Path $vectorHome 'share'
  $options = "-h 127.0.0.1 -p 15433 -c extension_control_path=$vectorControlRoot;`$system -c dynamic_library_path=$vectorLib;`$libdir"
  & (Join-Path $stagePgBin 'pg_ctl.exe') -D $stagePgData -l (Join-Path $stagePgRoot 'server.log') -o $options -w start
  if ($LASTEXITCODE -ne 0) { throw 'Project staging PostgreSQL startup failed.' }
}
$priorPgPassword = $env:PGPASSWORD
$env:PGPASSWORD = $stageValues['DB_PASSWORD']
try {
  $exists = & (Join-Path $stagePgBin 'psql.exe') -h 127.0.0.1 -p 15433 -U error_intelligence_stage -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'error_intelligence_stage'"
  if ($LASTEXITCODE -ne 0) { throw 'Project staging PostgreSQL authentication failed.' }
  if ($exists -ne '1') {
    & (Join-Path $stagePgBin 'createdb.exe') -h 127.0.0.1 -p 15433 -U error_intelligence_stage error_intelligence_stage
    if ($LASTEXITCODE -ne 0) { throw 'Project staging database creation failed.' }
  }
} finally { $env:PGPASSWORD = $priorPgPassword }

$linuxRedisRoot = (& wsl.exe -d $stageRedisDistro -- wslpath -a $stageRedisRoot).Trim()
if ($LASTEXITCODE -ne 0) { throw 'The project WSL Redis distribution is unavailable.' }
$redisConfig = @"
bind 127.0.0.1
protected-mode yes
port 16379
requirepass $($stageValues['REDIS_PASSWORD'])
daemonize yes
pidfile "$linuxRedisRoot/redis.pid"
logfile "$linuxRedisRoot/redis.log"
dir "$linuxRedisRoot"
save ""
appendonly no
maxmemory 128mb
maxmemory-policy noeviction
"@
[IO.File]::WriteAllText((Join-Path $stageRedisRoot 'redis.conf'), $redisConfig.Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $stageRedisRoot 'health.sh'), @'
#!/bin/sh
set -eu
read -r password
REDISCLI_AUTH=$(printf '%s' "$password" | tr -d '\r')
export REDISCLI_AUTH
test "$(redis-cli -p 16379 ping)" = PONG
printf '%s\n' PONG
'@.Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $stageRedisRoot 'shutdown.sh'), @'
#!/bin/sh
set -eu
read -r password
REDISCLI_AUTH=$(printf '%s' "$password" | tr -d '\r')
export REDISCLI_AUTH
test "$(redis-cli -p 16379 ping)" = PONG
redis-cli -p 16379 shutdown nosave
'@.Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
if (-not (Test-StageRedis $stageValues)) {
  $linuxConf = (& wsl.exe -d $stageRedisDistro -- wslpath -a (Join-Path $stageRedisRoot 'redis.conf')).Trim()
  & wsl.exe -d $stageRedisDistro -u root -- redis-server $linuxConf
  if ($LASTEXITCODE -ne 0 -or -not (Test-StageRedis $stageValues)) { throw 'Project staging Redis startup failed.' }
}

if ((Test-StageApp) -and (Test-Path -LiteralPath $stageAppPathFile)) {
  $activeJar = Get-Content -LiteralPath $stageAppPathFile -Raw
  if (-not [string]::Equals($activeJar, $stageJar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'A different staging JAR is already running. Stop local staging before switching artifacts.'
  }
}
if (-not (Test-StageApp)) {
  $javaHome = Get-ChildItem -LiteralPath (Join-Path $stageProjectRoot '.tools\java') -Directory | Select-Object -First 1
  if (-not $javaHome) { throw 'Project-local Java 21 runtime is missing.' }
  $javaExe = Join-Path $javaHome.FullName 'bin\java.exe'
  $saved = @{}
  try {
    foreach ($name in $stageValues.Keys) {
      $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
      [Environment]::SetEnvironmentVariable($name, $stageValues[$name], 'Process')
    }
    foreach ($name in @('SPRING_PROFILES_ACTIVE', 'OPENAI_API_KEY')) {
      $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
      [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    $arguments = @('-jar', "`"$stageJar`"", '--server.address=127.0.0.1')
    if ($ReleaseLabel) { $arguments += "--app.version=$ReleaseLabel" }
    $process = Start-Process -FilePath $javaExe -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $stageRoot 'backend.log') -RedirectStandardError (Join-Path $stageRoot 'backend.err')
    [IO.File]::WriteAllText($stageAppPidFile, [string]$process.Id)
    [IO.File]::WriteAllText($stageAppPathFile, $stageJar)
  } finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
  }
  $ready = $false
  for ($attempt = 0; $attempt -lt 60; $attempt++) {
    if (Test-StageApp) { $ready = $true; break }
    Start-Sleep -Seconds 1
  }
  if (-not $ready) { throw 'Project staging backend did not become ready. Check .local/staging/backend.err and backend.log.' }
}
Write-Host 'Docker-free local staging is ready at http://127.0.0.1:18080 (loopback only).'
