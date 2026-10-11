#!/usr/bin/env bash
# Installs or updates the nightly sandbox suite on the Oracle test server (wiki decision 100).
# Copies the built jar, the runner, the fixture and the SANDBOX secrets to ~/portal-e2e (owner-only), writes its env
# file from sandbox.env, and schedules it at 03:00 New York time. Re-run after changing the code (./gradlew bootJar first).
#   scripts/install-nightly-e2e.sh [ssh host, default oci] [--with-google]
# Google is left out unless --with-google: the sandbox shares the real Workspace, and its key can manage every group.
set -euo pipefail
cd "$(dirname "$0")/.."
host="${1:-oci}"
with_google=false
[[ "${2:-}" == "--with-google" ]] && with_google=true
jar=$(ls build/libs/portal-*.jar | grep -v plain | head -1)
[[ -f "$jar" ]] || { echo "No jar in build/libs: run ./gradlew bootJar first"; exit 1; }

dir=portal-e2e
ssh "$host" "mkdir -p ~/$dir/secrets ~/$dir/logs && chmod 700 ~/$dir ~/$dir/secrets"
scp -q "$jar" "$host:$dir/portal.jar"
scp -q deploy/nightly-e2e/run.sh "$host:$dir/run.sh"
scp -q sandbox-fixture.yml "$host:$dir/sandbox-fixture.yml"
scp -q secrets/github-app.pem secrets/vault-staging-ca.crt "$host:$dir/secrets/"
if $with_google; then scp -q secrets/google-service-account.json "$host:$dir/secrets/"; else ssh "$host" "rm -f ~/$dir/secrets/google-service-account.json"; fi

# The env file, without the sign-in client and (by default) without Google; values never printed.
python3 - "$with_google" <<'PY' | ssh "$host" "umask 077 && cat > ~/$dir/nightly.env"
import sys
with_google = sys.argv[1] == "true"
for line in open("sandbox.env"):
    line = line.rstrip("\n")
    if not line or line.lstrip().startswith("#") or "=" not in line: continue
    key = line.split("=", 1)[0].strip()
    if key.startswith("PORTAL_GOOGLE_CLIENT_"): continue
    if key.startswith("PORTAL_ADAPTERS_GOOGLE_") and not with_google: continue
    print(line)
if not with_google:
    print("PORTAL_ADAPTERS_GOOGLE_ENABLED=false")
PY
ssh "$host" "chmod 600 ~/$dir/nightly.env ~/$dir/sandbox-fixture.yml ~/$dir/secrets/* && chmod 700 ~/$dir/run.sh"

# 03:00 New York time, every night; replaces an earlier entry.
ssh "$host" "(crontab -l 2>/dev/null | grep -v 'portal-e2e/run.sh' | grep -v '^CRON_TZ=America/New_York\$'; echo 'CRON_TZ=America/New_York'; echo '0 3 * * * \$HOME/$dir/run.sh >/dev/null 2>&1') | crontab -"
echo "Installed on $host:~/$dir ($(basename "$jar"), Google: $with_google). Run now: ssh $host '~/$dir/run.sh; tail -3 ~/$dir/logs/history.txt'"
