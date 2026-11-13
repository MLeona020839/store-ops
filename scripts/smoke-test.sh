#!/usr/bin/env bash
# Full-flow smoke test against the running system (docker compose --profile apps up -d --build):
# a promotion created over HTTP in promotion-service must show up as tasks in
# store-task-service, through the outbox, Kafka and the consumer; cancelling it must clear them.
# Requests carry real signed tokens from scripts/dev-jwt.sh, and access control is checked too.
set -euo pipefail

PROMOTIONS=${PROMOTIONS:-http://localhost:8081}
TASKS=${TASKS:-http://localhost:8082}
STORE="smoke-$(date +%s)"
DEV_JWT="$(dirname "$0")/dev-jwt.sh"

HEAD_OFFICE=$("$DEV_JWT" token '"scope":"promotions:read promotions:write"')
READ_ONLY=$("$DEV_JWT" token '"scope":"promotions:read"')
EMPLOYEE=$("$DEV_JWT" token '"store_id":"'"$STORE"'"')
OTHER_STORE_EMPLOYEE=$("$DEV_JWT" token '"store_id":"another-store"')

wait_until() { # <description> <seconds> <command...>
  local what=$1 seconds=$2
  shift 2
  for _ in $(seq "$seconds"); do
    if "$@"; then echo "ok: $what"; return 0; fi
    sleep 1
  done
  echo "FAILED: timed out after ${seconds}s waiting for $what" >&2
  return 1
}

expect_status() { # <description> <expected status> <curl args...>
  local what=$1 expected=$2 actual
  shift 2
  actual=$(curl -s -o /dev/null -w '%{http_code}' "$@")
  if [ "$actual" = "$expected" ]; then
    echo "ok: $what ($actual)"
  else
    echo "FAILED: $what: expected $expected, got $actual" >&2
    exit 1
  fi
}

healthy() { curl -fs "$1/actuator/health" | grep -q '"status":"UP"'; }

# Open tasks of the smoke-test store; "until" far ahead so tasks due later count too.
open_tasks() { curl -fs -H "Authorization: Bearer $EMPLOYEE" "$TASKS/stores/$STORE/tasks?until=2099-12-31"; }
open_task_count() { open_tasks | grep -o '"promotionId":"'"$PROMOTION_ID"'"' | wc -l | tr -d ' '; }
has_open_tasks() { [ "$(open_task_count)" = "$1" ]; }

PROMOTION='{
  "title": "Alle kipfilet", "label": "25% korting", "kind": "PERCENT_OFF", "percent": 25,
  "validFrom": "2026-11-16", "validUntil": "2026-11-22",
  "storeIds": ["'"$STORE"'"], "productIds": ["product-42"]
}'

wait_until "promotion-service healthy" 120 healthy "$PROMOTIONS"
wait_until "store-task-service healthy" 120 healthy "$TASKS"

expect_status "no token is refused" 401 "$PROMOTIONS/promotions"
expect_status "a read-only token can't create promotions" 403 \
  -X POST "$PROMOTIONS/promotions" -H "Authorization: Bearer $READ_ONLY" -H 'Content-Type: application/json' -d "$PROMOTION"

PROMOTION_ID=$(
  curl -fs "$PROMOTIONS/promotions" -H "Authorization: Bearer $HEAD_OFFICE" -H 'Content-Type: application/json' -d "$PROMOTION" |
    grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
)
echo "created promotion $PROMOTION_ID for store $STORE"

wait_until "put-up and take-down tasks in store-task-service" 60 has_open_tasks 2

expect_status "another store's employee can't see these tasks" 403 \
  -H "Authorization: Bearer $OTHER_STORE_EMPLOYEE" "$TASKS/stores/$STORE/tasks"

curl -fs -X POST -H "Authorization: Bearer $HEAD_OFFICE" "$PROMOTIONS/promotions/$PROMOTION_ID/cancel" > /dev/null
echo "cancelled promotion $PROMOTION_ID"

wait_until "tasks cancelled (none open)" 60 has_open_tasks 0

echo "smoke test passed"
