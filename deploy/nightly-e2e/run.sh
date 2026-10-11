#!/usr/bin/env bash
# Nightly sandbox suite (build plan step 9), on the Oracle test server. Installed by scripts/install-nightly-e2e.sh.
# Runs the portal jar with the sandbox-e2e profile against a throwaway Postgres: apply, re-run, rollback, cleanup.
# Results: logs/latest.log (full), logs/history.txt (one line per night). Exit status 0 = all checks passed.
set -uo pipefail
cd "$(dirname "$0")"
mkdir -p logs
log="logs/e2e-$(date -u +%Y%m%d-%H%M%S).log"
db=h4i-nightly-e2e-db

docker rm -f "$db" >/dev/null 2>&1 || true
docker run -d --name "$db" -e POSTGRES_DB=portal -e POSTGRES_USER=portal -e POSTGRES_PASSWORD=portal -p 127.0.0.1:5437:5432 postgres:17 >/dev/null
trap 'docker rm -f "$db" >/dev/null 2>&1 || true' EXIT
for _ in $(seq 60); do docker exec "$db" pg_isready -h 127.0.0.1 -U portal >/dev/null 2>&1 && break; sleep 1; done

# Host networking: the throwaway database is on localhost:5437 and staging Vaultwarden on localhost:8443.
docker run --rm --name h4i-nightly-e2e --network host -v "$PWD:/work:z" -w /work --env-file nightly.env eclipse-temurin:25-jre \
  java -jar portal.jar --spring.profiles.active=sandbox-e2e --portal.sandbox-e2e.reset-database=true \
  --spring.datasource.url=jdbc:postgresql://localhost:5437/portal --spring.datasource.username=portal --spring.datasource.password=portal \
  > "$log" 2>&1
status=$?

result=$(grep -E "ALL PASSED|[0-9]+ FAILED|Sandbox suite stopped" "$log" | tail -1 | sed 's/^.*: //')
echo "$(date -u +%Y-%m-%dT%H:%MZ) exit=$status ${result:-no result (see $log)}" >> logs/history.txt
ln -sf "$(basename "$log")" logs/latest.log

# A failed night posts to Slack when nightly.env has PORTAL_NIGHTLY_ALERT_WEBHOOK (an incoming-webhook URL).
if [[ "$status" -ne 0 ]]; then
  hook=$(grep -E '^PORTAL_NIGHTLY_ALERT_WEBHOOK=' nightly.env | cut -d= -f2- || true)
  if [[ -n "${hook:-}" ]]; then
    fails=$(grep -E '^FAIL ' "$log" | head -5 | sed 's/^FAIL  */• /')
    text=$(printf 'H4I portal: the nightly sandbox suite failed (%s).\n%s\nFull log: %s:~/portal-e2e/%s' "${result:-no result}" "$fails" "$(hostname -s)" "$log")
    python3 -c 'import json,sys; print(json.dumps({"text": sys.argv[1]}))' "$text" | curl -s -m 20 -X POST -H 'Content-Type: application/json' --data @- "$hook" >/dev/null || true
  fi
fi
find logs -name 'e2e-*.log' -mtime +30 -delete
exit "$status"
