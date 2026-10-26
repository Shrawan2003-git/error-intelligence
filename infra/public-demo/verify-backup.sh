#!/usr/bin/env bash
set -euo pipefail

backup="${1:-}"
if [[ ! -f "$backup" ]]; then
  echo 'Usage: bash infra/public-demo/verify-backup.sh path/to/backup.dump' >&2
  exit 1
fi

directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
environment="$directory/public-demo.env"
compose="$directory/compose.yml"
if [[ ! -f "$environment" ]]; then
  echo 'public-demo.env is missing.' >&2
  exit 1
fi

temporary_database="eip_restore_$(date -u +%s)_$$"
docker compose --env-file "$environment" -f "$compose" exec -T postgres \
  createdb -U error_intelligence "$temporary_database"
cleanup() {
  docker compose --env-file "$environment" -f "$compose" exec -T postgres \
    dropdb -U error_intelligence --if-exists --force "$temporary_database" >/dev/null || true
}
trap cleanup EXIT

docker compose --env-file "$environment" -f "$compose" exec -T postgres \
  pg_restore --exit-on-error --no-owner --no-acl -U error_intelligence \
  -d "$temporary_database" < "$backup"
version_count="$(docker compose --env-file "$environment" -f "$compose" exec -T postgres \
  psql -U error_intelligence -d "$temporary_database" -Atc \
  'select count(*) from public.flyway_schema_history')"
if [[ ! "$version_count" =~ ^[1-9][0-9]*$ ]]; then
  echo 'Restored database has no Flyway history.' >&2
  exit 1
fi
echo "Backup restored successfully into a temporary database ($version_count migrations)."
