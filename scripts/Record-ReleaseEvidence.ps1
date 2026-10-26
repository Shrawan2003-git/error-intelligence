param([string]$OutputDirectory = '.local/release-evidence')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$destination = [IO.Path]::GetFullPath((Join-Path $repo $OutputDirectory))
if (-not $destination.StartsWith(($repo + [IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Evidence output must be inside this repository.'
}
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Push-Location $repo
try {
  $files = @(& git -c "safe.directory=$($repo.Replace('\','/'))" ls-files --cached --others --exclude-standard)
  if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate source files.' }
  $manifest = foreach ($relative in ($files | Sort-Object -Unique)) {
    $path = Join-Path $repo $relative
    if (Test-Path -LiteralPath $path -PathType Leaf) {
      [ordered]@{ path = $relative; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
  }
  $head = & git -c "safe.directory=$($repo.Replace('\','/'))" rev-parse --verify --quiet HEAD
  $revision = if ($LASTEXITCODE -eq 0) { $head } else { $null }
  [ordered]@{ recordedAtUtc = [DateTime]::UtcNow.ToString('o'); revision = $revision; files = @($manifest) } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'source-manifest.json')
  Get-ChildItem -LiteralPath (Join-Path $repo 'back-end/target') -Filter '*.jar' -File |
    Get-FileHash -Algorithm SHA256 | Select-Object Hash, @{Name='Artifact';Expression={Split-Path -Leaf $_.Path}} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'artifact-checksums.json')
  Write-Output "Recorded source manifest and artifact checksums in $destination"
} finally { Pop-Location }
