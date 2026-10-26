$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$storyboard = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'storyboard.json') | ConvertFrom-Json
$audioDirectory = Join-Path $projectRoot '.local\demo-video\audio'
New-Item -ItemType Directory -Force -Path $audioDirectory | Out-Null
Add-Type -AssemblyName System.Speech

foreach ($scene in $storyboard) {
  $destination = Join-Path $audioDirectory ($scene.id + '.wav')
  $speaker = [System.Speech.Synthesis.SpeechSynthesizer]::new()
  try {
    $speaker.SelectVoice('Microsoft Zira Desktop')
    $speaker.Rate = -1
    $speaker.SetOutputToWaveFile($destination)
    $speaker.Speak($scene.narration)
  } finally {
    $speaker.Dispose()
  }
}

Write-Host "Generated $($storyboard.Count) offline narration clips."
