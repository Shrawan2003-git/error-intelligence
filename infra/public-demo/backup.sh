#!/usr/bin/env bash
set -euo pipefail

directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
environment="$directory/public-demo.env"
compose="$directory/compose.yml"
backup_directory="$directory/backups"
if [[ ! -f "$environment" ]]; then
  echo 'public-demo.env is missing.' >&2
  exit 1
fi
umask 077
mkdir -p "$backup_directory"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
temporary="$backup_directory/error-intelligence-$stamp.dump.partial"
destination="$backup_directory/error-intelligence-$stamp.dump"
trap 'rm -f "$temporary"' EXIT

docker compose --env-file "$environment" -f "$compose" exec -T postgres \
  pg_dump -U error_intelligence -d error_intelligence -Fc > "$temporary"
if [[ ! -s "$temporary" ]]; then
  echo 'Backup is empty.' >&2
  exit 1
fi
docker compose --env-file "$environment" -f "$compose" exec -T postgres \
  pg_restore --list < "$temporary" >/dev/null
mv "$temporary" "$destination"
trap - EXIT
sha256sum "$destination"
echo 'Copy this backup to a second location; a VM disk failure also loses local backups.'
