#!/usr/bin/env bash
set -euo pipefail

domain="${1:-}"
if [[ ! "$domain" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]] || [[ "$domain" != *.* ]]; then
  echo 'Usage: bash infra/public-demo/init-env.sh your-name.duckdns.org' >&2
  exit 1
fi

directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
destination="$directory/public-demo.env"
if [[ -e "$destination" ]]; then
  echo "Keeping existing $destination; edit it instead of regenerating secrets." >&2
  exit 1
fi
command -v openssl >/dev/null || { echo 'OpenSSL is required.' >&2; exit 1; }
umask 077
postgres_password="$(openssl rand -hex 24)"
redis_password="$(openssl rand -hex 24)"
signing_key="$(openssl rand -base64 48 | tr -d '\n')"
cat > "$destination" <<EOF
APP_DOMAIN=$domain
APP_VERSION=0.1.0-cv-demo
POSTGRES_PASSWORD=$postgres_password
REDIS_PASSWORD=$redis_password
AUTH_SIGNING_KEY=$signing_key
SMTP_HOST=
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=
MAIL_FROM=
EOF
chmod 600 "$destination"
echo "Created $destination with private database, Redis, and signing secrets."
echo 'Fill SMTP_HOST, SMTP_USERNAME, SMTP_PASSWORD, and MAIL_FROM before deploying.'
