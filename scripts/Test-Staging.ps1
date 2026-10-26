param(
  [Parameter(Mandatory = $true)]
  [uri]$BaseUrl,
  [string]$ExpectedVersion,
  [switch]$AllowHttp
)

$ErrorActionPreference = 'Stop'

if (-not $BaseUrl.IsAbsoluteUri -or $BaseUrl.UserInfo -or $BaseUrl.Query -or $BaseUrl.Fragment -or $BaseUrl.AbsolutePath -ne '/') {
  throw 'BaseUrl must be an absolute origin without credentials, path, query, or fragment.'
}
if ($BaseUrl.Scheme -ne 'https' -and -not ($AllowHttp -and $BaseUrl.Scheme -eq 'http')) {
  throw 'Staging smoke checks require HTTPS. Use -AllowHttp only for a local development server.'
}

$stageOrigin = $BaseUrl.GetLeftPart([System.UriPartial]::Authority).TrimEnd('/')
foreach ($stageProbe in @('liveness', 'readiness')) {
  $stageHealth = Invoke-RestMethod -Uri "$stageOrigin/actuator/health/$stageProbe" -Method Get -TimeoutSec 10 -MaximumRedirection 0
  if ($stageHealth.status -ne 'UP') {
    throw "The $stageProbe health probe did not report UP."
  }
}

$stageSystem = Invoke-RestMethod -Uri "$stageOrigin/api/v1/system" -Method Get -TimeoutSec 10 -MaximumRedirection 0
if ($stageSystem.name -ne 'Error Intelligence') {
  throw 'The system endpoint returned an unexpected application name.'
}
if ($ExpectedVersion -and $stageSystem.version -ne $ExpectedVersion) {
  throw "Expected version '$ExpectedVersion', received '$($stageSystem.version)'."
}

Write-Host "Staging smoke passed: version=$($stageSystem.version) liveness=UP readiness=UP"
