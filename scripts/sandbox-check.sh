#!/usr/bin/env bash
# Build plan step 6 finish line: loads sandbox-fixture.yml into a THROWAWAY Postgres, dry-runs against the real
# sandboxes, and compares the planned diff with the fixture's `expect` list. Your local dev database isn't touched.
# Usage (from Membership-Project-Portal/, SSH tunnel open for Vaultwarden):
#   scripts/sandbox-check.sh [env file, default sandbox.env]
set -euo pipefail
cd "$(dirname "$0")/.."
env_file="${1:-sandbox.env}"
name=h4i-sandbox-check-db

docker rm -f "$name" >/dev/null 2>&1 || true
docker run -d --name "$name" -e POSTGRES_DB=portal -e POSTGRES_USER=portal -e POSTGRES_PASSWORD=portal -p 127.0.0.1:5435:5432 postgres:17 >/dev/null
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
until docker exec "$name" psql -h 127.0.0.1 -U portal -d portal -c 'SELECT 1' >/dev/null 2>&1; do sleep 1; done

./gradlew bootRun -PenvFile="$env_file" --console=plain --args="--spring.profiles.active=sandbox-check \
  --portal.sandbox-check.reset-database=true \
  --spring.docker.compose.enabled=false \
  --spring.datasource.url=jdbc:postgresql://localhost:5435/portal \
  --spring.datasource.username=portal --spring.datasource.password=portal"
