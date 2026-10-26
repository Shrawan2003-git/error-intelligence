$ErrorActionPreference = 'Stop'
$restoreProjectRoot = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot -Parent)).Path
$restoreStageRoot = Join-Path $restoreProjectRoot '.local\staging'
$restoreEnvPath = Join-Path $restoreStageRoot 'staging.env'
if (-not (Test-Path -LiteralPath $restoreEnvPath)) { throw 'Start local staging first.' }
$restoreValues = @{}
foreach ($line in Get-Content -LiteralPath $restoreEnvPath) {
  if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $restoreValues[$Matches[1]] = $Matches[2] }
}
if ($restoreValues['DB_URL'] -ne 'jdbc:postgresql://127.0.0.1:15433/error_intelligence_stage' -or
    $restoreValues['DB_USERNAME'] -ne 'error_intelligence_stage' -or
    $restoreValues['DB_PASSWORD'] -notmatch '^[a-f0-9]{64}$') {
  throw 'The staging database identity does not match the project-local instance.'
}
$restorePgBin = if ($env:PG_BIN) { $env:PG_BIN } else { 'C:\Program Files\PostgreSQL\18\bin' }
$restoreName = 'stage_restore_' + [Guid]::NewGuid().ToString('N')
$restoreDump = Join-Path $restoreStageRoot ($restoreName + '.dump')
$restoreCreated = $false
$priorPassword = $env:PGPASSWORD
$env:PGPASSWORD = $restoreValues['DB_PASSWORD']
try {
  $sourceCount = & (Join-Path $restorePgBin 'psql.exe') -h 127.0.0.1 -p 15433 -U error_intelligence_stage -d error_intelligence_stage -tAc 'select count(*) from intelligence.error_events'
  if ($LASTEXITCODE -ne 0 -or [int]$sourceCount -lt 1) { throw 'No synthetic staging event exists to verify after restore.' }
  & (Join-Path $restorePgBin 'pg_dump.exe') -h 127.0.0.1 -p 15433 -U error_intelligence_stage -Fc -f $restoreDump error_intelligence_stage
  if ($LASTEXITCODE -ne 0) { throw 'Project staging dump failed.' }
  & (Join-Path $restorePgBin 'createdb.exe') -h 127.0.0.1 -p 15433 -U error_intelligence_stage $restoreName
  if ($LASTEXITCODE -ne 0) { throw 'Isolated restore database creation failed.' }
  $restoreCreated = $true
  & (Join-Path $restorePgBin 'pg_restore.exe') -h 127.0.0.1 -p 15433 -U error_intelligence_stage -d $restoreName --no-owner --exit-on-error $restoreDump
  if ($LASTEXITCODE -ne 0) { throw 'Isolated staging restore failed.' }
  $restoredCount = & (Join-Path $restorePgBin 'psql.exe') -h 127.0.0.1 -p 15433 -U error_intelligence_stage -d $restoreName -tAc 'select count(*) from intelligence.error_events'
  if ($LASTEXITCODE -ne 0 -or [int]$restoredCount -ne [int]$sourceCount) {
    throw 'The restored synthetic event count did not match the source.'
  }
  Write-Host "Isolated local staging restore passed: syntheticEvents=$restoredCount"
} finally {
  if ($restoreCreated -and $restoreName -match '^stage_restore_[a-f0-9]{32}$') {
    & (Join-Path $restorePgBin 'dropdb.exe') -h 127.0.0.1 -p 15433 -U error_intelligence_stage $restoreName
    if ($LASTEXITCODE -ne 0) { Write-Warning 'The isolated restore database could not be removed; inspect it manually.' }
  }
  Remove-Item -LiteralPath $restoreDump -ErrorAction SilentlyContinue
  $env:PGPASSWORD = $priorPassword
}
