param([ValidateSet('Start','Stop','Status')][string]$Action='Start')
$ErrorActionPreference='Stop'
$redisProjectRoot=Split-Path $PSScriptRoot -Parent
$redisDistro='ErrorIntelligenceDev'
$redisValues=@{}
foreach ($redisLine in Get-Content -LiteralPath (Join-Path $redisProjectRoot '.env')) {
  if ($redisLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $redisValues[$Matches[1]]=$Matches[2] }
}
if ($redisValues['REDIS_PASSWORD'] -notmatch '^[a-f0-9]{64}$') { throw 'Expected a generated local Redis password. Configure remote Redis separately for other credentials.' }
$redisLocal=Join-Path $redisProjectRoot '.local\redis'
New-Item -ItemType Directory -Force -Path $redisLocal | Out-Null
$redisConf=Join-Path $redisLocal 'redis.conf'
$redisLinuxRoot=(& wsl.exe -d $redisDistro -- wslpath -a $redisLocal).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Install the project WSL distribution before starting Redis.' }
$redisConfiguration=@"
bind 127.0.0.1
protected-mode yes
port $($redisValues['REDIS_PORT'])
requirepass $($redisValues['REDIS_PASSWORD'])
daemonize yes
pidfile "$redisLinuxRoot/redis.pid"
logfile "$redisLinuxRoot/redis.log"
dir "$redisLinuxRoot"
save ""
appendonly no
maxmemory 128mb
maxmemory-policy noeviction
"@
[IO.File]::WriteAllText($redisConf,$redisConfiguration.Replace("`r`n","`n"),[Text.UTF8Encoding]::new($false))
$redisLinuxConf=(& wsl.exe -d $redisDistro -- wslpath -a $redisConf).Trim()
$redisHealthScript=Join-Path $redisLocal 'health.sh'
[IO.File]::WriteAllText($redisHealthScript, @'
#!/bin/sh
set -eu
read -r redis_password
REDISCLI_AUTH=$(printf '%s' "$redis_password" | tr -d '\r')
export REDISCLI_AUTH
result=$(redis-cli -p "$1" ping)
[ "$result" = PONG ] || exit 1
printf '%s\n' "$result"
'@.Replace("`r`n","`n"),[Text.UTF8Encoding]::new($false))
$redisLinuxHealth=(& wsl.exe -d $redisDistro -- wslpath -a $redisHealthScript).Trim()
if ($Action -eq 'Start') {
  & wsl.exe -d $redisDistro -u root -- redis-server $redisLinuxConf
  if ($LASTEXITCODE -ne 0) { throw 'Redis could not start. Inspect the project-local Redis log.' }
} elseif ($Action -eq 'Stop') {
  # Use only this project's pid file. Never terminate an unrelated Redis instance.
  & wsl.exe -d $redisDistro -u root -- sh -c 'test -f "$1/redis.pid" && kill -TERM "$(cat "$1/redis.pid")"' sh $redisLinuxRoot
  if ($LASTEXITCODE -ne 0) { throw 'Project Redis is not running.' }
}
if ($Action -ne 'Stop') {
  # Pass the credential through stdin, never command-line arguments or output.
  $redisValues['REDIS_PASSWORD'] | & wsl.exe -d $redisDistro -- sh $redisLinuxHealth $redisValues['REDIS_PORT']
  if ($LASTEXITCODE -ne 0) { throw 'Redis authentication/health check failed.' }
}
