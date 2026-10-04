#!/usr/bin/env bash
# One-command on-sale stampede against a running service.
# Usage: ADMIN_KEY=<admin key> ./burst.sh <BASE_URL>
# Optional: HOT_USERS=5000 (default 2000)
set -euo pipefail

BASE_URL="${1:?Usage: ADMIN_KEY=<key> ./burst.sh <BASE_URL>}"
: "${ADMIN_KEY:?Set ADMIN_KEY to the admin key of the service}"
HOT_USERS="${HOT_USERS:-2000}"

cd "$(dirname "$0")"

k6 run \
  -e BASE_URL="$BASE_URL" \
  -e ADMIN_KEY="$ADMIN_KEY" \
  -e HOT_USERS="$HOT_USERS" \
  burst/burst.js