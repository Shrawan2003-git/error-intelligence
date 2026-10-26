$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'initialize-local.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Local configuration initialization failed.' }
