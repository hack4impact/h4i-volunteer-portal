#!/usr/bin/env bash
# Fills the LOCAL dev database (compose.yaml) with the staging demo, then exits. For trying the views before staging.
# Usage (from Membership-Project-Portal/): scripts/demo-seed.sh [env file with PORTAL_DEMO_PERSONAS_*] [--reset]
# --reset empties the dev database first, including any member-DB import in it.
set -euo pipefail
cd "$(dirname "$0")/.."
env_file="${1:-demo.env}"
reset=false
[[ "${2:-}" == "--reset" ]] && reset=true
./gradlew bootRun -PenvFile="$env_file" --console=plain --args="--portal.demo.seed=true --portal.demo.reset=$reset \
  --spring.main.web-application-type=none --portal.sync.on-request=false --portal.agreements.on-request=false"
