param(
  [ValidateSet('Start', 'Stop', 'Status')]
  [string]$Action = 'Start',
  [string]$PostgresBin = $env:PG_BIN
)
$ErrorActionPreference = 'Stop'
$phaseProjectRoot = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot -Parent)).Path
if (-not $PostgresBin) { $PostgresBin = 'C:\Program Files\PostgreSQL\18\bin' }
if (-not (Test-Path -LiteralPath (Join-Path $PostgresBin 'pg_ctl.exe'))) {
  throw 'Set PG_BIN or -PostgresBin to your installed PostgreSQL bin directory.'
}
$phaseLocalRoot = Join-Path $phaseProjectRoot '.local\postgres'
$phaseData = [System.IO.Path]::GetFullPath((Join-Path $phaseLocalRoot 'data'))
if (-not $phaseData.StartsWith($phaseProjectRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw 'The database directory must stay inside this project.'
}
if ($Action -eq 'Stop' -or $Action -eq 'Status') {
  & (Join-Path $PostgresBin 'pg_ctl.exe') -D $phaseData $Action.ToLowerInvariant()
  if ($LASTEXITCODE -ne 0) { throw "Project database $Action failed." }
  exit
}
$phaseEnvPath = Join-Path $phaseProjectRoot '.env'
if (-not (Test-Path -LiteralPath $phaseEnvPath)) { throw 'Run scripts/Initialize-Local.ps1 first.' }
$phaseValues = @{}
foreach ($phaseLine in Get-Content -LiteralPath $phaseEnvPath) {
  if ($phaseLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $phaseValues[$Matches[1]] = $Matches[2] }
}
$phaseUser = $phaseValues['POSTGRES_USER']
$phaseDatabase = $phaseValues['POSTGRES_DB']
$phasePassword = $phaseValues['POSTGRES_PASSWORD']
$phasePort = $phaseValues['POSTGRES_PORT']
if ($phaseUser -notmatch '^[a-z_][a-z0-9_]{0,62}$' -or $phaseDatabase -notmatch '^[a-z_][a-z0-9_]{0,62}$') { throw 'Use simple lowercase database/user names for local initialization.' }
if (-not $phasePassword -or $phasePort -notmatch '^\d+$' -or [int]$phasePort -lt 1024 -or [int]$phasePort -gt 65535) { throw 'A non-empty password and valid local port are required.' }
if ($phaseValues['DB_PASSWORD'] -ne $phasePassword -or $phaseValues['DB_USERNAME'] -ne $phaseUser) { throw 'Host DB credentials must match this isolated project cluster.' }
New-Item -ItemType Directory -Path $phaseLocalRoot -Force | Out-Null
$phaseVectorSource = Join-Path $phaseProjectRoot '.tools\pgvector-runtime'
$phaseVectorHome = Join-Path $env:TEMP 'error-intelligence-pgvector-v0.8.7'
$phaseVectorShare = Join-Path $phaseVectorHome 'share\extension'
$phaseVectorLib = Join-Path $phaseVectorHome 'lib'
if (-not (Test-Path -LiteralPath (Join-Path $phaseVectorSource 'share\extension\vector.control')) -or
    -not (Test-Path -LiteralPath (Join-Path $phaseVectorSource 'lib\vector.dll'))) {
  throw 'The project-local pgvector runtime is missing. Restore .tools/pgvector-runtime before starting the database.'
}
New-Item -ItemType Directory -Path $phaseVectorShare, $phaseVectorLib -Force | Out-Null
foreach ($source in Get-ChildItem -LiteralPath (Join-Path $phaseVectorSource 'share\extension') -File) {
  $destination = Join-Path $phaseVectorShare $source.Name
  if (-not (Test-Path -LiteralPath $destination)) { Copy-Item -LiteralPath $source.FullName -Destination $destination }
  elseif ((Get-FileHash -Algorithm SHA256 -LiteralPath $source.FullName).Hash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash) {
    throw "Project-local pgvector file differs: $($source.Name). Stop this project's database before updating its runtime."
  }
}
$phaseVectorDllSource = Join-Path $phaseVectorSource 'lib\vector.dll'
$phaseVectorDll = Join-Path $phaseVectorLib 'vector.dll'
if (-not (Test-Path -LiteralPath $phaseVectorDll)) { Copy-Item -LiteralPath $phaseVectorDllSource -Destination $phaseVectorDll }
elseif ((Get-FileHash -Algorithm SHA256 -LiteralPath $phaseVectorDllSource).Hash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $phaseVectorDll).Hash) {
  throw 'Project-local pgvector DLL differs. Stop this project''s database before updating its runtime.'
}
if (-not (Test-Path -LiteralPath (Join-Path $phaseData 'PG_VERSION'))) {
  $phasePasswordFile = Join-Path $phaseLocalRoot 'init-password.txt'
  [System.IO.File]::WriteAllText($phasePasswordFile, $phasePassword)
  try {
    & (Join-Path $PostgresBin 'initdb.exe') -D $phaseData "--username=$phaseUser" "--pwfile=$phasePasswordFile" --auth-host=scram-sha-256 --auth-local=scram-sha-256 --encoding=UTF8 --locale=C *> (Join-Path $phaseLocalRoot 'init.log')
    if ($LASTEXITCODE -ne 0) { throw 'Project database initialization failed. See .local/postgres/init.log.' }
  } finally { Remove-Item -LiteralPath $phasePasswordFile -ErrorAction SilentlyContinue }
}
& (Join-Path $PostgresBin 'pg_ctl.exe') -D $phaseData status *> $null
$phaseRunning = $LASTEXITCODE -eq 0
if ($phaseRunning) {
  $phasePriorPgPassword = $env:PGPASSWORD
  $env:PGPASSWORD = $phasePassword
  try {
    $phaseVectorAvailable = & (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p $phasePort -U $phaseUser -d postgres -tAc "SELECT 1 FROM pg_available_extensions WHERE name = 'vector'"
    if ($LASTEXITCODE -ne 0) { throw 'Project database connection failed while checking pgvector.' }
  } finally { $env:PGPASSWORD = $phasePriorPgPassword }
  if ($phaseVectorAvailable -ne '1') {
    Write-Host 'Restarting only this project database to enable its project-local pgvector runtime.'
    & (Join-Path $PostgresBin 'pg_ctl.exe') -D $phaseData -m fast -w stop
    if ($LASTEXITCODE -ne 0) { throw 'Project database restart failed while stopping PostgreSQL.' }
    $phaseRunning = $false
  }
}
if (-not $phaseRunning) {
  $phaseVectorControlRoot = Join-Path $phaseVectorHome 'share'
  $phaseOptions = "-h 127.0.0.1 -p $phasePort -c extension_control_path=$phaseVectorControlRoot;`$system -c dynamic_library_path=$phaseVectorLib;`$libdir"
  & (Join-Path $PostgresBin 'pg_ctl.exe') -D $phaseData -l (Join-Path $phaseLocalRoot 'server.log') -o $phaseOptions -w start
  if ($LASTEXITCODE -ne 0) { throw 'Project database startup failed. Check its log and port availability.' }
}
$phasePriorPgPassword = $env:PGPASSWORD
$env:PGPASSWORD = $phasePassword
try {
  $phaseExists = & (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p $phasePort -U $phaseUser -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$phaseDatabase'"
  if ($LASTEXITCODE -ne 0) { throw 'Project database connection failed.' }
  if ($phaseExists -ne '1') {
    & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p $phasePort -U $phaseUser $phaseDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Project database creation failed.' }
  }
} finally { $env:PGPASSWORD = $phasePriorPgPassword }
Write-Host "Isolated project PostgreSQL is ready on 127.0.0.1:$phasePort. Your existing database service was not changed."
