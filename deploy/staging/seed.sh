#!/usr/bin/env bash
# Fills staging with the demo (a fictional chapter set up to show every view), then exits.
# The personas sign in with Google test accounts named in .env:
#   PORTAL_DEMO_PERSONAS_NATIONAL, _LEAD, _COLEAD, _VIEWER, _PROJECTLEAD, _MEMBER
# Usage: deploy/staging/seed.sh [--reset]   (--reset empties staging's database first; needed when it isn't empty)
set -euo pipefail
cd "$(dirname "$0")"
reset=false
[[ "${1:-}" == "--reset" ]] && reset=true

docker compose up -d --wait db
docker compose run --rm \
  -e PORTAL_DEMO_SEED=true -e PORTAL_DEMO_RESET="$reset" -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e PORTAL_SYNC_ONREQUEST=false -e PORTAL_AGREEMENTS_ONREQUEST=false \
  portal
echo "Seeded. Start (or restart) staging with ./up.sh and sign in as each persona at ${PORTAL_BASE_URL:-http://localhost:${STAGING_PORT:-8081}}"
