param(
  [ValidateSet('Run', 'Verify', 'VerifyNative', 'VerifyNativeLoad', 'Format', 'Unit')]
  [string]$Action = 'Run'
)
$ErrorActionPreference = 'Stop'
$phaseProjectRoot = Split-Path $PSScriptRoot -Parent
$env:MAVEN_USER_HOME = Join-Path $phaseProjectRoot '.tools\maven-user'
$phasePortableJdk = Get-ChildItem -LiteralPath (Join-Path $phaseProjectRoot '.tools\java') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($phasePortableJdk) {
  $env:JAVA_HOME = $phasePortableJdk.FullName
  $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}
$phaseEnvPath = Join-Path $phaseProjectRoot '.env'
if (Test-Path -LiteralPath $phaseEnvPath) {
  foreach ($phaseEnvLine in Get-Content -LiteralPath $phaseEnvPath) {
    if ($phaseEnvLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
      [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
  }
}
Push-Location (Join-Path $phaseProjectRoot 'back-end')
try {
  $phaseRepository = Join-Path $phaseProjectRoot '.tools\m2'
  $phaseArgs = @('-B', '-ntp', "-Dmaven.repo.local=$phaseRepository")
  switch ($Action) {
    'Run' { $phaseArgs += 'spring-boot:run' }
    'Verify' { $phaseArgs += 'verify' }
    { $_ -in @('VerifyNative', 'VerifyNativeLoad') } {
      if (-not $env:PG_BIN) { $env:PG_BIN = 'C:\Program Files\PostgreSQL\18\bin' }
      if (-not $env:PGVECTOR_HOME) {
        $env:PGVECTOR_HOME = Join-Path $env:TEMP 'error-intelligence-pgvector-v0.8.7'
        $phaseVectorShare = Join-Path $env:PGVECTOR_HOME 'share\extension'
        $phaseVectorLib = Join-Path $env:PGVECTOR_HOME 'lib'
        New-Item -ItemType Directory -Path $phaseVectorShare, $phaseVectorLib -Force | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $phaseProjectRoot '.tools\pgvector-runtime\share\extension') -File | Copy-Item -Destination $phaseVectorShare -Force
        $phaseVectorSource = Join-Path $phaseProjectRoot '.tools\pgvector-runtime\lib\vector.dll'
        $phaseVectorTarget = Join-Path $phaseVectorLib 'vector.dll'
        if (-not (Test-Path -LiteralPath $phaseVectorTarget)) {
          Copy-Item -LiteralPath $phaseVectorSource -Destination $phaseVectorTarget
        } elseif ((Get-FileHash -Algorithm SHA256 -LiteralPath $phaseVectorSource).Hash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $phaseVectorTarget).Hash) {
          throw 'The existing pgvector runtime differs from this project; stop the local staging database before replacing it.'
        }
      }
      if ($Action -eq 'VerifyNative') { $phaseArgs += @('clean', 'verify', '-Pnative-postgres') }
      else { $phaseArgs += @('verify', '-Pnative-postgres', '-Dit.test=NativeLoadIT') }
    }
    'Format' { $phaseArgs += 'spotless:apply' }
    'Unit' { $phaseArgs += 'test' }
  }
  $phasePortableMaven = Join-Path $phaseProjectRoot '.tools\maven\apache-maven-3.9.16\bin\mvn.cmd'
  if (Test-Path -LiteralPath $phasePortableMaven) { & $phasePortableMaven @phaseArgs }
  else { & '.\mvnw.cmd' @phaseArgs }
  if ($LASTEXITCODE -ne 0) { throw "Backend $Action failed. See the build output above." }
} finally { Pop-Location }
