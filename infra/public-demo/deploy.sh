#!/usr/bin/env bash
set -euo pipefail

directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$directory/../.." && pwd)"
environment="$directory/public-demo.env"
compose="$directory/compose.yml"

if [[ ! -f "$environment" ]]; then
  echo 'Run init-env.sh and fill the SMTP settings first.' >&2
  exit 1
fi
command -v docker >/dev/null || { echo 'Docker with the Compose plugin is required.' >&2; exit 1; }
command -v curl >/dev/null || { echo 'curl is required for the HTTPS smoke check.' >&2; exit 1; }

domain="$(sed -n 's/^APP_DOMAIN=//p' "$environment" | head -n 1)"
if [[ ! "$domain" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]] || [[ "$domain" != *.* ]]; then
  echo 'APP_DOMAIN must be a DNS hostname without https:// or a path.' >&2
  exit 1
fi

cd "$root"
docker compose --env-file "$environment" -f "$compose" config --quiet
docker compose --env-file "$environment" -f "$compose" up --build -d --wait

url="https://$domain/actuator/health/readiness"
for attempt in {1..30}; do
  if curl --fail --silent --show-error --max-time 10 "$url" | grep -q '"status":"UP"'; then
    echo "Public demo is ready at https://$domain/"
    exit 0
  fi
  sleep 10
done

echo 'Containers started, but public HTTPS readiness did not pass. Check DNS, ports 80/443, and Caddy logs.' >&2
docker compose --env-file "$environment" -f "$compose" ps >&2
exit 1
